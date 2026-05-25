package com.example.fogmirror

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class FogMirrorWallpaperService : WallpaperService() {
    private companion object {
        private const val INITIAL_FOG_ALPHA = 178
    }

    override fun onCreateEngine(): Engine = FogMirrorEngine()

    private inner class FogMirrorEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val random = Random(System.nanoTime())
        private val wetStrokes = mutableListOf<WetStroke>()
        private val drips = mutableListOf<Drip>()

        private var visible = false
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var lastFrameNanos = 0L
        private var fogReturnAccumulator = 0f
        private var secondsSinceTouch = 999f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var hasLastTouch = false

        private lateinit var clearBitmap: Bitmap
        private lateinit var fogBitmap: Bitmap
        private lateinit var condensationNoise: Bitmap
        private lateinit var fogMask: Bitmap
        private lateinit var fogMaskCanvas: Canvas

        private val imageMatrix = Matrix()
        private val frameRunnable = object : Runnable {
            override fun run() {
                drawFrame()
            }
        }

        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        private val wipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 96f
            maskFilter = BlurMaskFilter(38f, BlurMaskFilter.Blur.NORMAL)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val wipeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 56f
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val softReturnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(8, 255, 255, 255)
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(52f, BlurMaskFilter.Blur.NORMAL)
        }
        private val aestheticGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val wetTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        }
        private val wetHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(108, 255, 255, 255)
        }
        private val dripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        }
        private val dripBeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(135, 255, 255, 255)
            maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            clearBitmap = BitmapFactory.decodeResource(resources, R.drawable.mirror_clear)
            fogBitmap = BitmapFactory.decodeResource(resources, R.drawable.fog_blur)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                lastFrameNanos = System.nanoTime()
                drawFrame()
            } else {
                handler.removeCallbacks(frameRunnable)
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            surfaceWidth = max(1, width)
            surfaceHeight = max(1, height)
            createSurfaceBuffers()
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frameRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frameRunnable)
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    secondsSinceTouch = 0f
                    hasLastTouch = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                    wipeSegment(event.x, event.y, event.x, event.y, 0.65f)
                }

                MotionEvent.ACTION_MOVE -> {
                    secondsSinceTouch = 0f
                    for (i in 0 until event.historySize) {
                        val x = event.getHistoricalX(i)
                        val y = event.getHistoricalY(i)
                        wipeFromLastPoint(x, y)
                    }
                    wipeFromLastPoint(event.x, event.y)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hasLastTouch = false
                }
            }
            super.onTouchEvent(event)
        }

        private fun createSurfaceBuffers() {
            // The mask stores where fog is still present. Transparent pixels are wiped glass.
            fogMask = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
            fogMaskCanvas = Canvas(fogMask)
            fogMaskCanvas.drawColor(Color.argb(INITIAL_FOG_ALPHA, 255, 255, 255))
            condensationNoise = createCondensationNoise(surfaceWidth, surfaceHeight)
            configureCenterCropMatrix()
        }

        private fun configureCenterCropMatrix() {
            val scale = max(
                surfaceWidth / clearBitmap.width.toFloat(),
                surfaceHeight / clearBitmap.height.toFloat()
            )
            val dx = (surfaceWidth - clearBitmap.width * scale) * 0.5f
            val dy = (surfaceHeight - clearBitmap.height * scale) * 0.5f
            imageMatrix.reset()
            imageMatrix.postScale(scale, scale)
            imageMatrix.postTranslate(dx, dy)
        }

        private fun drawFrame() {
            if (!visible) return
            if (!::fogMask.isInitialized || fogMask.width != surfaceWidth || fogMask.height != surfaceHeight) {
                createSurfaceBuffers()
            }

            val now = System.nanoTime()
            val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = now

            secondsSinceTouch += dt
            updateWetGlass(dt)
            returnFogNonUniformly(dt)

            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (_: Exception) {
                null
            } ?: run {
                scheduleNextFrame()
                return
            }

            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(clearBitmap, imageMatrix, clearPaint)
                drawFogLayer(canvas)
                drawSoftAestheticGlow(canvas)
                drawWetGlass(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }

            scheduleNextFrame()
        }

        private fun scheduleNextFrame() {
            handler.removeCallbacks(frameRunnable)
            if (visible) {
                handler.postDelayed(frameRunnable, 16L)
            }
        }

        private fun drawFogLayer(canvas: Canvas) {
            // Draw the blurred photo and condensation texture, then clip them through the live fog mask.
            val layer = canvas.saveLayer(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), null)
            canvas.drawBitmap(fogBitmap, imageMatrix, fogPaint)
            canvas.drawBitmap(condensationNoise, 0f, 0f, null)
            canvas.drawBitmap(fogMask, 0f, 0f, maskPaint)
            canvas.restoreToCount(layer)
        }

        private fun wipeFromLastPoint(x: Float, y: Float) {
            if (!hasLastTouch) {
                lastTouchX = x
                lastTouchY = y
                hasLastTouch = true
            }
            val distance = hypot(x - lastTouchX, y - lastTouchY)
            wipeSegment(lastTouchX, lastTouchY, x, y, (distance / 115f).coerceIn(0.3f, 1.25f))
            lastTouchX = x
            lastTouchY = y
        }

        private fun wipeSegment(startX: Float, startY: Float, endX: Float, endY: Float, pressure: Float) {
            // A soft outer brush plus a clean inner brush reveals the image without watery artifacts.
            val distance = hypot(endX - startX, endY - startY)
            val steps = max(1, (distance / 18f).toInt())
            var previousX = startX
            var previousY = startY
            wipePaint.strokeWidth = 94f * pressure.coerceIn(0.55f, 1.15f)
            wipeCorePaint.strokeWidth = 52f * pressure.coerceIn(0.55f, 1.15f)

            for (step in 1..steps) {
                val t = step / steps.toFloat()
                val smoothT = t * t * (3f - 2f * t)
                val x = startX + (endX - startX) * smoothT
                val y = startY + (endY - startY) * smoothT
                fogMaskCanvas.drawLine(previousX, previousY, x, y, wipePaint)
                fogMaskCanvas.drawLine(previousX, previousY, x, y, wipeCorePaint)
                wetStrokes += WetStroke(
                    startX = previousX,
                    startY = previousY,
                    endX = x,
                    endY = y,
                    width = random.nextFloat(14f, 24f) * pressure.coerceIn(0.7f, 1.2f)
                )
                if (random.nextFloat() < 0.055f && drips.size < 18) {
                    drips += Drip(
                        x = x + random.nextFloatSigned(11f),
                        y = y,
                        startY = y,
                        length = random.nextFloat(18f, 54f),
                        width = random.nextFloat(5f, 11f),
                        velocity = random.nextFloat(28f, 68f),
                        gravity = random.nextFloat(70f, 125f),
                        lean = random.nextFloatSigned(4f)
                    )
                }
                previousX = x
                previousY = y
            }
        }

        private fun returnFogNonUniformly(dt: Float) {
            // Wait after touch, then restore fog with faint circular patches so clear wipes stay clean.
            if (secondsSinceTouch < 1.35f) return
            fogReturnAccumulator += dt
            if (fogReturnAccumulator < 0.04f) return
            val patchBudget = fogReturnAccumulator
            fogReturnAccumulator = 0f
            val patches = max(1, (patchBudget * 36f).toInt())
            repeat(patches) {
                softReturnPaint.alpha = random.nextInt(3, 8)
                fogMaskCanvas.drawCircle(
                    random.nextFloat(0f, surfaceWidth.toFloat()),
                    random.nextFloat(0f, surfaceHeight.toFloat()),
                    random.nextFloat(90f, 210f),
                    softReturnPaint
                )
            }
        }

        private fun drawSoftAestheticGlow(canvas: Canvas) {
            aestheticGlowPaint.shader = LinearGradient(
                0f,
                0f,
                surfaceWidth.toFloat(),
                surfaceHeight.toFloat(),
                intArrayOf(
                    Color.argb(22, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(28, 185, 215, 225)
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), aestheticGlowPaint)
            aestheticGlowPaint.shader = null
        }

        private fun updateWetGlass(dt: Float) {
            val strokeIterator = wetStrokes.iterator()
            while (strokeIterator.hasNext()) {
                val stroke = strokeIterator.next()
                stroke.age += dt
                if (stroke.age > 7.5f) strokeIterator.remove()
            }

            val dripIterator = drips.iterator()
            while (dripIterator.hasNext()) {
                val drip = dripIterator.next()
                drip.age += dt
                drip.velocity = min(360f, drip.velocity + drip.gravity * dt)
                drip.y += drip.velocity * dt
                drip.length = min(260f, drip.length + drip.velocity * dt * 0.32f)
                if (drip.y - drip.length > surfaceHeight || drip.age > 9f) dripIterator.remove()
            }
        }

        private fun drawWetGlass(canvas: Canvas) {
            for (stroke in wetStrokes) {
                val fade = (1f - stroke.age / 7.5f).coerceIn(0f, 1f)
                wetTrailPaint.strokeWidth = stroke.width
                wetTrailPaint.color = Color.argb((64 * fade).toInt(), 0, 0, 0)
                canvas.drawLine(stroke.startX, stroke.startY, stroke.endX, stroke.endY, wetTrailPaint)

                wetHighlightPaint.strokeWidth = max(2f, stroke.width * 0.28f)
                wetHighlightPaint.alpha = (86 * fade).toInt().coerceIn(0, 86)
                canvas.drawLine(
                    stroke.startX - 2f,
                    stroke.startY - 2f,
                    stroke.endX - 2f,
                    stroke.endY - 2f,
                    wetHighlightPaint
                )
            }

            for (drip in drips) {
                val fade = (1f - drip.age / 9f).coerceIn(0f, 1f)
                val top = max(drip.startY, drip.y - drip.length)
                dripPaint.strokeWidth = drip.width
                dripPaint.shader = LinearGradient(
                    drip.x,
                    top,
                    drip.x,
                    drip.y,
                    Color.argb((85 * fade).toInt(), 255, 255, 255),
                    Color.argb((92 * fade).toInt(), 0, 0, 0),
                    Shader.TileMode.CLAMP
                )
                canvas.drawLine(drip.x, top, drip.x + drip.lean, drip.y, dripPaint)
                dripPaint.shader = null
                dripBeadPaint.alpha = (145 * fade).toInt().coerceIn(0, 145)
                canvas.drawCircle(drip.x, drip.y, drip.width * 1.12f, dripBeadPaint)
            }
        }

        private fun createCondensationNoise(width: Int, height: Int): Bitmap {
            // Static noise breaks up the flat blurred image into cloudy condensation.
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    Color.argb(105, 245, 250, 248),
                    Color.argb(145, 210, 226, 229),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mistPaint)

            val speckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
            }
            val speckCount = (width * height / 1050f).toInt().coerceIn(600, 3800)
            repeat(speckCount) {
                val alpha = random.nextInt(12, 55)
                val gray = random.nextInt(205, 256)
                speckPaint.color = Color.argb(alpha, gray, gray, gray)
                val radius = random.nextFloat(0.8f, 4.7f)
                canvas.drawCircle(
                    random.nextFloat(0f, width.toFloat()),
                    random.nextFloat(0f, height.toFloat()),
                    radius,
                    speckPaint
                )
            }
            return bitmap
        }
    }

    private data class WetStroke(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val width: Float,
        var age: Float = 0f
    )

    private data class Drip(
        val x: Float,
        var y: Float,
        val startY: Float,
        var length: Float,
        val width: Float,
        var velocity: Float,
        val gravity: Float,
        val lean: Float,
        var age: Float = 0f
    )
}

private fun Random.nextFloat(min: Float, max: Float): Float = min + nextFloat() * (max - min)

private fun Random.nextFloatSigned(magnitude: Float): Float = nextFloat(-magnitude, magnitude)
