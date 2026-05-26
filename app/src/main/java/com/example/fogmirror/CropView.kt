package com.example.fogmirror

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class CropView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var sourceBitmap: Bitmap? = null
    private var aspectX: Float = 1f
    private var aspectY: Float = 1f

    private val cropRect = RectF()
    private val imageMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint().apply {
        color = Color.BLACK
        alpha = 180
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            imageMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    })

    fun setBitmap(bitmap: Bitmap, initialPortrait: Boolean) {
        sourceBitmap = bitmap
        setOrientation(initialPortrait)
        resetImageMatrix()
    }

    fun setOrientation(portrait: Boolean) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        
        if (portrait) {
            aspectX = Math.min(w, h)
            aspectY = Math.max(w, h)
        } else {
            aspectX = Math.max(w, h)
            aspectY = Math.min(w, h)
        }
        updateCropRect()
        invalidate()
    }

    private fun resetImageMatrix() {
        val bitmap = sourceBitmap ?: return
        imageMatrix.reset()
        val scale = Math.max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        imageMatrix.setScale(scale, scale)
        val dx = (width - bitmap.width * scale) / 2f
        val dy = (height - bitmap.height * scale) / 2f
        imageMatrix.postTranslate(dx, dy)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCropRect()
        if (oldw == 0) resetImageMatrix()
    }

    private fun updateCropRect() {
        if (width == 0 || height == 0) return
        val targetAspect = aspectX / aspectY
        var cropW: Float
        var cropH: Float

        if (width.toFloat() / height > targetAspect) {
            cropH = height * 0.9f
            cropW = cropH * targetAspect
        } else {
            cropW = width * 0.9f
            cropH = cropW / targetAspect
        }
        cropRect.set((width - cropW) / 2f, (height - cropH) / 2f, (width + cropW) / 2f, (height + cropH) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sourceBitmap ?: return

        canvas.drawBitmap(bitmap, imageMatrix, paint)

        // Draw dark overlay around crop rect
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        
        val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        canvas.drawRect(cropRect, clearPaint)
        canvas.restoreToCount(layer)

        // Draw crop border
        canvas.drawRect(cropRect, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        
        if (scaleDetector.isInProgress) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    imageMatrix.postTranslate(dx, dy)
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun getCroppedBitmap(): Bitmap? {
        val bitmap = sourceBitmap ?: return null
        
        // Final cropped bitmap size based on cropRect size but mapped back to original image
        val inverse = Matrix()
        imageMatrix.invert(inverse)
        
        val bitmapRect = RectF()
        inverse.mapRect(bitmapRect, cropRect)
        
        val left = bitmapRect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bitmapRect.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = bitmapRect.width().toInt().coerceAtMost(bitmap.width - left)
        val height = bitmapRect.height().toInt().coerceAtMost(bitmap.height - top)
        
        if (width <= 0 || height <= 0) return null
        
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }
}
