package com.sova.test

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { requestProjection() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val capIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(capIntent)
            else startService(capIntent)

            startService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "SovaTest запущено", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Потрібен дозвіл на захоплення екрану", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnStart).setOnClickListener { checkOverlayPerm() }
    }

    private fun checkOverlayPerm() {
        if (!Settings.canDrawOverlays(this)) {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermLauncher.launch(i)
        } else requestProjection()
    }

    private fun requestProjection() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Дай дозвіл 'поверх інших додатків'", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
