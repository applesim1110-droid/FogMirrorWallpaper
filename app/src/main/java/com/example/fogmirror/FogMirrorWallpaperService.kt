package com.example.fogmirror

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
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
    override fun onCreateEngine(): Engine = FogMirrorEngine()

    private inner class FogMirrorEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val random = Random(System.nanoTime())
        private val droplets = mutableListOf<Droplet>()
        private val wetEdges = mutableListOf<WetEdge>()

        private var visible = false
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var lastFrameNanos = 0L
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
            strokeWidth = 92f
            maskFilter = BlurMaskFilter(34f, BlurMaskFilter.Blur.NORMAL)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val softReturnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(18, 255, 255, 255)
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(45f, BlurMaskFilter.Blur.NORMAL)
        }
        private val wetEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 18f
            color = Color.argb(95, 235, 250, 255)
            maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        }
        private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(82, 245, 252, 255)
            maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        }
        private val dropletBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dropletShinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(170, 255, 255, 255)
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
                    hasLastTouch = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                    wipeSegment(event.x, event.y, event.x, event.y, 0.65f)
                }

                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.historySize) {
                        val x = event.getHistoricalX(i)
                        val y = event.getHistoricalY(i)
                        wipeFromLastPoint(x, y)
                    }
                    wipeFromLastPoint(event.x, event.y)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hasLastTouch = false
                    spawnDroplets(event.x, event.y, 3)
                }
            }
            super.onTouchEvent(event)
        }

        private fun createSurfaceBuffers() {
            // The mask stores where fog is still present. Transparent pixels are wiped glass.
            fogMask = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
            fogMaskCanvas = Canvas(fogMask)
            fogMaskCanvas.drawColor(Color.WHITE)
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

            updateDroplets(dt)
            returnFogNonUniformly(dt)

            val holder = surfaceHolder
            val canvas = try {
                holder.lockCanvas()
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
                drawWetEdges(canvas, dt)
                drawDroplets(canvas)
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
            // Several offset strokes make the wipe look like skin dragging through wet glass.
            repeat(4) {
                val jitter = 9f + it * 4f
                val sx = startX + random.nextFloatSigned(jitter)
                val sy = startY + random.nextFloatSigned(jitter)
                val ex = endX + random.nextFloatSigned(jitter)
                val ey = endY + random.nextFloatSigned(jitter)
                wipePaint.strokeWidth = random.nextFloat(58f, 108f) * pressure
                fogMaskCanvas.drawLine(sx, sy, ex, ey, wipePaint)
            }

            val edgePath = Path().apply {
                moveTo(startX, startY)
                quadTo((startX + endX) * 0.5f, (startY + endY) * 0.5f, endX, endY)
            }
            wetEdges += WetEdge(edgePath, 1f, random.nextFloat(15f, 28f))
            spawnDroplets(endX, endY, random.nextInt(1, 4))
        }

        private fun returnFogNonUniformly(dt: Float) {
            // Fog returns slowly as soft cloudy patches, so cleared regions never refill as flat shapes.
            val patches = max(1, (dt * 70f).toInt())
            repeat(patches) {
                softReturnPaint.alpha = random.nextInt(5, 18)
                val radius = random.nextFloat(35f, 145f)
                fogMaskCanvas.drawCircle(
                    random.nextFloat(0f, surfaceWidth.toFloat()),
                    random.nextFloat(0f, surfaceHeight.toFloat()),
                    radius,
                    softReturnPaint
                )
            }
        }

        private fun updateDroplets(dt: Float) {
            // Droplets speed up as they fall and keep their path as a wet streak.
            val iterator = droplets.iterator()
            while (iterator.hasNext()) {
                val drop = iterator.next()
                drop.age += dt
                drop.velocityY += drop.gravity * dt
                drop.x += drop.velocityX * dt
                drop.y += drop.velocityY * dt
                drop.trail.lineTo(drop.x, drop.y)

                if (drop.y - drop.radius > surfaceHeight || drop.age > 12f) {
                    iterator.remove()
                }
            }
            mergeCloseDroplets()
        }

        private fun mergeCloseDroplets() {
            // Neighboring droplets combine into a larger, faster bead of water.
            var i = 0
            while (i < droplets.size) {
                var j = i + 1
                while (j < droplets.size) {
                    val a = droplets[i]
                    val b = droplets[j]
                    val distance = hypot(a.x - b.x, a.y - b.y)
                    if (distance < (a.radius + b.radius) * 0.78f) {
                        val totalArea = a.radius * a.radius + b.radius * b.radius
                        a.x = (a.x * a.radius + b.x * b.radius) / (a.radius + b.radius)
                        a.y = (a.y * a.radius + b.y * b.radius) / (a.radius + b.radius)
                        a.radius = min(26f, kotlin.math.sqrt(totalArea))
                        a.velocityY = max(a.velocityY, b.velocityY) * 1.05f
                        droplets.removeAt(j)
                    } else {
                        j++
                    }
                }
                i++
            }
        }

        private fun spawnDroplets(x: Float, y: Float, count: Int) {
            if (droplets.size > 130) return
            repeat(count) {
                if (random.nextFloat() > 0.72f) return@repeat
                val radius = random.nextFloat(3.5f, 12f)
                val dx = random.nextFloatSigned(42f)
                val dy = random.nextFloatSigned(26f)
                droplets += Droplet(
                    x = x + dx,
                    y = y + dy,
                    radius = radius,
                    velocityX = random.nextFloatSigned(6f),
                    velocityY = random.nextFloat(10f, 58f),
                    gravity = random.nextFloat(36f, 95f),
                    trail = Path().apply { moveTo(x + dx, y + dy) }
                )
            }
        }

        private fun drawWetEdges(canvas: Canvas, dt: Float) {
            val iterator = wetEdges.iterator()
            while (iterator.hasNext()) {
                val edge = iterator.next()
                edge.life -= dt * 0.38f
                if (edge.life <= 0f) {
                    iterator.remove()
                    continue
                }
                wetEdgePaint.strokeWidth = edge.width
                wetEdgePaint.alpha = (90 * edge.life).toInt().coerceIn(0, 90)
                canvas.drawPath(edge.path, wetEdgePaint)
            }
        }

        private fun drawDroplets(canvas: Canvas) {
            for (drop in droplets) {
                streakPaint.strokeWidth = max(2f, drop.radius * 0.35f)
                canvas.drawPath(drop.trail, streakPaint)

                dropletBodyPaint.shader = RadialGradient(
                    drop.x - drop.radius * 0.35f,
                    drop.y - drop.radius * 0.45f,
                    drop.radius * 1.25f,
                    intArrayOf(
                        Color.argb(190, 255, 255, 255),
                        Color.argb(86, 210, 235, 245),
                        Color.argb(34, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.58f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawOval(
                    RectF(
                        drop.x - drop.radius * 0.72f,
                        drop.y - drop.radius,
                        drop.x + drop.radius * 0.72f,
                        drop.y + drop.radius * 1.12f
                    ),
                    dropletBodyPaint
                )
                dropletBodyPaint.shader = null
                canvas.drawCircle(
                    drop.x - drop.radius * 0.25f,
                    drop.y - drop.radius * 0.42f,
                    max(1.2f, drop.radius * 0.18f),
                    dropletShinePaint
                )
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

    private data class Droplet(
        var x: Float,
        var y: Float,
        var radius: Float,
        var velocityX: Float,
        var velocityY: Float,
        val gravity: Float,
        val trail: Path,
        var age: Float = 0f
    )

    private data class WetEdge(
        val path: Path,
        var life: Float,
        val width: Float
    )
}

private fun Random.nextFloat(min: Float, max: Float): Float = min + nextFloat() * (max - min)

private fun Random.nextFloatSigned(magnitude: Float): Float = nextFloat(-magnitude, magnitude)
