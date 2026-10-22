package com.hetcuu.hcview.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.hetcuu.hcview.protocol.FramePacker

/**
 * Custom View mô phỏng màn hình LCD ma trận điểm 191 × 62 của thiết bị cầm tay.
 * - KHÔNG BỊ MỜ: Sử dụng thuật toán Nearest-Neighbor (isFilterBitmap = false) thay vì Bilinear Blur của ImageView.
 * - Lưới điểm ảnh (Dot Matrix Grid): Mô phỏng từng điểm ảnh tinh thể lỏng vật lý sắc nét.
 * - Hỗ trợ 4 Palette màu sắc: LCD Cổ Điển, OLED Đen Trắng, Cyber Neon, Hổ Phách.
 */
class LcdMatrixView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint().apply {
        isFilterBitmap = false // TRIỆT TIÊU TOÀN BỘ HIỆN TƯỢNG MỜ
        isDither = false
        isAntiAlias = false
    }

    private val pixelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private var currentBitmap: Bitmap? = null
    private val pixelArray = IntArray(FramePacker.W * FramePacker.H)
    private var hasPixels = false

    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var currentPalette: IntArray = FramePacker.PALETTE_CLASSIC
        set(value) {
            field = value
            bgPaint.color = value[0]
            invalidate()
        }

    init {
        bgPaint.color = currentPalette[0]
    }

    fun updateFrame(bitmap: Bitmap?) {
        if (bitmap == null) return
        this.currentBitmap = bitmap
        try {
            bitmap.getPixels(pixelArray, 0, FramePacker.W, 0, 0, FramePacker.W, FramePacker.H)
            hasPixels = true
        } catch (e: Exception) {
            hasPixels = false
        }
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Duy trì tỉ lệ khung hình chuẩn 191:62 (~3.08:1)
        val targetHeight = (width * FramePacker.H / FramePacker.W).coerceAtLeast(100)
        val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
        val finalHeight = if (heightSpecMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            targetHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec).let { if (it > 0) it else targetHeight })
        }
        setMeasuredDimension(width, finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        // 1. Vẽ nền màn hình LCD theo màu palette mức 0
        bgPaint.color = currentPalette[0]
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val bmp = currentBitmap
        if (bmp == null || !hasPixels) {
            return
        }

        // Tính toán kích thước hiển thị vừa vặn giữ tỉ lệ 191:62
        val targetW: Float
        val targetH: Float
        val offsetX: Float
        val offsetY: Float

        val aspectScreen = w / h
        val aspectLcd = FramePacker.W.toFloat() / FramePacker.H.toFloat()

        if (aspectScreen > aspectLcd) {
            targetH = h
            targetW = h * aspectLcd
            offsetX = (w - targetW) / 2f
            offsetY = 0f
        } else {
            targetW = w
            targetH = w / aspectLcd
            offsetX = 0f
            offsetY = (h - targetH) / 2f
        }

        if (showGrid && targetW >= FramePacker.W * 2) {
            // Vẽ từng điểm ảnh LCD ma trận với khe hở vi điểm cực kỳ sắc nét
            val dotW = targetW / FramePacker.W
            val dotH = targetH / FramePacker.H
            val gapX = (dotW * 0.12f).coerceIn(0.5f, 1.5f)
            val gapY = (dotH * 0.12f).coerceIn(0.5f, 1.5f)
            val pw = dotW - gapX
            val ph = dotH - gapY

            val bgColor = currentPalette[0]

            for (y in 0 until FramePacker.H) {
                val py = offsetY + y * dotH
                val rowOffset = y * FramePacker.W
                for (x in 0 until FramePacker.W) {
                    val color = pixelArray[rowOffset + x]
                    // Nếu là màu nền thì đã có background, chỉ vẽ nếu khác nền để tối ưu hiệu năng
                    if (color != bgColor) {
                        pixelPaint.color = color
                        val px = offsetX + x * dotW
                        canvas.drawRect(px, py, px + pw, py + ph, pixelPaint)
                    }
                }
            }
        } else {
            // Vẽ toàn bộ bitmap với Nearest Neighbor scaling sắc cạnh
            val srcRect = Rect(0, 0, FramePacker.W, FramePacker.H)
            val dstRect = RectF(offsetX, offsetY, offsetX + targetW, offsetY + targetH)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        }
    }
}
