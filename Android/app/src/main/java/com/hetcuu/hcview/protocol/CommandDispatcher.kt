package com.hetcuu.hcview.protocol

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hetcuu.hcview.model.AppState
import com.hetcuu.hcview.model.RemoteConfig
import com.hetcuu.hcview.service.RemoteA11yService
import kotlin.math.floor
import kotlin.math.max

class CommandDispatcher(
    private val config: RemoteConfig,
    private val screenWidthProvider: () -> Int,
    private val screenHeightProvider: () -> Int
) {
    companion object {
        private const val TAG = "CommandDispatcher"
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Quy đổi toạ độ theo mục 5.3:
     * px = region_x + floor(x * region_w / 192)
     * py = region_y + floor(y * region_h / 62)
     */
    fun mapX(x: Int, region: Rect): Float {
        val rw = region.right - region.left
        return (region.left + floor(x.toFloat() * rw / 192f)).toFloat()
    }

    fun mapY(y: Int, region: Rect): Float {
        val rh = region.bottom - region.top
        return (region.top + floor(y.toFloat() * rh / 62f)).toFloat()
    }

    /**
     * Xử lý text lệnh từ thiết bị.
     * Chạy trên luồng WebSocket và chuyển tiếp bất đồng bộ, không block.
     */
    fun dispatch(cmd: String) {
        if (cmd.isEmpty() || cmd.startsWith("{")) return

        val code = cmd[0]
        val arg = if (cmd.length > 1) cmd.substring(1) else ""

        mainHandler.post {
            val a11y = RemoteA11yService.instance
            val region = AppState.activeRegion.value

            try {
                when (code) {
                    'C' -> {
                        // C<x>,<y> - Chạm tại điểm quy đổi
                        val parts = arg.split(',')
                        if (parts.size >= 2) {
                            val x = parts[0].trim().toInt()
                            val y = parts[1].trim().toInt()
                            val px = mapX(x, region)
                            val py = mapY(y, region)
                            a11y?.tap(px, py) ?: Log.w(TAG, "A11y service not available for Tap")
                        }
                    }
                    'P' -> {
                        // P<x>,<y> - Nhấn giữ tại điểm quy đổi
                        val parts = arg.split(',')
                        if (parts.size >= 2) {
                            val x = parts[0].trim().toInt()
                            val y = parts[1].trim().toInt()
                            val px = mapX(x, region)
                            val py = mapY(y, region)
                            a11y?.longPress(px, py) ?: Log.w(TAG, "A11y service not available for LongPress")
                        }
                    }
                    'S' -> {
                        // S<delta> - Cuộn (delta > 0 = cuộn xuống, ngón tay vuốt lên)
                        val delta = arg.trim().toInt()
                        a11y?.scroll(region, delta, config.scrollSpeed) ?: Log.w(TAG, "A11y service not available for Scroll")
                    }
                    'M' -> {
                        // M<hx>,<hy> - Dời vùng chụp
                        val parts = arg.split(',')
                        if (parts.size >= 2) {
                            val hx = parts[0].trim().toInt().coerceIn(-1, 1)
                            val hy = parts[1].trim().toInt().coerceIn(-1, 1)
                            panRegion(hx, hy)
                        }
                    }
                    'T' -> {
                        // T<chuỗi UTF-8> - Gõ chữ (giữ nguyên toàn bộ chuỗi)
                        a11y?.typeText(arg) ?: Log.w(TAG, "A11y service not available for TypeText")
                    }
                    'K' -> {
                        // K<key> - Phím điều khiển
                        val keyName = arg.trim().uppercase()
                        a11y?.key(keyName) ?: Log.w(TAG, "A11y service not available for Key: $keyName")
                    }
                    'Z' -> {
                        // Z<zoom> - Điều chỉnh độ phóng to dải ngang (1x, 1.5x, 2x, 3x hoặc +, -)
                        handleZoom(arg.trim())
                    }
                    'V' -> {
                        // V<speed> - Điều chỉnh tốc độ cuộn trang (0.5x, 1.0x, 1.5x, 2.0x hoặc +, -)
                        handleScrollSpeed(arg.trim())
                    }
                    else -> {
                        Log.d(TAG, "Bỏ qua lệnh lạ: $cmd")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi khi xử lý lệnh: $cmd", e)
            }
        }
    }

    /**
     * Dời vùng chụp theo mục 5.4 và phát hình ảnh mới ngay lập tức.
     */
    fun panRegion(hx: Int, hy: Int) {
        val current = AppState.activeRegion.value
        val screenW = screenWidthProvider()
        val screenH = screenHeightProvider()

        val stepX = max(1, floor(current.width() * config.panRatio).toInt())
        val stepY = max(1, floor(current.height() * config.panRatio).toInt())

        val maxLeft = max(0, screenW - current.width())
        val maxTop = max(0, screenH - current.height())

        val newLeft = (current.left + hx * stepX).coerceIn(0, maxLeft)
        val newTop = (current.top + hy * stepY).coerceIn(0, maxTop)

        val newRegion = Rect(newLeft, newTop, newLeft + current.width(), newTop + current.height())
        AppState.updateActiveRegion(newRegion)

        // Sau khi dời, gửi khung mới ngay, không chờ nhịp FPS
        AppState.requestImmediateFrame()
    }

    /**
     * Tính toán mức zoom mới từ chuỗi tham số (ví dụ: "1", "1.5", "2", "+", "-").
     */
    fun calculateNewZoom(arg: String, currentZoom: Float): Float {
        return when (arg.lowercase()) {
            "+" -> when {
                currentZoom < 1.4f -> 1.5f
                currentZoom < 1.9f -> 2.0f
                currentZoom < 2.9f -> 3.0f
                else -> 4.0f
            }
            "-" -> when {
                currentZoom > 2.5f -> 2.0f
                currentZoom > 1.8f -> 1.5f
                currentZoom > 1.2f -> 1.0f
                else -> 1.0f
            }
            else -> arg.toFloatOrNull()?.coerceIn(1.0f, 4.0f) ?: currentZoom
        }
    }

    /**
     * Tính toán Rect vùng chụp mới theo mức zoom, giữ nguyên tâm khung hình hiện tại.
     */
    fun calculateZoomRegion(zoom: Float, currentRegion: Rect, screenW: Int, screenH: Int): Rect {
        val targetZoom = zoom.coerceIn(1.0f, 4.0f)
        val rw = (screenW / targetZoom).toInt().coerceIn(RemoteConfig.LCD_W, screenW)
        val rh = (rw * RemoteConfig.LCD_H / RemoteConfig.LCD_W).coerceAtLeast(RemoteConfig.LCD_H)

        val centerX = (currentRegion.left + currentRegion.right) / 2
        val centerY = (currentRegion.top + currentRegion.bottom) / 2

        val maxLeft = max(0, screenW - rw)
        val maxTop = max(0, screenH - rh)

        val newLeft = (centerX - rw / 2).coerceIn(0, maxLeft)
        val newTop = (centerY - rh / 2).coerceIn(0, maxTop)

        return Rect(newLeft, newTop, newLeft + rw, newTop + rh)
    }

    /**
     * Xử lý lệnh Zoom từ ESP32 và phát khung hình mới ngay lập tức.
     */
    fun handleZoom(arg: String) {
        val targetZoom = calculateNewZoom(arg, config.zoomLevel)
        config.zoomLevel = targetZoom

        val screenW = screenWidthProvider()
        val screenH = screenHeightProvider()
        val current = AppState.activeRegion.value
        val newRegion = calculateZoomRegion(targetZoom, current, screenW, screenH)

        config.region = newRegion
        AppState.updateActiveRegion(newRegion)

        // Sau khi zoom, gửi khung mới ngay lập tức
        AppState.requestImmediateFrame()
        Log.i(TAG, "Lệnh Zoom từ ESP32: '$arg' -> ${targetZoom}x, vùng chụp mới: $newRegion")
    }

    /**
     * Tính toán tốc độ cuộn mới từ chuỗi tham số (ví dụ: "0.5", "1", "1.0", "1.5", "2", "+", "-").
     */
    fun calculateNewScrollSpeed(arg: String, currentSpeed: Float): Float {
        return when (arg.lowercase()) {
            "+" -> when {
                currentSpeed < 0.7f -> 1.0f
                currentSpeed < 1.3f -> 1.5f
                currentSpeed < 1.8f -> 2.0f
                else -> 3.0f
            }
            "-" -> when {
                currentSpeed > 1.8f -> 1.5f
                currentSpeed > 1.3f -> 1.0f
                currentSpeed > 0.7f -> 0.5f
                else -> 0.2f
            }
            else -> arg.toFloatOrNull()?.coerceIn(0.2f, 3.0f) ?: currentSpeed
        }
    }

    /**
     * Xử lý lệnh chỉnh tốc độ cuộn từ ESP32.
     */
    fun handleScrollSpeed(arg: String) {
        val targetSpeed = calculateNewScrollSpeed(arg, config.scrollSpeed)
        config.scrollSpeed = targetSpeed
        AppState.updateScrollSpeed(targetSpeed)
        Log.i(TAG, "Lệnh chỉnh tốc độ cuộn từ ESP32: '$arg' -> ${targetSpeed}x")
    }
}
