package com.example.fogmirror

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import java.io.File

class MainActivity : Activity() {

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
        previewBg = findViewById(R.id.img_preview_bg)
        previewFog = findViewById(R.id.img_preview_fog)
        cbAutoFog = findViewById(R.id.cb_auto_fog)
        layoutManual = findViewById(R.id.layout_manual_controls)
        sbDensity = findViewById(R.id.sb_density)
        colorLayout = findViewById(R.id.layout_color_presets)

        // Add some preset colors
        val colors = intArrayOf(
            Color.rgb(205, 210, 215), // Silver (Balanced)
            Color.rgb(180, 170, 160), // Amber (Warm)
            Color.rgb(220, 230, 240), // Arctic (Cold)
            Color.rgb(255, 255, 255), // Pure White
            Color.rgb(150, 150, 150)  // Dark Fog
        )

        for (color in colors) {
            val view = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(120, 120).apply {
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
        findViewById<Button>(R.id.btn_pick_image).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
        }

        findViewById<Button>(R.id.btn_set_wallpaper).setOnClickListener {
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
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveSettings()
            }
        })
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
                    
                    // Generate a blurred preview
                    val blurred = blurForPreview(bmp)
                    previewFog.setImageBitmap(blurred)
                    
                    // Apply fog tint and density
                    if (isAuto) {
                        // In auto mode, we'd need to run the same logic as the service
                        // For preview simplicity, just use the balanced grey
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
            updatePreview()
        }
    }

    companion object {
        private const val REQUEST_CODE_PICK_IMAGE = 1001
        private const val REQUEST_CODE_CROP_IMAGE = 1002
    }
}
