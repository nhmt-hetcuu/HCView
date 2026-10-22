package com.hetcuu.hcview.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hetcuu.hcview.model.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import java.io.DataOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RemoteA11yService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteA11yService"
        var instance: RemoteA11yService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /**
         * Tắt dịch vụ trợ năng ngay lập tức từ bên trong app
         */
        fun disableService(context: Context? = null): Boolean {
            val inst = instance
            if (inst != null) {
                inst.disableSelf()
                if (context != null) {
                    disableWithRoot(context)
                }
                AppState.updateA11yRunning(false)
                return true
            }
            if (context != null) {
                val ok = disableWithRoot(context)
                AppState.updateA11yRunning(false)
                return ok
            }
            return false
        }

        /**
         * Kiểm tra xem dịch vụ đã được kích hoạt trong Cài đặt hệ thống chưa
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expectedComponent = ComponentName(context, RemoteA11yService::class.java).flattenToString()
            val expectedShort = "${context.packageName}/.${RemoteA11yService::class.java.simpleName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(":").any {
                it.equals(expectedComponent, ignoreCase = true) || it.equals(expectedShort, ignoreCase = true)
            }
        }

        /**
         * Thử bật dịch vụ trợ năng tự động nếu thiết bị đã Root
         */
        fun enableWithRoot(context: Context): Boolean {
            val component = ComponentName(context, RemoteA11yService::class.java).flattenToString()
            return try {
                val process = Runtime.getRuntime().exec("su")
                DataOutputStream(process.outputStream).use { os ->
                    os.writeBytes("cur=\$(settings get secure enabled_accessibility_services)\n")
                    os.writeBytes("if [ \"\$cur\" = \"null\" ] || [ -z \"\$cur\" ]; then\n")
                    os.writeBytes("  settings put secure enabled_accessibility_services \"$component\"\n")
                    os.writeBytes("elif [[ \"\$cur\" != *\"$component\"* ]]; then\n")
                    os.writeBytes("  settings put secure enabled_accessibility_services \"\$cur:$component\"\n")
                    os.writeBytes("fi\n")
                    os.writeBytes("settings put secure accessibility_enabled 1\n")
                    os.writeBytes("exit\n")
                    os.flush()
                }
                val exitCode = process.waitFor()
                exitCode == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Tắt dịch vụ trợ năng qua root
         */
        private fun disableWithRoot(context: Context): Boolean {
            val component = ComponentName(context, RemoteA11yService::class.java).flattenToString()
            return try {
                val process = Runtime.getRuntime().exec("su")
                DataOutputStream(process.outputStream).use { os ->
                    os.writeBytes("cur=\$(settings get secure enabled_accessibility_services)\n")
                    os.writeBytes("new=\$(echo \"\$cur\" | sed 's#$component##g' | sed 's#::#:#g' | sed 's#^:##g' | sed 's#:\$##g')\n")
                    os.writeBytes("settings put secure enabled_accessibility_services \"\$new\"\n")
                    os.writeBytes("exit\n")
                    os.flush()
                }
                val exitCode = process.waitFor()
                exitCode == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Mở màn hình Cài đặt Trợ năng của hệ thống Android
         */
        fun openAccessibilitySettings(context: Context) {
            try {
                val component = ComponentName(context, RemoteA11yService::class.java).flattenToString()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(":settings:fragment_args_key", component)
                    putExtra(":settings:show_fragment_args", Bundle().apply {
                        putString(":settings:fragment_args_key", component)
                    })
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }

        /**
         * Lấy lệnh ADB chuẩn để bật nhanh qua máy tính
         */
        fun getAdbCommand(context: Context): String {
            val component = ComponentName(context, RemoteA11yService::class.java).flattenToString()
            return "adb shell settings put secure enabled_accessibility_services $component && adb shell settings put secure accessibility_enabled 1"
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "RemoteA11yService connected")
        AppState.updateA11yRunning(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        serviceScope.launch {
            AppState.activeRegion.collect { region ->
                updateOverlay(region)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        instance = null
        AppState.updateA11yRunning(false)
        Log.i(TAG, "RemoteA11yService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we only use service for dispatching gestures and text
    }

    override fun onInterrupt() {
        Log.w(TAG, "RemoteA11yService interrupted")
    }

    // --- Overlay Indicator ---

    private fun updateOverlay(region: Rect) {
        mainHandler.post {
            try {
                val wm = windowManager ?: return@post
                if (overlayView == null) {
                    val border = View(this).apply {
                        val shape = GradientDrawable().apply {
                            setStroke(4, Color.parseColor("#00E5FF")) // Electric Cyan
                            setColor(Color.parseColor("#2500E5FF")) // Subtle glowing tint
                            cornerRadius = 4f
                        }
                        background = shape
                    }
                    overlayView = border

                    val params = WindowManager.LayoutParams(
                        region.width(),
                        region.height(),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = region.left
                        y = region.top
                    }
                    wm.addView(border, params)
                } else {
                    val params = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return@post
                    params.x = region.left
                    params.y = region.top
                    params.width = region.width()
                    params.height = region.height()
                    wm.updateViewLayout(overlayView, params)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update overlay", e)
            }
        }
    }

    private fun removeOverlay() {
        mainHandler.post {
            try {
                if (overlayView != null && windowManager != null) {
                    windowManager?.removeView(overlayView)
                    overlayView = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay", e)
            }
        }
    }

    // --- Gesture Dispatch ---

    private fun stroke(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        stroke(path, 50)
    }

    fun longPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        stroke(path, 700)
    }

    fun scroll(region: Rect, delta: Int, speedMultiplier: Float = 1.0f) {
        val steps = (delta / 60f).coerceIn(-5f, 5f)
        val displayMetrics = resources.displayMetrics
        val displayW = displayMetrics.widthPixels.toFloat()
        val displayH = displayMetrics.heightPixels.toFloat()

        // Tính quãng đường cuộn vừa vặn theo dải nhìn, tránh nhảy trang quá đà
        val mult = speedMultiplier.coerceIn(0.2f, 3.0f)
        val baseStepDist = (region.height() * 0.65f).coerceIn(140f, 320f) * mult
        val dist = baseStepDist * steps

        val cx = region.exactCenterX().coerceIn(120f, (displayW - 120f).coerceAtLeast(120f))
        val cy = region.exactCenterY().coerceIn(250f, (displayH - 250f).coerceAtLeast(250f))

        val halfDist = dist / 2f
        val startY = (cy + halfDist).coerceIn(80f, (displayH - 80f).coerceAtLeast(80f))
        val endY = (cy - halfDist).coerceIn(80f, (displayH - 80f).coerceAtLeast(80f))

        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        // Thời gian vuốt 320ms giúp dừng dứt khoát tại điểm dừng, tránh kích hoạt quán tính (fling) cuộn trôi quá nhanh
        stroke(path, 320)
    }

    fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // --- Typing and Key Inputs ---

    fun typeText(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ic = inputMethod?.currentInputConnection
            if (ic != null) {
                ic.commitText(text, 1, null)
                return
            }
        }

        // Fallback for Android 12 and below or if inputMethod is null
        mainHandler.post {
            val root = rootInActiveWindow ?: return@post
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val currentText = focusedNode.text?.toString() ?: ""
                val selStart = focusedNode.textSelectionStart
                val newText = if (selStart in 0..currentText.length) {
                    currentText.substring(0, selStart) + text + currentText.substring(selStart)
                } else {
                    currentText + text
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                focusedNode.recycle()
            }
            root.recycle()
        }
    }

    fun key(keyName: String) {
        when (keyName) {
            "ENTER" -> sendKeyOrAction(KeyEvent.KEYCODE_ENTER, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            "BACKSPACE" -> sendBackspace()
            "TAB" -> sendKeyCode(KeyEvent.KEYCODE_TAB)
            "ESC" -> back()
            "SPACE" -> typeText(" ")
            "UP" -> sendKeyCode(KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            "LEFT" -> sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT" -> sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            else -> Log.d(TAG, "Unknown key name: $keyName")
        }
    }

    private fun sendKeyCode(code: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ic = inputMethod?.currentInputConnection
            if (ic != null) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return
            }
        }
    }

    private fun sendKeyOrAction(code: Int, actionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ic = inputMethod?.currentInputConnection
            if (ic != null) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return
            }
        }
        mainHandler.post {
            val root = rootInActiveWindow ?: return@post
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    focusedNode.performAction(actionId)
                }
                focusedNode.recycle()
            }
            root.recycle()
        }
    }

    private fun sendBackspace() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ic = inputMethod?.currentInputConnection
            if (ic != null) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                return
            }
        }
        mainHandler.post {
            val root = rootInActiveWindow ?: return@post
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val currentText = focusedNode.text?.toString() ?: ""
                val selStart = focusedNode.textSelectionStart
                if (currentText.isNotEmpty()) {
                    val newText = if (selStart > 0 && selStart <= currentText.length) {
                        currentText.substring(0, selStart - 1) + currentText.substring(selStart)
                    } else {
                        currentText.dropLast(1)
                    }
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                    }
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                focusedNode.recycle()
            }
            root.recycle()
        }
    }
}
