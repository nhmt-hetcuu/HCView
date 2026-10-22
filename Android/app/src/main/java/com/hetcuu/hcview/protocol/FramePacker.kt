package com.hetcuu.hcview.protocol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object FramePacker {
    const val W = 191
    const val H = 62
    const val FRAME_SIZE_1BIT = 24 * H // 1488 bytes
    const val FRAME_SIZE_2BIT = 48 * H // 2976 bytes

    private val filterPaint by lazy { Paint(Paint.FILTER_BITMAP_FLAG) }
    private val destRect by lazy { Rect(0, 0, W, H) }

    /**
     * Cắt vùng [region] từ [screen] và co về 191x62 (ARGB_8888).
     */
    fun cropScale(screen: Bitmap, region: Rect, outBitmap: Bitmap, outPixels: IntArray) {
        val canvas = Canvas(outBitmap)
        canvas.drawBitmap(screen, region, destRect, filterPaint)
        outBitmap.getPixels(outPixels, 0, W, 0, 0, W, H)
    }

    /**
     * Độ sáng 0..255 theo công thức qGray (khớp đặc tả).
     */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 11 + g * 16 + b * 5) shr 5
    }

    /**
     * Đóng gói khung 1-bit: đúng 1488 byte = 24 byte/hàng × 62 hàng.
     * Bit 7 là điểm trái nhất. Cột 191 luôn đệm 0.
     */
    fun packFrame1Bit(px: IntArray, invert: Boolean = false): ByteArray {
        val out = ByteArray(FRAME_SIZE_1BIT)
        for (y in 0 until H) {
            val rowOffset = y * 24
            val pxRowOffset = y * W
            for (x in 0 until W) {
                val dark = luma(px[pxRowOffset + x]) < 128
                if (dark != invert) {
                    val i = rowOffset + (x shr 3)
                    out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }

    /**
     * Đóng gói khung 2-bit (4 xám): đúng 2976 byte = 48 byte/hàng × 62 hàng.
     * Bit 7..6 là điểm trái nhất, tiếp theo 5..4, 3..2, 1..0.
     * Cột 191 luôn đệm 00.
     */
    fun packFrame2Bit(px: IntArray, invert: Boolean = false): ByteArray {
        val out = ByteArray(FRAME_SIZE_2BIT)
        for (y in 0 until H) {
            val rowOffset = y * 48
            val pxRowOffset = y * W
            for (x in 0 until W) {
                val level = luma(px[pxRowOffset + x]) shr 6 // 0 tối .. 3 sáng
                val v = if (invert) level else 3 - level
                if (v != 0) {
                    val i = rowOffset + (x shr 2)
                    out[i] = (out[i].toInt() or (v shl (6 - 2 * (x and 3)))).toByte()
                }
            }
        }
        return out
    }

    val PALETTE_CLASSIC = intArrayOf(
        0xFFA4B99E.toInt(), // 0: White / Lightest (LCD background)
        0xFF728E6A.toInt(), // 1: Light gray
        0xFF3C5435.toInt(), // 2: Dark gray
        0xFF101A0E.toInt()  // 3: Darkest / Black
    )

    val PALETTE_MONO = intArrayOf(
        0xFFF6F8FA.toInt(), // 0: Clean bright
        0xFFA0AEC0.toInt(), // 1: Light gray
        0xFF4A5568.toInt(), // 2: Dark gray
        0xFF0A0F1D.toInt()  // 3: Solid black
    )

    val PALETTE_CYBER = intArrayOf(
        0xFF081009.toInt(), // 0: Deep background
        0xFF0E3D1C.toInt(), // 1: Dim neon
        0xFF009933.toInt(), // 2: Medium neon
        0xFF00FF66.toInt()  // 3: Electric neon green
    )

    val PALETTE_AMBER = intArrayOf(
        0xFF181002.toInt(), // 0: Dark amber background
        0xFF593900.toInt(), // 1: Dim amber
        0xFFB37200.toInt(), // 2: Medium amber
        0xFFFFAE00.toInt()  // 3: Glowing amber
    )

    val LCD_COLORS = PALETTE_CLASSIC

    /**
     * Tăng độ tương phản & làm sắc nét mảng luma để chống mờ chữ khi downscale.
     */
    fun processLumaBuffer(
        px: IntArray,
        contrastLevel: Int = 1, // 0 = Off, 1 = Balanced, 2 = High
        applySharpen: Boolean = true
    ): IntArray {
        val count = W * H
        val lumas = IntArray(count)
        for (i in 0 until count) {
            lumas[i] = luma(px[i])
        }

        if (contrastLevel == 0 && !applySharpen) {
            return lumas
        }

        // 1. Áp dụng Unsharp Mask Sharpening 3x3 để cạnh chữ sắc lẹm
        val sharpened = if (applySharpen) {
            val out = IntArray(count)
            for (y in 0 until H) {
                val row = y * W
                for (x in 0 until W) {
                    val idx = row + x
                    if (x == 0 || x == W - 1 || y == 0 || y == H - 1) {
                        out[idx] = lumas[idx]
                    } else {
                        val c = lumas[idx]
                        val neighbors = lumas[idx - 1] + lumas[idx + 1] + lumas[idx - W] + lumas[idx + W]
                        out[idx] = (c * 5 - neighbors).coerceIn(0, 255)
                    }
                }
            }
            out
        } else {
            lumas
        }

        // 2. Kéo dãn tương phản phi tuyến tính (S-Curve & Dynamic Thresholding)
        if (contrastLevel > 0) {
            val isHigh = contrastLevel >= 2
            for (i in 0 until count) {
                val v = sharpened[i]
                sharpened[i] = if (isHigh) {
                    when {
                        v < 90 -> 0
                        v < 140 -> 60
                        v < 185 -> 130
                        else -> 255
                    }
                } else {
                    when {
                        v < 75 -> (v * 0.6f).toInt()
                        v < 165 -> (v * 0.85f).toInt()
                        v > 210 -> (210 + (v - 210) * 1.5f).toInt().coerceAtMost(255)
                        else -> v
                    }
                }
            }
        }

        return sharpened
    }

    /**
     * Đóng gói khung 1-bit: đúng 1488 byte = 24 byte/hàng × 62 hàng.
     */
    fun packFrame1Bit(
        px: IntArray,
        invert: Boolean = false,
        contrastLevel: Int = 0,
        sharpen: Boolean = false
    ): ByteArray {
        val lumaBuf = if (contrastLevel > 0 || sharpen) {
            processLumaBuffer(px, contrastLevel, sharpen)
        } else null

        val out = ByteArray(FRAME_SIZE_1BIT)
        for (y in 0 until H) {
            val rowOffset = y * 24
            val pxRowOffset = y * W
            for (x in 0 until W) {
                val l = lumaBuf?.get(pxRowOffset + x) ?: luma(px[pxRowOffset + x])
                val dark = l < 128
                if (dark != invert) {
                    val i = rowOffset + (x shr 3)
                    out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }

    /**
     * Đóng gói khung 2-bit (4 xám): đúng 2976 byte = 48 byte/hàng × 62 hàng.
     * Hỗ trợ chế độ làm nét chữ hoặc Floyd-Steinberg dithering chuyển sắc mịn.
     */
    fun packFrame2Bit(
        px: IntArray,
        invert: Boolean = false,
        contrastLevel: Int = 0,
        sharpen: Boolean = false,
        useDither: Boolean = false
    ): ByteArray {
        val out = ByteArray(FRAME_SIZE_2BIT)

        if (useDither) {
            // Floyd-Steinberg error diffusion trên 4 mức xám (0..3)
            val ditherBuf = FloatArray(W * H)
            for (i in 0 until W * H) {
                ditherBuf[i] = luma(px[i]).toFloat()
            }

            for (y in 0 until H) {
                val rowOffset = y * 48
                val pxRowOffset = y * W
                for (x in 0 until W) {
                    val oldVal = ditherBuf[pxRowOffset + x].coerceIn(0f, 255f)
                    val level = (oldVal / 64f + 0.5f).toInt().coerceIn(0, 3)
                    val newVal = level * 85f
                    val err = oldVal - newVal

                    // Phân tán lỗi cho lân cận
                    if (x + 1 < W) ditherBuf[pxRowOffset + x + 1] += err * (7f / 16f)
                    if (y + 1 < H) {
                        val nextRow = (y + 1) * W
                        if (x - 1 >= 0) ditherBuf[nextRow + x - 1] += err * (3f / 16f)
                        ditherBuf[nextRow + x] += err * (5f / 16f)
                        if (x + 1 < W) ditherBuf[nextRow + x + 1] += err * (1f / 16f)
                    }

                    val v = if (invert) level else 3 - level
                    if (v != 0) {
                        val i = rowOffset + (x shr 2)
                        out[i] = (out[i].toInt() or (v shl (6 - 2 * (x and 3)))).toByte()
                    }
                }
            }
            return out
        }

        val lumaBuf = if (contrastLevel > 0 || sharpen) {
            processLumaBuffer(px, contrastLevel, sharpen)
        } else null

        for (y in 0 until H) {
            val rowOffset = y * 48
            val pxRowOffset = y * W
            for (x in 0 until W) {
                val l = lumaBuf?.get(pxRowOffset + x) ?: luma(px[pxRowOffset + x])
                val level = (l shr 6).coerceIn(0, 3) // 0 tối .. 3 sáng
                val v = if (invert) level else 3 - level
                if (v != 0) {
                    val i = rowOffset + (x shr 2)
                    out[i] = (out[i].toInt() or (v shl (6 - 2 * (x and 3)))).toByte()
                }
            }
        }
        return out
    }

    /**
     * Giải mã khung nhị phân (1488 hoặc 2976 byte) thành Bitmap 191x62 để hiển thị preview.
     * Hỗ trợ chọn palette màu tuỳ chỉnh.
     */
    fun unpackToBitmap(
        frame: ByteArray,
        outBitmap: Bitmap,
        palette: IntArray = PALETTE_CLASSIC
    ) {
        val px = IntArray(W * H)
        if (frame.size == FRAME_SIZE_1BIT) {
            for (y in 0 until H) {
                val rowOffset = y * 24
                val pxRowOffset = y * W
                for (x in 0 until W) {
                    val byteVal = frame[rowOffset + (x shr 3)].toInt() and 0xFF
                    val bit = (byteVal ushr (7 - (x and 7))) and 1
                    px[pxRowOffset + x] = if (bit == 1) palette[3] else palette[0]
                }
            }
        } else if (frame.size == FRAME_SIZE_2BIT) {
            for (y in 0 until H) {
                val rowOffset = y * 48
                val pxRowOffset = y * W
                for (x in 0 until W) {
                    val byteVal = frame[rowOffset + (x shr 2)].toInt() and 0xFF
                    val level = (byteVal ushr (6 - 2 * (x and 3))) and 3
                    px[pxRowOffset + x] = palette[level]
                }
            }
        }
        outBitmap.setPixels(px, 0, W, 0, 0, W, H)
    }
}
