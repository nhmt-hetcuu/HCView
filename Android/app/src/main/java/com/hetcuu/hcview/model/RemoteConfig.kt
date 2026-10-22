package com.hetcuu.hcview.model

import android.content.Context
import android.graphics.Rect

enum class ConnectionMode {
    DIRECT_SERVER,
    RELAY_SERVER
}

enum class BitMode {
    MODE_1BIT,
    MODE_2BIT
}

data class RemoteConfig(
    var mode: ConnectionMode = ConnectionMode.DIRECT_SERVER,
    var directPort: Int = 8268,
    var wsPath: String = "/ws/remote",
    var relayServerUrl: String = "http://192.168.1.10:8000",
    var relayUsername: String = "",
    var relayToken: String = "",
    var relayDeviceId: String = "",
    var bitMode: BitMode = BitMode.MODE_2BIT,
    var invert: Boolean = false,
    var fps: Int = 5,
    var panRatio: Float = 0.5f,
    var region: Rect = Rect(0, 200, 1080, 550), // Dải ngang
    var showOverlay: Boolean = true,
    var keepScreenOn: Boolean = true,
    var zoomLevel: Float = 1.5f, // Mặc định phóng to 1.5x để chữ to rõ nét
    var contrastLevel: Int = 1,
    var ditherMode: Int = 0,
    var lcdPaletteId: Int = 0,
    var showLcdGrid: Boolean = true,
    var scrollSpeed: Float = 1.0f // Tốc độ cuộn trang (mặc định 1.0x êm ái, dừng đúng vị trí)
) {
    companion object {
        const val LCD_W = 191
        const val LCD_H = 62
        private const val PREFS_NAME = "hcview_remote_prefs"

        fun load(context: Context, screenWidth: Int = 1080, screenHeight: Int = 2400): RemoteConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val modeStr = prefs.getString("che_do", ConnectionMode.DIRECT_SERVER.name)
            val mode = runCatching { ConnectionMode.valueOf(modeStr ?: "") }.getOrDefault(ConnectionMode.DIRECT_SERVER)

            val bitModeStr = prefs.getString("bit_mode", BitMode.MODE_2BIT.name)
            val bitMode = runCatching { BitMode.valueOf(bitModeStr ?: "") }.getOrDefault(BitMode.MODE_2BIT)

            val zoom = prefs.getFloat("zoom_level", 1.5f).coerceIn(1.0f, 4.0f)
            val regionW = (screenWidth / zoom).toInt().coerceIn(LCD_W, screenWidth)
            val defaultH = (regionW * LCD_H / LCD_W).coerceAtLeast(LCD_H)

            val defaultX = ((screenWidth - regionW) / 2).coerceAtLeast(0)
            val defaultY = ((screenHeight - defaultH) / 2).coerceAtLeast(0)
            val rx = prefs.getInt("region_x", defaultX).coerceIn(0, (screenWidth - regionW).coerceAtLeast(0))
            val ry = prefs.getInt("region_y", defaultY).coerceIn(0, (screenHeight - defaultH).coerceAtLeast(0))
            val rw = prefs.getInt("region_w", regionW).coerceIn(LCD_W, screenWidth)
            val rh = prefs.getInt("region_h", defaultH).coerceIn(LCD_H, screenHeight)

            return RemoteConfig(
                mode = mode,
                directPort = prefs.getInt("direct_port", 8268),
                wsPath = prefs.getString("ws_path", "/ws/remote") ?: "/ws/remote",
                relayServerUrl = prefs.getString("server_url", "http://192.168.1.10:8000") ?: "http://192.168.1.10:8000",
                relayUsername = prefs.getString("username", "") ?: "",
                relayToken = prefs.getString("token", "") ?: "",
                relayDeviceId = prefs.getString("device_id", "") ?: "",
                bitMode = bitMode,
                invert = prefs.getBoolean("invert", false),
                fps = prefs.getInt("fps", 5).coerceIn(1, 30),
                panRatio = prefs.getFloat("pan_ty_le", 0.5f).coerceIn(0.05f, 1.0f),
                region = Rect(rx, ry, rx + rw, ry + rh),
                showOverlay = prefs.getBoolean("show_overlay", true),
                keepScreenOn = prefs.getBoolean("keep_screen_on", true),
                zoomLevel = zoom,
                contrastLevel = prefs.getInt("contrast_level", 1),
                ditherMode = prefs.getInt("dither_mode", 0),
                lcdPaletteId = prefs.getInt("lcd_palette_id", 0),
                showLcdGrid = prefs.getBoolean("show_lcd_grid", true),
                scrollSpeed = prefs.getFloat("scroll_speed", 1.0f).coerceIn(0.2f, 3.0f)
            )
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("che_do", mode.name)
            .putInt("direct_port", directPort)
            .putString("ws_path", wsPath)
            .putString("server_url", relayServerUrl)
            .putString("username", relayUsername)
            .putString("token", relayToken)
            .putString("device_id", relayDeviceId)
            .putString("bit_mode", bitMode.name)
            .putBoolean("invert", invert)
            .putInt("fps", fps)
            .putFloat("pan_ty_le", panRatio)
            .putInt("region_x", region.left)
            .putInt("region_y", region.top)
            .putInt("region_w", region.width())
            .putInt("region_h", region.height())
            .putBoolean("show_overlay", showOverlay)
            .putBoolean("keep_screen_on", keepScreenOn)
            .putFloat("zoom_level", zoomLevel)
            .putInt("contrast_level", contrastLevel)
            .putInt("dither_mode", ditherMode)
            .putInt("lcd_palette_id", lcdPaletteId)
            .putBoolean("show_lcd_grid", showLcdGrid)
            .putFloat("scroll_speed", scrollSpeed)
            .apply()
    }
}
