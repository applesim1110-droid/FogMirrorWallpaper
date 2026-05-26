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
    private val drawMatrix = Matrix()
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
    private var isDraggingCrop = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val targetAspect = aspectX / aspectY
            
            val newWidth = cropRect.width() * scaleFactor
            val newHeight = newWidth / targetAspect
            
            // Limit minimum size
            if (newWidth < 100f || newHeight < 100f) return true

            val centerX = cropRect.centerX()
            val centerY = cropRect.centerY()
            
            val newRect = RectF(
                centerX - newWidth / 2f,
                centerY - newHeight / 2f,
                centerX + newWidth / 2f,
                centerY + newHeight / 2f
            )

            // Keep within view bounds
            if (newRect.left >= 0 && newRect.top >= 0 && newRect.right <= width && newRect.bottom <= height) {
                cropRect.set(newRect)
            }
            
            invalidate()
            return true
        }
    })

    fun setBitmap(bitmap: Bitmap, initialPortrait: Boolean) {
        sourceBitmap = bitmap
        setOrientation(initialPortrait)
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
        resetCropRect()
        invalidate()
    }

    private fun resetCropRect() {
        if (width == 0 || height == 0) return
        val targetAspect = aspectX / aspectY
        var cropW: Float
        var cropH: Float

        if (width.toFloat() / height > targetAspect) {
            cropH = height * 0.8f
            cropW = cropH * targetAspect
        } else {
            cropW = width * 0.8f
            cropH = cropW / targetAspect
        }
        cropRect.set((width - cropW) / 2f, (height - cropH) / 2f, (width + cropW) / 2f, (height + cropH) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bitmap = sourceBitmap ?: return

        // Matrix to fit bitmap inside view (static)
        val scale = Math.min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val dx = (w - bitmap.width * scale) / 2f
        val dy = (h - bitmap.height * scale) / 2f
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        
        resetCropRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sourceBitmap ?: return

        canvas.drawBitmap(bitmap, drawMatrix, paint)

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
                if (cropRect.contains(event.x, event.y)) {
                    lastX = event.x
                    lastY = event.y
                    isDraggingCrop = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingCrop) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    
                    val newRect = RectF(cropRect)
                    newRect.offset(dx, dy)
                    
                    // Constrain to view bounds
                    if (newRect.left >= 0 && newRect.top >= 0 && newRect.right <= width && newRect.bottom <= height) {
                        cropRect.set(newRect)
                    }
                    
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingCrop = false
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
        
        val inverse = Matrix()
        drawMatrix.invert(inverse)
        
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
