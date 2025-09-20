package com.example.transcriptapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.content.Intent

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val screenRecordingButton: Button = findViewById(R.id.screenRecordingButton)
        screenRecordingButton.setOnClickListener {
            val intent = Intent(this, ScreenRecordingActivity::class.java)
            startActivity(intent)
        }
    }
}