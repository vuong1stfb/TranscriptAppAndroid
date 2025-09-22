package com.example.transcriptapp.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.transcriptapp.ScreenRecordService
import com.example.transcriptapp.utils.RecorderLogger

class CapturePermissionActivity : AppCompatActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var launcher: ActivityResultLauncher<Intent>
    private lateinit var micPermission: ActivityResultLauncher<String>
    private lateinit var notifPermission: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val svc = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data!!)
                }
                RecorderLogger.media("CapturePermissionActivity", "START", "Starting recording service from permission activity")
                startForegroundService(svc)
            }
            finish()
        }

        micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            RecorderLogger.permission("CapturePermissionActivity", "RECORD_AUDIO", if (granted) "GRANTED" else "DENIED")
            // Regardless of mic permission result, proceed to request projection; recording will fail gracefully if needed
            requestProjection()
        }

        notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            RecorderLogger.permission("CapturePermissionActivity", "POST_NOTIFICATIONS", if (granted) "GRANTED" else "DENIED")
            // After handling notification permission, request microphone permission
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Start by asking for notification permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestProjection() {
        launcher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
