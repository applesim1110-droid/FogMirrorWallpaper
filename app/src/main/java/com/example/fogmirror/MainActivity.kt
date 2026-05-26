package com.example.fogmirror

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        }
    }

    companion object {
        private const val REQUEST_CODE_PICK_IMAGE = 1001
        private const val REQUEST_CODE_CROP_IMAGE = 1002
    }
}
