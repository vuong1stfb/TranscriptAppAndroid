package com.example.transcriptapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.transcriptapp.overlay.OverlayService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText: TextView = findViewById(R.id.overlayStatus)
        val btnGrant: Button = findViewById(R.id.btnGrantOverlay)
        val btnStartOverlay: Button = findViewById(R.id.btnStartOverlay)

        fun refresh() {
            val granted = Settings.canDrawOverlays(this)
            statusText.text = if (granted) "Overlay: granted" else "Overlay: not granted"
            btnStartOverlay.isEnabled = granted
        }

        btnGrant.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStartOverlay.setOnClickListener {
            // No action string is required; the service shows overlay in onCreate
            startService(Intent(this, OverlayService::class.java))
        }

        refresh()
    }
}