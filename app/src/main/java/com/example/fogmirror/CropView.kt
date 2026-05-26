package com.example.fogmirror

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CropView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var sourceBitmap: Bitmap? = null
    private var aspectX: Float = 1f
    private var aspectY: Float = 1f

    private val cropRect = RectF()
    private val drawMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint().apply {
        color = Color.BLACK
        alpha = 160
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var lastX = 0f
    private var lastY = 0f

    fun setBitmap(bitmap: Bitmap, ax: Float, ay: Float) {
        sourceBitmap = bitmap
        aspectX = ax
        aspectY = ay
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bitmap = sourceBitmap ?: return

        // Calculate matrix to fit bitmap inside view
        val scale = Math.min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val dx = (w - bitmap.width * scale) / 2f
        val dy = (h - bitmap.height * scale) / 2f
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)

        // Initialize crop rect centered with fixed aspect ratio
        val targetAspect = aspectX / aspectY
        var cropW: Float
        var cropH: Float

        if (w.toFloat() / h > targetAspect) {
            cropH = h * 0.8f
            cropW = cropH * targetAspect
        } else {
            cropW = w * 0.8f
            cropH = cropW / targetAspect
        }

        cropRect.set((w - cropW) / 2f, (h - cropH) / 2f, (w + cropW) / 2f, (h + cropH) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sourceBitmap ?: return

        canvas.drawBitmap(bitmap, drawMatrix, paint)

        // Draw dark overlay
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        
        // Clear crop area
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawRect(cropRect, paint)
        paint.xfermode = null
        canvas.restoreToCount(layer)

        // Draw border
        canvas.drawRect(cropRect, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                cropRect.offset(dx, dy)
                
                // Keep inside view
                if (cropRect.left < 0) cropRect.offset(-cropRect.left, 0f)
                if (cropRect.top < 0) cropRect.offset(0f, -cropRect.top)
                if (cropRect.right > width) cropRect.offset(width - cropRect.right, 0f)
                if (cropRect.bottom > height) cropRect.offset(0f, height - cropRect.bottom)

                lastX = event.x
                lastY = event.y
                invalidate()
            }
        }
        return true
    }

    fun getCroppedBitmap(): Bitmap? {
        val bitmap = sourceBitmap ?: return null
        
        // Inverse matrix to find crop rect in bitmap coordinates
        val inverse = Matrix()
        drawMatrix.invert(inverse)
        
        val bitmapRect = RectF()
        inverse.mapRect(bitmapRect, cropRect)
        
        val left = bitmapRect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bitmapRect.top.toInt().coerceIn(0, bitmap.height - 1)
        var width = bitmapRect.width().toInt().coerceAtMost(bitmap.width - left)
        var height = bitmapRect.height().toInt().coerceAtMost(bitmap.height - top)
        
        if (width <= 0 || height <= 0) return null
        
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }
}
