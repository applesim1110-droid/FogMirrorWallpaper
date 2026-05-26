package com.example.fogmirror

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var layoutLanding: LinearLayout
    private lateinit var layoutPreviewMode: View
    
    private lateinit var previewBg: ImageView
    private lateinit var previewFog: ImageView
    private lateinit var cbAutoFog: CheckBox
    private lateinit var layoutManual: LinearLayout
    private lateinit var sbDensity: SeekBar
    private lateinit var colorLayout: LinearLayout

    private var currentDensity = 235
    private var currentColor = Color.argb(235, 205, 210, 215)
    private var isAuto = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSettings()
        setupListeners()
        updatePreview()
    }

    private fun initViews() {
        layoutLanding = findViewById(R.id.layout_landing)
        layoutPreviewMode = findViewById(R.id.layout_preview_mode)
        
        previewBg = findViewById(R.id.img_preview_bg)
        previewFog = findViewById(R.id.img_preview_fog)
        cbAutoFog = findViewById(R.id.cb_auto_fog)
        layoutManual = findViewById(R.id.layout_manual_controls)
        sbDensity = findViewById(R.id.sb_density)
        colorLayout = findViewById(R.id.layout_color_presets)

        // Add some preset colors
        val colors = intArrayOf(
            Color.rgb(205, 210, 215), // Silver
            Color.rgb(180, 170, 160), // Amber
            Color.rgb(220, 230, 240), // Arctic
            Color.rgb(255, 255, 255), // White
            Color.rgb(150, 150, 150)  // Dark
        )

        for (color in colors) {
            addColorPreset(color)
        }

        val customButton = Button(this).apply {
            text = getString(R.string.custom_color)
            setOnClickListener { showColorPickerDialog() }
        }
        colorLayout.addView(customButton)
    }

    private fun addColorPreset(color: Int) {
        val view = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 100).apply {
                setMargins(0, 0, 16, 0)
            }
            setBackgroundColor(color)
            setOnClickListener {
                currentColor = Color.argb(currentDensity, Color.red(color), Color.green(color), Color.blue(color))
                saveSettings()
                updatePreview()
            }
        }
        colorLayout.addView(view)
    }

    private fun showColorPickerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        val rSeek = createRGBSeekBar(layout, "Red", Color.red(currentColor))
        val gSeek = createRGBSeekBar(layout, "Green", Color.green(currentColor))
        val bSeek = createRGBSeekBar(layout, "Blue", Color.blue(currentColor))

        AlertDialog.Builder(this)
            .setTitle("Custom Fog Color")
            .setView(layout)
            .setPositiveButton("Set") { _, _ ->
                currentColor = Color.argb(currentDensity, rSeek.progress, gSeek.progress, bSeek.progress)
                saveSettings()
                updatePreview()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createRGBSeekBar(parent: ViewGroup, label: String, initial: Int): SeekBar {
        val tv = TextView(this).apply { text = label; setPadding(0, 8, 0, 0) }
        val sb = SeekBar(this).apply { max = 255; progress = initial }
        parent.addView(tv)
        parent.addView(sb)
        return sb
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        isAuto = prefs.getBoolean("auto_fog", true)
        currentDensity = prefs.getInt("fog_density", 235)
        currentColor = prefs.getInt("fog_color", Color.argb(235, 205, 210, 215))

        cbAutoFog.isChecked = isAuto
        layoutManual.visibility = if (isAuto) View.GONE else View.VISIBLE
        sbDensity.progress = currentDensity
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("auto_fog", isAuto)
            putInt("fog_density", currentDensity)
            putInt("fog_color", currentColor)
            apply()
        }
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btn_pick_image_main).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
        }

        findViewById<Button>(R.id.btn_go_to_customize).setOnClickListener {
            switchToPreviewMode(true)
        }

        findViewById<ImageButton>(R.id.btn_back_to_main).setOnClickListener {
            switchToPreviewMode(false)
        }

        findViewById<Button>(R.id.btn_apply_wallpaper).setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, FogMirrorWallpaperService::class.java)
                )
            }
            startActivity(intent)
        }

        cbAutoFog.setOnCheckedChangeListener { _, isChecked ->
            isAuto = isChecked
            layoutManual.visibility = if (isChecked) View.GONE else View.VISIBLE
            saveSettings()
            updatePreview()
        }

        sbDensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentDensity = progress
                    currentColor = Color.argb(currentDensity, Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { saveSettings() }
        })
    }

    private fun switchToPreviewMode(showPreview: Boolean) {
        layoutLanding.visibility = if (showPreview) View.GONE else View.VISIBLE
        layoutPreviewMode.visibility = if (showPreview) View.VISIBLE else View.GONE
        if (showPreview) updatePreview()
    }

    private fun updatePreview() {
        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString("background_uri", null)
        
        if (uriString != null) {
            try {
                val uri = Uri.parse(uriString)
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    previewBg.setImageBitmap(bmp)
                    
                    val blurred = blurForPreview(bmp)
                    previewFog.setImageBitmap(blurred)
                    
                    if (isAuto) {
                        previewFog.setColorFilter(Color.argb(currentDensity, 205, 210, 215), PorterDuff.Mode.SRC_ATOP)
                    } else {
                        previewFog.setColorFilter(currentColor, PorterDuff.Mode.SRC_ATOP)
                    }
                    previewFog.imageAlpha = currentDensity
                }
            } catch (e: Exception) {}
        }
    }

    private fun blurForPreview(src: Bitmap): Bitmap {
        val scale = 0.2f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        if (requestCode == REQUEST_CODE_PICK_IMAGE) {
            data?.data?.let { uri ->
                val intent = Intent(this, CropActivity::class.java).apply {
                    putExtra("uri", uri.toString())
                }
                startActivityForResult(intent, REQUEST_CODE_CROP_IMAGE)
            }
        } else if (requestCode == REQUEST_CODE_CROP_IMAGE) {
            Toast.makeText(this, R.string.image_selected, Toast.LENGTH_SHORT).show()
            switchToPreviewMode(true)
        }
    }

    companion object {
        private const val REQUEST_CODE_PICK_IMAGE = 1001
        private const val REQUEST_CODE_CROP_IMAGE = 1002
    }
}
