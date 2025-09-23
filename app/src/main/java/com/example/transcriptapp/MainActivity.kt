package com.example.transcriptapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.transcriptapp.overlay.OverlayService
import com.example.transcriptapp.overlay.SubtitleOverlayService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Kiểm tra trạng thái đăng nhập, nếu chưa đăng nhập thì chuyển về LoginActivity
        val prefs = getSharedPreferences("auth_prefs", MODE_PRIVATE)
        val isLoggedIn = prefs.getString("access_token", null) != null
        if (!isLoggedIn) {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        val btnStartOverlay: Button = findViewById(R.id.btnStartOverlay)
        btnStartOverlay.setOnClickListener {
            // No action string is required; the service shows overlay in onCreate
            startService(Intent(this, OverlayService::class.java))
        }

        val btnGrantOverlay: Button = findViewById(R.id.btnGrantOverlay)
        val btnLogout: Button = findViewById(R.id.btnLogout)
        val btnSubtitleOverlay: Button = findViewById(R.id.btnSubtitleOverlay)
        val btnTestSubtitle: Button = findViewById(R.id.btnTestSubtitle)
        val btnHideSubtitle: Button = findViewById(R.id.btnHideSubtitle)

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnSubtitleOverlay.setOnClickListener {
            // Start subtitle overlay service
            val intent = Intent(this, SubtitleOverlayService::class.java)
            startService(intent)
        }

        btnTestSubtitle.setOnClickListener {
            // Test subtitle with sample text
            val intent = Intent(SubtitleOverlayService.ACTION_SHOW_SUBTITLE).apply {
                putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, "Đây là subtitle mẫu để test tính năng hiển thị phụ đề overlay")
            }
            sendBroadcast(intent)
        }

        btnHideSubtitle.setOnClickListener {
            // Hide subtitle
            val intent = Intent(SubtitleOverlayService.ACTION_HIDE_SUBTITLE)
            sendBroadcast(intent)
        }


        btnLogout.setOnClickListener {
            // Xóa dữ liệu đăng nhập trong SharedPreferences
            val prefs = getSharedPreferences("auth_prefs", MODE_PRIVATE)
            prefs.edit().clear().apply()
            // Chuyển về màn hình đăng nhập
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}