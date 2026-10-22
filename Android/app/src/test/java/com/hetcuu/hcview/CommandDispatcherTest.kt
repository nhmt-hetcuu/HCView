package com.hetcuu.hcview

import android.graphics.Rect
import com.hetcuu.hcview.model.RemoteConfig
import com.hetcuu.hcview.protocol.CommandDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.floor

class CommandDispatcherTest {

    @Test
    fun testCoordinateMappingCenter() {
        val config = RemoteConfig()
        val dispatcher = CommandDispatcher(config, { 1080 }, { 2400 })
        val region = Rect(0, 0, 1080, 350)

        // (96, 31) is the center coordinate on 191x62 LCD
        val px = dispatcher.mapX(96, region)
        val py = dispatcher.mapY(31, region)

        // 96 * 1080 / 192 = 540
        assertEquals(540f, px, 0.001f)
        // 31 * 350 / 62 = 175
        assertEquals(175f, py, 0.001f)
    }

    @Test
    fun testCoordinateMappingWithOffset() {
        val config = RemoteConfig()
        val dispatcher = CommandDispatcher(config, { 1080 }, { 2400 })
        val region = Rect(100, 500, 100 + 1920, 500 + 620)

        val px0 = dispatcher.mapX(0, region)
        val py0 = dispatcher.mapY(0, region)
        assertEquals(100f, px0, 0.001f)
        assertEquals(500f, py0, 0.001f)

        val pxMax = dispatcher.mapX(191, region)
        val pyMax = dispatcher.mapY(61, region)
        // 100 + floor(191 * 1920 / 192) = 100 + 1910 = 2010
        assertEquals(2010f, pxMax, 0.001f)
        // 500 + floor(61 * 620 / 62) = 500 + 610 = 1110
        assertEquals(1110f, pyMax, 0.001f)
    }

    @Test
    fun testCommandParsingPrefixes() {
        val rawCommands = listOf(
            "C96,31",
            "P96,31",
            "S60",
            "S-60",
            "M0,-1",
            "TXin chào tiếng Việt có dấu, phẩy và {ngoặc}",
            "KENTER",
            "KESC",
            "KBACKSPACE",
            "KTAB",
            "Z1",
            "Z1.5",
            "Z2",
            "V0.5",
            "V1.0",
            "V2.0"
        )

        for (cmd in rawCommands) {
            val code = cmd[0]
            val arg = cmd.substring(1)
            when (code) {
                'C', 'P' -> {
                    val parts = arg.split(',')
                    assertEquals(2, parts.size)
                    assertEquals(96, parts[0].toInt())
                    assertEquals(31, parts[1].toInt())
                }
                'S' -> {
                    val delta = arg.toInt()
                    org.junit.Assert.assertTrue(delta == 60 || delta == -60)
                }
                'M' -> {
                    val parts = arg.split(',')
                    assertEquals(2, parts.size)
                    assertEquals(0, parts[0].toInt())
                    assertEquals(-1, parts[1].toInt())
                }
                'T' -> {
                    assertEquals("Xin chào tiếng Việt có dấu, phẩy và {ngoặc}", arg)
                }
                'K' -> {
                    org.junit.Assert.assertTrue(
                        arg in listOf("ENTER", "ESC", "BACKSPACE", "TAB")
                    )
                }
                'Z' -> {
                    org.junit.Assert.assertTrue(
                        arg in listOf("1", "1.5", "2")
                    )
                }
                'V' -> {
                    org.junit.Assert.assertTrue(
                        arg in listOf("0.5", "1.0", "2.0")
                    )
                }
            }
        }
    }

    @Test
    fun testZoomCalculation() {
        val config = RemoteConfig()
        val dispatcher = CommandDispatcher(config, { 1080 }, { 2400 })

        // Test absolute zoom presets
        assertEquals(1.0f, dispatcher.calculateNewZoom("1", 1.5f), 0.001f)
        assertEquals(1.5f, dispatcher.calculateNewZoom("1.5", 1.0f), 0.001f)
        assertEquals(2.0f, dispatcher.calculateNewZoom("2", 1.5f), 0.001f)
        assertEquals(2.0f, dispatcher.calculateNewZoom("2.0", 1.0f), 0.001f)

        // Test step zoom (+)
        assertEquals(1.5f, dispatcher.calculateNewZoom("+", 1.0f), 0.001f)
        assertEquals(2.0f, dispatcher.calculateNewZoom("+", 1.5f), 0.001f)
        assertEquals(3.0f, dispatcher.calculateNewZoom("+", 2.0f), 0.001f)

        // Test step zoom (-)
        assertEquals(2.0f, dispatcher.calculateNewZoom("-", 3.0f), 0.001f)
        assertEquals(1.5f, dispatcher.calculateNewZoom("-", 2.0f), 0.001f)
        assertEquals(1.0f, dispatcher.calculateNewZoom("-", 1.5f), 0.001f)

        // Test zoom region keeps center within bounds
        val initialRegion = Rect(0, 200, 1080, 550) // center: (540, 375)
        val region15x = dispatcher.calculateZoomRegion(1.5f, initialRegion, 1080, 2400)
        assertEquals(720, region15x.width())
        assertEquals(540, (region15x.left + region15x.right) / 2)

        val region2x = dispatcher.calculateZoomRegion(2.0f, initialRegion, 1080, 2400)
        assertEquals(540, region2x.width())
        assertEquals(540, (region2x.left + region2x.right) / 2)
    }

    @Test
    fun testScrollSpeedCalculation() {
        val config = RemoteConfig()
        val dispatcher = CommandDispatcher(config, { 1080 }, { 2400 })

        // Test absolute speed presets
        assertEquals(0.5f, dispatcher.calculateNewScrollSpeed("0.5", 1.0f), 0.001f)
        assertEquals(1.0f, dispatcher.calculateNewScrollSpeed("1.0", 0.5f), 0.001f)
        assertEquals(1.5f, dispatcher.calculateNewScrollSpeed("1.5", 1.0f), 0.001f)
        assertEquals(2.0f, dispatcher.calculateNewScrollSpeed("2.0", 1.0f), 0.001f)

        // Test step speed (+)
        assertEquals(1.0f, dispatcher.calculateNewScrollSpeed("+", 0.5f), 0.001f)
        assertEquals(1.5f, dispatcher.calculateNewScrollSpeed("+", 1.0f), 0.001f)
        assertEquals(2.0f, dispatcher.calculateNewScrollSpeed("+", 1.5f), 0.001f)

        // Test step speed (-)
        assertEquals(1.5f, dispatcher.calculateNewScrollSpeed("-", 2.0f), 0.001f)
        assertEquals(1.0f, dispatcher.calculateNewScrollSpeed("-", 1.5f), 0.001f)
        assertEquals(0.5f, dispatcher.calculateNewScrollSpeed("-", 1.0f), 0.001f)
    }
}
