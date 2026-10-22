package com.hetcuu.hcview

import com.hetcuu.hcview.protocol.FramePacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePackerTest {

    @Test
    fun testFrameDimensions() {
        assertEquals(191, FramePacker.W)
        assertEquals(62, FramePacker.H)
        assertEquals(1488, FramePacker.FRAME_SIZE_1BIT)
        assertEquals(2976, FramePacker.FRAME_SIZE_2BIT)
    }

    @Test
    fun testPureWhite1Bit() {
        // White = 0xFFFFFFFF, luma = 255
        val whitePixels = IntArray(FramePacker.W * FramePacker.H) { 0xFFFFFFFF.toInt() }
        val packed = FramePacker.packFrame1Bit(whitePixels, invert = false)

        assertEquals(1488, packed.size)
        // All pixels white -> all bits 0
        for (b in packed) {
            assertEquals(0x00.toByte(), b)
        }
    }

    @Test
    fun testPureBlack1Bit() {
        // Black = 0xFF000000, luma = 0
        val blackPixels = IntArray(FramePacker.W * FramePacker.H) { 0xFF000000.toInt() }
        val packed = FramePacker.packFrame1Bit(blackPixels, invert = false)

        assertEquals(1488, packed.size)
        // Rows: 23 bytes of 0xFF, followed by 1 byte of 0xFE (column 191 is 0, so bit 0 is 0 -> 0xFE)
        for (y in 0 until FramePacker.H) {
            val rowOffset = y * 24
            for (xByte in 0 until 23) {
                assertEquals("Row $y byte $xByte", 0xFF.toByte(), packed[rowOffset + xByte])
            }
            assertEquals("Row $y last byte", 0xFE.toByte(), packed[rowOffset + 23])
        }
    }

    @Test
    fun testPureWhite2Bit() {
        val whitePixels = IntArray(FramePacker.W * FramePacker.H) { 0xFFFFFFFF.toInt() }
        val packed = FramePacker.packFrame2Bit(whitePixels, invert = false)

        assertEquals(2976, packed.size)
        // Invert = false: white level = 3 -> 3 - 3 = 0 -> all 0x00
        for (b in packed) {
            assertEquals(0x00.toByte(), b)
        }
    }

    @Test
    fun testPureBlack2Bit() {
        val blackPixels = IntArray(FramePacker.W * FramePacker.H) { 0xFF000000.toInt() }
        val packed = FramePacker.packFrame2Bit(blackPixels, invert = false)

        assertEquals(2976, packed.size)
        // Rows: 47 bytes of 0xFF, followed by 1 byte of 0xFC (column 191 is 00 -> 0xFC)
        for (y in 0 until FramePacker.H) {
            val rowOffset = y * 48
            for (xByte in 0 until 47) {
                assertEquals("Row $y byte $xByte", 0xFF.toByte(), packed[rowOffset + xByte])
            }
            assertEquals("Row $y last byte", 0xFC.toByte(), packed[rowOffset + 47])
        }
    }

    @Test
    fun testInvertLogic() {
        val whitePixels = IntArray(FramePacker.W * FramePacker.H) { 0xFFFFFFFF.toInt() }
        val packed1BitInverted = FramePacker.packFrame1Bit(whitePixels, invert = true)

        // White with invert=true should look like black
        assertEquals(1488, packed1BitInverted.size)
        assertEquals(0xFF.toByte(), packed1BitInverted[0])
        assertEquals(0xFE.toByte(), packed1BitInverted[23])

        val packed2BitInverted = FramePacker.packFrame2Bit(whitePixels, invert = true)
        assertEquals(2976, packed2BitInverted.size)
        assertEquals(0xFF.toByte(), packed2BitInverted[0])
        assertEquals(0xFC.toByte(), packed2BitInverted[47])
    }
}
