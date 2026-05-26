package com.example.fogmirror

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import java.io.File
import java.io.FileOutputStream

class CropActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        val uriString = intent.getStringExtra("uri")
        if (uriString == null) {
            finish()
            return
        }

        val uri = Uri.parse(uriString)
        val inputStream = contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        
        val cropView = findViewById<CropView>(R.id.cropView)
        // Default to portrait but user can change
        cropView.setBitmap(bitmap, true)

        findViewById<Button>(R.id.btn_portrait).setOnClickListener {
            cropView.setOrientation(true)
        }

        findViewById<Button>(R.id.btn_landscape).setOnClickListener {
            cropView.setOrientation(false)
        }

        findViewById<Button>(R.id.btn_done).setOnClickListener {
            val cropped = cropView.getCroppedBitmap()
            if (cropped != null) {
                val file = File(filesDir, "background.jpg")
                FileOutputStream(file).use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                
                val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
                prefs.edit().putString("background_uri", Uri.fromFile(file).toString()).apply()
                
                setResult(RESULT_OK)
            }
            finish()
        }

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            finish()
        }
    }
}
