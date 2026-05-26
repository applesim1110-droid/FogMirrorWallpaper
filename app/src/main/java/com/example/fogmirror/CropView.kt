package com.example.fogmirror

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class CropView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var sourceBitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null
    
    private var aspectX: Float = 1f
    private var aspectY: Float = 1f

    private val cropRect = RectF()
    private val previewMatrix = Matrix()
    private val imageBounds = RectF() // The bounds of the image on screen
    
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
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var lastX = 0f
    private var lastY = 0f
    private var touchMode = TOUCH_NONE

    private val handleSize = 40f
    private val touchTolerance = 60f

    companion object {
        private const val TOUCH_NONE = 0
        private const val TOUCH_DRAG = 1
        private const val TOUCH_RESIZE_BR = 2
    }

    fun setBitmap(bitmap: Bitmap, initialPortrait: Boolean) {
        sourceBitmap = bitmap
        
        // Further reduce preview size to 800px to ensure zero lag on any tablet
        val maxPreviewDim = 800
        val scale = minOf(maxPreviewDim.toFloat() / bitmap.width, maxPreviewDim.toFloat() / bitmap.height)
        
        previewBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap, 
                (bitmap.width * scale).toInt(), 
                (bitmap.height * scale).toInt(), 
                true
            )
        } else {
            bitmap
        }

        setOrientation(initialPortrait)
    }

    fun setOrientation(portrait: Boolean) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        
        if (portrait) {
            aspectX = minOf(w, h)
            aspectY = maxOf(w, h)
        } else {
            aspectX = maxOf(w, h)
            aspectY = minOf(w, h)
        }
        resetCropRect()
        invalidate()
    }

    private fun resetCropRect() {
        if (width == 0 || height == 0 || imageBounds.isEmpty) return
        
        val targetAspect = aspectX / aspectY
        var cropW: Float
        var cropH: Float

        // Try to fit inside imageBounds initially
        if (imageBounds.width() / imageBounds.height() > targetAspect) {
            cropH = imageBounds.height() * 0.8f
            cropW = cropH * targetAspect
        } else {
            cropW = imageBounds.width() * 0.8f
            cropH = cropW / targetAspect
        }
        
        cropRect.set(
            imageBounds.centerX() - cropW / 2f,
            imageBounds.centerY() - cropH / 2f,
            imageBounds.centerX() + cropW / 2f,
            imageBounds.centerY() + cropH / 2f
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bitmap = previewBitmap ?: return

        val scale = minOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val dx = (w - bitmap.width * scale) / 2f
        val dy = (h - bitmap.height * scale) / 2f
        
        previewMatrix.setScale(scale, scale)
        previewMatrix.postTranslate(dx, dy)
        
        // Pre-calculate image bounds on screen
        imageBounds.set(dx, dy, dx + bitmap.width * scale, dy + bitmap.height * scale)
        
        resetCropRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = previewBitmap ?: return

        canvas.drawBitmap(bitmap, previewMatrix, paint)

        val w = width.toFloat()
        val h = height.toFloat()
        
        // Fast 4-rect overlay
        canvas.drawRect(0f, 0f, w, cropRect.top, overlayPaint)
        canvas.drawRect(0f, cropRect.bottom, w, h, overlayPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, overlayPaint)
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, overlayPaint)

        canvas.drawRect(cropRect, borderPaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleSize / 2f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                
                if (abs(event.x - cropRect.right) < touchTolerance && abs(event.y - cropRect.bottom) < touchTolerance) {
                    touchMode = TOUCH_RESIZE_BR
                } else if (cropRect.contains(event.x, event.y)) {
                    touchMode = TOUCH_DRAG
                } else {
                    touchMode = TOUCH_NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                if (touchMode == TOUCH_DRAG) {
                    val newRect = RectF(cropRect)
                    newRect.offset(dx, dy)
                    
                    // Strictly constrain to image bounds
                    if (newRect.left < imageBounds.left) newRect.offset(imageBounds.left - newRect.left, 0f)
                    if (newRect.top < imageBounds.top) newRect.offset(0f, imageBounds.top - newRect.top)
                    if (newRect.right > imageBounds.right) newRect.offset(imageBounds.right - newRect.right, 0f)
                    if (newRect.bottom > imageBounds.bottom) newRect.offset(0f, imageBounds.bottom - newRect.bottom)
                    
                    cropRect.set(newRect)
                } else if (touchMode == TOUCH_RESIZE_BR) {
                    val targetAspect = aspectX / aspectY
                    
                    // Calculate max possible width based on image boundaries
                    val maxWidth = (imageBounds.right - cropRect.left).coerceAtMost((imageBounds.bottom - cropRect.top) * targetAspect)
                    
                    var newWidth = (cropRect.width() + dx).coerceIn(100f, maxWidth)
                    var newHeight = newWidth / targetAspect
                    
                    cropRect.set(cropRect.left, cropRect.top, cropRect.left + newWidth, cropRect.top + newHeight)
                }

                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TOUCH_NONE
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
        val original = sourceBitmap ?: return null
        
        // Map cropRect (view coords) back to original bitmap coordinates
        val fullMatrix = Matrix()
        val scale = minOf(width.toFloat() / original.width, height.toFloat() / original.height)
        val dx = (width - original.width * scale) / 2f
        val dy = (height - original.height * scale) / 2f
        fullMatrix.setScale(scale, scale)
        fullMatrix.postTranslate(dx, dy)

        val inverse = Matrix()
        fullMatrix.invert(inverse)
        
        val bitmapRect = RectF()
        inverse.mapRect(bitmapRect, cropRect)
        
        val left = bitmapRect.left.toInt().coerceIn(0, original.width - 1)
        val top = bitmapRect.top.toInt().coerceIn(0, original.height - 1)
        val w = bitmapRect.width().toInt().coerceAtMost(original.width - left)
        val h = bitmapRect.height().toInt().coerceAtMost(original.height - top)
        
        if (w <= 0 || h <= 0) return null
        
        return Bitmap.createBitmap(original, left, top, w, h)
    }
}
