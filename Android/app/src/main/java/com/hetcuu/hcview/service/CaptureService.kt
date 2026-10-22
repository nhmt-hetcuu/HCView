package com.hetcuu.hcview.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.hetcuu.hcview.HcViewApp
import com.hetcuu.hcview.R
import com.hetcuu.hcview.model.AppState
import com.hetcuu.hcview.model.ConnectionMode
import com.hetcuu.hcview.model.ConnectionStatus
import com.hetcuu.hcview.model.RemoteConfig
import com.hetcuu.hcview.network.DirectServer
import com.hetcuu.hcview.network.NetworkUtils
import com.hetcuu.hcview.network.RelayClient
import com.hetcuu.hcview.protocol.CommandDispatcher
import com.hetcuu.hcview.protocol.FramePacker
import com.hetcuu.hcview.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Arrays

class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.hetcuu.hcview.START"
        const val ACTION_STOP = "com.hetcuu.hcview.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var captureJob: Job? = null

    private lateinit var config: RemoteConfig
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var screenBmp: Bitmap? = null
    private var scaledBmp: Bitmap? = null
    private var previewBmp: Bitmap? = null
    private val pixelBuffer = IntArray(FramePacker.W * FramePacker.H)

    @Volatile
    private var latestPackedFrame: ByteArray? = null
    private var lastSentFrame: ByteArray? = null
    private var lastFrameSentTimeMs: Long = 0L

    private var directServer: DirectServer? = null
    private var relayClient: RelayClient? = null
    private lateinit var commandDispatcher: CommandDispatcher

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != Activity.RESULT_OK || resultData == null) {
                Log.e(TAG, "Invalid MediaProjection intent data")
                stopSelf()
                return START_NOT_STICKY
            }

            startForegroundServiceNotification()
            startCapture(resultCode, resultData)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val stopIntent = Intent(this, CaptureService::class.java).apply {
            this.action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, HcViewApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        isRunning = true
        AppState.resetStats()

        // Đọc kích thước màn hình thực bao gồm cả system bars
        calculateScreenMetrics()

        config = RemoteConfig.load(this, screenWidth, screenHeight)
        AppState.updateActiveRegion(config.region)

        commandDispatcher = CommandDispatcher(
            config = config,
            screenWidthProvider = { screenWidth },
            screenHeightProvider = { screenHeight }
        )

        // Phân bổ bitmaps dùng lại
        scaledBmp = Bitmap.createBitmap(FramePacker.W, FramePacker.H, Bitmap.Config.ARGB_8888)
        previewBmp = Bitmap.createBitmap(FramePacker.W, FramePacker.H, Bitmap.Config.ARGB_8888)

        // MediaProjection & VirtualDisplay
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)

        // API 34+: BẮT BUỘC registerCallback trước createVirtualDisplay
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                stopCapture()
                stopSelf()
            }
        }, null)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "HcViewRemoteDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        // Khởi động mạng và khoá giữ màn hình luôn mở
        NetworkUtils.acquireLocks(this, keepScreenOn = config.keepScreenOn)
        startNetworking()

        // Đăng ký callback chụp hình ngay khi cần (pan M hoặc client mới)
        AppState.onImmediateFrameRequested = {
            serviceScope.launch {
                captureAndSendFrame(forceSend = true)
            }
        }

        // Bắt đầu vòng lặp chụp theo FPS
        startCaptureLoop()
    }

    private fun calculateScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
        Log.i(TAG, "Screen size: ${screenWidth}x$screenHeight, density: $screenDensity")
    }

    private fun startNetworking() {
        if (config.mode == ConnectionMode.DIRECT_SERVER) {
            directServer = DirectServer(
                port = config.directPort,
                onCommand = { cmd -> commandDispatcher.dispatch(cmd) },
                currentFrame = { latestPackedFrame }
            )
            directServer?.start()
        } else {
            relayClient = RelayClient(
                config = config,
                onCommand = { cmd -> commandDispatcher.dispatch(cmd) },
                currentFrame = { latestPackedFrame }
            )
            relayClient?.start()
        }
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            var frameCount = 0
            var lastStatTime = System.currentTimeMillis()

            while (isActive && isRunning) {
                val loopStartTime = System.currentTimeMillis()

                captureAndSendFrame(forceSend = false)
                frameCount++

                val now = System.currentTimeMillis()
                if (now - lastStatTime >= 1000L) {
                    val fps = frameCount * 1000f / (now - lastStatTime)
                    AppState.updateActualFps(fps)
                    frameCount = 0
                    lastStatTime = now
                }

                val frameIntervalMs = (1000L / config.fps.coerceAtLeast(1))
                val elapsed = System.currentTimeMillis() - loopStartTime
                val sleepTime = frameIntervalMs - elapsed
                if (sleepTime > 0) {
                    delay(sleepTime)
                }
            }
        }
    }

    private fun captureAndSendFrame(forceSend: Boolean) {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val widthWithPad = rowStride / pixelStride

            if (screenBmp == null || screenBmp?.width != widthWithPad || screenBmp?.height != image.height) {
                screenBmp = Bitmap.createBitmap(widthWithPad, image.height, Bitmap.Config.ARGB_8888)
            }
            screenBmp?.copyPixelsFromBuffer(buffer)

            val screen = screenBmp ?: return
            val targetScaled = scaledBmp ?: return
            val currentRegion = AppState.activeRegion.value

            // Kẹp vùng chụp trong giới hạn ảnh thực tế (không tính phần đệm)
            val safeLeft = currentRegion.left.coerceIn(0, image.width - 1)
            val safeTop = currentRegion.top.coerceIn(0, image.height - 1)
            val safeRight = currentRegion.right.coerceIn(safeLeft + 1, image.width)
            val safeBottom = currentRegion.bottom.coerceIn(safeTop + 1, image.height)
            val safeRegion = Rect(safeLeft, safeTop, safeRight, safeBottom)

            // Co và cắt ảnh về 191x62
            FramePacker.cropScale(screen, safeRegion, targetScaled, pixelBuffer)

            // Đóng gói 2-bit (2976 byte, 4 mức xám chuẩn LCD 191x62) kèm bộ lọc làm nét & dither
            val frameBytes = FramePacker.packFrame2Bit(
                pixelBuffer,
                config.invert,
                contrastLevel = config.contrastLevel,
                sharpen = (config.contrastLevel > 0),
                useDither = (config.ditherMode == 1)
            )

            latestPackedFrame = frameBytes



            val now = System.currentTimeMillis()
            val isDuplicate = lastSentFrame != null && Arrays.equals(frameBytes, lastSentFrame)
            val isKeepaliveDue = (now - lastFrameSentTimeMs) >= 2000L

            // Gửi nếu forceSend (ví dụ vừa kết nối hoặc vừa pan), hoặc không trùng, hoặc đã quá 2 giây
            if (forceSend || !isDuplicate || isKeepaliveDue) {
                if (config.mode == ConnectionMode.DIRECT_SERVER) {
                    directServer?.sendFrame(frameBytes)
                } else {
                    relayClient?.sendFrame(frameBytes)
                }
                lastSentFrame = frameBytes.clone()
                lastFrameSentTimeMs = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame capture error", e)
        } finally {
            image.close()
        }
    }

    private fun stopCapture() {
        isRunning = false
        captureJob?.cancel()
        AppState.onImmediateFrameRequested = null

        // Thông báo dừng tới thiết bị
        directServer?.notifyStopSharing()
        try {
            directServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping direct server", e)
        }
        directServer = null

        relayClient?.stop()
        relayClient = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        screenBmp?.recycle()
        screenBmp = null

        NetworkUtils.releaseLocks()
        AppState.updateClientCount(0)
        AppState.updateConnectedClients(emptyList())
        AppState.updateStatus(ConnectionStatus.STOPPED, "Đã dừng")
        Log.i(TAG, "CaptureService stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }
}
