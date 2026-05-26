package com.example.fogmirror

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.hypot
import kotlin.math.max

class FogMirrorWallpaperService : WallpaperService() {

    companion object {
        private const val INITIAL_FOG_ALPHA = 235
        private const val WIPE_FADE_SECONDS = 15f
        private const val MAX_STROKES = 20
    }

    override fun onCreateEngine(): Engine = FogEngine()

    private data class WipeStroke(
        val bitmap: Bitmap,
        var time: Float
    )

    private data class DripPoint(
        var x: Float,
        var y: Float,
        var velocity: Float,
        var width: Float,
        var active: Boolean = true
    )

    private inner class FogEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())

        private var visible = false
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var lastFrameTime = 0L

        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var hasLastTouch = false

        private lateinit var clearBitmap: Bitmap
        private lateinit var fogBitmap: Bitmap
        private lateinit var noiseBitmap: Bitmap
        private lateinit var trailBitmap: Bitmap
        private lateinit var trailCanvas: Canvas

        private val drips = mutableListOf<DripPoint>()
        private val random = java.util.Random()

        private val strokes = mutableListOf<WipeStroke>()

        private val matrix = Matrix()
        private val frameRunnable = Runnable { drawFrame() }

        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = INITIAL_FOG_ALPHA
        }

        private val wipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 90f
            maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
        }

        private val noisePaint = Paint().apply {
            alpha = 130
        }

        private val dripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(true)

            // Safely decode or provide fallback to prevent crashes if files are missing/corrupted
            clearBitmap = BitmapFactory.decodeResource(resources, R.drawable.mirror_clear)
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

            fogBitmap = BitmapFactory.decodeResource(resources, R.drawable.fog_blur)
                ?: createFallbackFog()
        }

        private fun createFallbackFog(): Bitmap {
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            // Using a warmer, slightly desaturated amber/sepia tone to match the orange tint
            Canvas(bmp).drawColor(Color.argb(235, 180, 170, 160))
            return bmp
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                lastFrameTime = System.nanoTime()
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
            createBuffers()
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frameRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    startNewStroke(event.x, event.y)
                }

                MotionEvent.ACTION_MOVE -> {
                    drawOnLatest(event.x, event.y)
                    // Occasionally spawn a drip at the current touch point
                    if (random.nextFloat() < 0.15f) {
                        spawnDrip(event.x, event.y)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    hasLastTouch = false
                }
            }
        }

        private fun spawnDrip(x: Float, y: Float) {
            drips.add(DripPoint(
                x = x,
                y = y,
                velocity = 80f + random.nextFloat() * 120f,
                width = 8f + random.nextFloat() * 12f
            ))
        }

        private fun createBuffers() {
            noiseBitmap = createNoise(surfaceWidth, surfaceHeight)
            
            trailBitmap = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ALPHA_8)
            trailCanvas = Canvas(trailBitmap)
            
            setupMatrix()
        }

        private fun setupMatrix() {
            val scale = max(
                surfaceWidth / clearBitmap.width.toFloat(),
                surfaceHeight / clearBitmap.height.toFloat()
            )

            val dx = (surfaceWidth - clearBitmap.width * scale) / 2f
            val dy = (surfaceHeight - clearBitmap.height * scale) / 2f

            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(dx, dy)
        }

        private fun startNewStroke(x: Float, y: Float) {
            if (strokes.size >= MAX_STROKES) {
                strokes.removeAt(0)
            }

            // Use ALPHA_8 to save memory (1 byte per pixel vs 4)
            // Essential for high-resolution tablets to avoid OutOfMemoryError
            val bmp = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ALPHA_8)
            val stroke = WipeStroke(bmp, 0f)

            strokes.add(stroke)

            lastTouchX = x
            lastTouchY = y
            hasLastTouch = true

            drawOnStroke(stroke, x, y, x, y)
        }

        private fun drawOnLatest(x: Float, y: Float) {
            if (!hasLastTouch || strokes.isEmpty()) return

            val stroke = strokes.last()
            drawOnStroke(stroke, lastTouchX, lastTouchY, x, y)

            lastTouchX = x
            lastTouchY = y
        }

        private fun drawOnStroke(
            stroke: WipeStroke,
            sx: Float,
            sy: Float,
            ex: Float,
            ey: Float
        ) {
            val canvas = Canvas(stroke.bitmap)

            val steps = max(1, (hypot(ex - sx, ey - sy) / 18f).toInt())

            var px = sx
            var py = sy

            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val x = sx + (ex - sx) * t
                val y = sy + (ey - sy) * t

                canvas.drawLine(px, py, x, y, wipePaint)

                px = x
                py = y
            }
        }

        private fun drawFrame() {
            if (!visible) return

            val now = System.nanoTime()
            val dt = ((now - lastFrameTime) / 1_000_000_000f)
                .coerceIn(0.001f, 0.05f)

            lastFrameTime = now

            updateDrips(dt)

            // update strokes independently
            val iterator = strokes.iterator()
            while (iterator.hasNext()) {
                val s = iterator.next()
                s.time += dt

                if (s.time >= WIPE_FADE_SECONDS) {
                    iterator.remove()
                }
            }

            val canvas = surfaceHolder.lockHardwareCanvas() ?: return

            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(clearBitmap, matrix, clearPaint)

            drawFog(canvas)

            surfaceHolder.unlockCanvasAndPost(canvas)

            handler.postDelayed(frameRunnable, 16)
        }

        private fun updateDrips(dt: Float) {
            val iterator = drips.iterator()
            while (iterator.hasNext()) {
                val drip = iterator.next()
                if (!drip.active) {
                    iterator.remove()
                    continue
                }

                val oldY = drip.y
                // Variable speed to simulate surface tension "stutter"
                val speedVar = 1f + 0.3f * kotlin.math.sin(nowToSeconds() * 5.0).toFloat()
                drip.y += drip.velocity * speedVar * dt
                
                // Gradually thin out the drip
                drip.width *= (1f - 0.05f * dt)

                // Draw to trail bitmap
                dripPaint.strokeWidth = drip.width
                trailCanvas.drawLine(drip.x, oldY, drip.x, drip.y, dripPaint)

                if (drip.y > surfaceHeight || drip.width < 2f) {
                    drip.active = false
                }
            }
        }

        private fun nowToSeconds(): Float = System.currentTimeMillis() / 1000f

        private fun drawFog(canvas: Canvas) {
            val layer = canvas.saveLayer(
                0f, 0f,
                surfaceWidth.toFloat(),
                surfaceHeight.toFloat(),
                null
            )

            // fog layer
            canvas.drawBitmap(fogBitmap, matrix, fogPaint)

            // texture
            canvas.drawBitmap(noiseBitmap, 0f, 0f, noisePaint)

            // Apply trails (water traces)
            val maskPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            canvas.drawBitmap(trailBitmap, 0f, 0f, maskPaint)

            // apply strokes independently
            for (s in strokes) {
                val progress = (s.time / WIPE_FADE_SECONDS)
                    .coerceIn(0f, 1f)

                val paint = Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    alpha = ((1f - progress) * 255).toInt()
                }

                canvas.drawBitmap(s.bitmap, 0f, 0f, paint)
            }

            canvas.restoreToCount(layer)
        }

        private fun createNoise(w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    // Warm desaturated tones for a more aesthetic look with the orange background
                    Color.argb(130, 160, 150, 140),
                    Color.argb(170, 110, 100, 95),
                    Shader.TileMode.CLAMP
                )
            }

            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            return bmp
        }
    }
}