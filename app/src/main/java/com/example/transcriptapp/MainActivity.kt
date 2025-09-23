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
import com.example.transcriptapp.service.translate.GoogleTranslateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val btnStartSubtitle: Button = findViewById(R.id.btnStartSubtitle)
        val btnTestTranslation: Button = findViewById(R.id.btnTestTranslation)
        val btnHideSubtitle: Button = findViewById(R.id.btnHideSubtitle)
        val btnLogout: Button = findViewById(R.id.btnLogout)

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStartSubtitle.setOnClickListener {
            startService(Intent(this, SubtitleOverlayService::class.java))
        }

        btnTestTranslation.setOnClickListener {
            testTranslationFunction()
        }

        btnHideSubtitle.setOnClickListener {
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

    private fun testTranslationFunction() {
        CoroutineScope(Dispatchers.IO).launch {
            val translateService = GoogleTranslateService()
            
            // Test the example text from the problem statement
            val testText = "You're receiving notifications because you modified the open/close state."
            val translatedText = translateService.translateText(testText, "vi", "auto")
            
            withContext(Dispatchers.Main) {
                // Show the result in subtitle overlay
                val displayText = if (translatedText != null) {
                    "Original: $testText\n\nTranslated: $translatedText"
                } else {
                    "Translation failed: $testText"
                }
                
                val intent = Intent(SubtitleOverlayService.ACTION_SHOW_SUBTITLE).apply {
                    putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, displayText)
                }
                sendBroadcast(intent)
            }
        }
    }
}