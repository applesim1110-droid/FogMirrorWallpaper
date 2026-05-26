package com.example.fogmirror

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.hypot
import kotlin.math.max

class FogMirrorWallpaperService : WallpaperService() {

    companion object {
        private const val DEFAULT_FOG_ALPHA = 235
        private const val WIPE_FADE_SECONDS = 15f
        private const val MAX_STROKES = 20
    }

    override fun onCreateEngine(): Engine = FogEngine()

    private data class WipeStroke(
        val bitmap: Bitmap,
        var time: Float
    )

    private inner class FogEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)

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

        private val strokes = mutableListOf<WipeStroke>()

        private val matrix = Matrix()
        private val frameRunnable = Runnable { drawFrame() }

        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        private var fogColor = Color.argb(235, 205, 210, 215)
        private var fogDensity = DEFAULT_FOG_ALPHA
        
        private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

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

        private val balancePaint = Paint()

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(true)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings()
            loadBitmaps()
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            when (key) {
                "background_uri" -> {
                    loadBitmaps()
                    setupMatrix()
                    drawFrame()
                }
                "auto_fog", "fog_density", "fog_color" -> {
                    updateSettings()
                    drawFrame()
                }
            }
        }

        private fun updateSettings() {
            val isAuto = prefs.getBoolean("auto_fog", true)
            if (isAuto) {
                // If auto, re-calculate based on current clearBitmap
                if (::clearBitmap.isInitialized) {
                    calculateAutoDensityAndColor(clearBitmap)
                }
            } else {
                fogDensity = prefs.getInt("fog_density", DEFAULT_FOG_ALPHA)
                fogColor = prefs.getInt("fog_color", Color.argb(235, 205, 210, 215))
                fogPaint.alpha = fogDensity
                noisePaint.alpha = (fogDensity * 0.5f).toInt()
                balancePaint.color = fogColor
                balancePaint.alpha = (fogDensity * 0.4f).toInt()
            }
        }

        private fun loadBitmaps() {
            val uriString = prefs.getString("background_uri", null)
            var loadedBitmap: Bitmap? = null

            if (uriString != null) {
                try {
                    val uri = Uri.parse(uriString)
                    val inputStream = contentResolver.openInputStream(uri)
                    loadedBitmap = BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {}
            }

            clearBitmap = loadedBitmap ?: BitmapFactory.decodeResource(resources, R.drawable.mirror_clear)
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

            if (prefs.getBoolean("auto_fog", true)) {
                calculateAutoDensityAndColor(clearBitmap)
            } else {
                updateSettings() // Apply manual density
            }

            fogBitmap = blurBitmapHighRes(clearBitmap)
        }

        private fun calculateAutoDensityAndColor(src: Bitmap) {
            val sampleSize = 100
            val x = (src.width / 2 - sampleSize / 2).coerceAtLeast(0)
            val y = (src.height / 2 - sampleSize / 2).coerceAtLeast(0)
            val w = sampleSize.coerceAtMost(src.width - x)
            val h = sampleSize.coerceAtMost(src.height - y)
            
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, x, y, w, h)
            
            var r = 0L; var g = 0L; var b = 0L; var lum = 0L
            for (p in pixels) {
                val red = Color.red(p); val green = Color.green(p); val blue = Color.blue(p)
                r += red; g += green; b += blue
                lum += (0.299 * red + 0.587 * green + 0.114 * blue).toLong()
            }
            val avgR = (r / pixels.size).toInt(); val avgG = (g / pixels.size).toInt(); val avgB = (b / pixels.size).toInt()
            val avgLum = (lum / pixels.size).toInt()

            val balR = (210 * 0.7f + avgR * 0.3f).toInt().coerceIn(0, 255)
            val balG = (215 * 0.7f + avgG * 0.3f).toInt().coerceIn(0, 255)
            val balB = (220 * 0.7f + avgB * 0.3f).toInt().coerceIn(0, 255)
            
            fogDensity = if (avgLum < 100) 180 else 235
            fogColor = Color.argb(fogDensity, balR, balG, balB)
            fogPaint.alpha = fogDensity
            noisePaint.alpha = (fogDensity * 0.5f).toInt()
            balancePaint.color = fogColor
            balancePaint.alpha = (fogDensity * 0.4f).toInt()
        }

        private fun blurBitmapHighRes(src: Bitmap): Bitmap {
            val blurred = src.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(blurred)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(25f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawBitmap(src, 0f, 0f, paint)
            return blurred
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

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            surfaceWidth = max(1, w); surfaceHeight = max(1, h)
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
                MotionEvent.ACTION_DOWN -> startNewStroke(event.x, event.y)
                MotionEvent.ACTION_MOVE -> drawOnLatest(event.x, event.y)
                MotionEvent.ACTION_UP -> hasLastTouch = false
            }
        }

        private fun createBuffers() {
            noiseBitmap = createNoise(surfaceWidth, surfaceHeight)
            setupMatrix()
        }

        private fun setupMatrix() {
            val scale = max(surfaceWidth / clearBitmap.width.toFloat(), surfaceHeight / clearBitmap.height.toFloat())
            val dx = (surfaceWidth - clearBitmap.width * scale) / 2f
            val dy = (surfaceHeight - clearBitmap.height * scale) / 2f
            matrix.reset(); matrix.postScale(scale, scale); matrix.postTranslate(dx, dy)
        }

        private fun startNewStroke(x: Float, y: Float) {
            if (strokes.size >= MAX_STROKES) strokes.removeAt(0)
            val bmp = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ALPHA_8)
            val stroke = WipeStroke(bmp, 0f)
            strokes.add(stroke)
            lastTouchX = x; lastTouchY = y; hasLastTouch = true
            drawOnStroke(stroke, x, y, x, y)
        }

        private fun drawOnLatest(x: Float, y: Float) {
            if (!hasLastTouch || strokes.isEmpty()) return
            drawOnStroke(strokes.last(), lastTouchX, lastTouchY, x, y)
            lastTouchX = x; lastTouchY = y
        }

        private fun drawOnStroke(s: WipeStroke, sx: Float, sy: Float, ex: Float, ey: Float) {
            val canvas = Canvas(s.bitmap)
            val steps = max(1, (hypot(ex - sx, ey - sy) / 18f).toInt())
            var px = sx; var py = sy
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val x = sx + (ex - sx) * t; val y = sy + (ey - sy) * t
                canvas.drawLine(px, py, x, y, wipePaint)
                px = x; py = y
            }
        }

        private fun drawFrame() {
            if (!visible) return
            val now = System.nanoTime()
            val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameTime = now
            val iterator = strokes.iterator()
            while (iterator.hasNext()) {
                val s = iterator.next(); s.time += dt
                if (s.time >= WIPE_FADE_SECONDS) iterator.remove()
            }
            val canvas = surfaceHolder.lockHardwareCanvas() ?: return
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(clearBitmap, matrix, clearPaint)
            drawFog(canvas)
            surfaceHolder.unlockCanvasAndPost(canvas)
            handler.postDelayed(frameRunnable, 16)
        }

        private fun drawFog(canvas: Canvas) {
            val layer = canvas.saveLayer(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), null)
            canvas.drawBitmap(fogBitmap, matrix, fogPaint)
            canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), balancePaint)
            canvas.drawBitmap(noiseBitmap, 0f, 0f, noisePaint)
            for (s in strokes) {
                val progress = (s.time / WIPE_FADE_SECONDS).coerceIn(0f, 1f)
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
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                    Color.argb(130, 190, 195, 200), Color.argb(170, 160, 165, 170), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            return bmp
        }
    }
}
