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
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.content.IntentFilter

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
    val etSplitSeconds: EditText = findViewById(R.id.etSplitSeconds)
    val btnHideSubtitle: Button = findViewById(R.id.btnHideSubtitle)
        val btnLogout: Button = findViewById(R.id.btnLogout)
    // etSplitSeconds will broadcast split-second changes automatically

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // Removed test translation button

        btnHideSubtitle.setOnClickListener {
            val intent = Intent(SubtitleOverlayService.ACTION_HIDE_SUBTITLE)
            sendBroadcast(intent)
        }

        // When the user types a number, broadcast it to the overlay service
        etSplitSeconds.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val secs = s?.toString()?.toIntOrNull() ?: 0
                // Start OverlayService with the seconds in the intent to ensure the service receives the update
                val svcIntent = Intent(this@MainActivity, com.example.transcriptapp.overlay.OverlayService::class.java).apply {
                    action = com.example.transcriptapp.overlay.DialogSecondsActivity.ACTION_SET_SPLIT_SECONDS
                    putExtra(com.example.transcriptapp.overlay.DialogSecondsActivity.EXTRA_SECONDS, secs)
                }
                startService(svcIntent)
                com.example.transcriptapp.utils.RecorderLogger.d("MainActivity", "Sent service intent ACTION_SET_SPLIT_SECONDS secs=$secs")
                // also send a broadcast for any receivers
                val bcast = Intent(com.example.transcriptapp.overlay.DialogSecondsActivity.ACTION_SET_SPLIT_SECONDS).apply {
                    putExtra(com.example.transcriptapp.overlay.DialogSecondsActivity.EXTRA_SECONDS, secs)
                }
                sendBroadcast(bcast)
                com.example.transcriptapp.utils.RecorderLogger.d("MainActivity", "Sent broadcast ACTION_SET_SPLIT_SECONDS secs=$secs")
            }
        })



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

    // Test translation function removed per request
}