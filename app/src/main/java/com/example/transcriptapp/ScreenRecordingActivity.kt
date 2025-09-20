package com.example.transcriptapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.transcriptapp.utils.RecorderLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordingActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var isRecording = false

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var splitButton: Button
    private lateinit var fileInfoTextView: TextView

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var requestMediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var requestNotificationPermissionLauncher: ActivityResultLauncher<String>
    
    // Broadcast receiver to get the file path when recording stops
    private val recordingStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val filePath = intent.getStringExtra(ScreenRecordService.EXTRA_OUTPUT_FILE_PATH)
            RecorderLogger.broadcast("ScreenRecordingActivity", "RECORDING_STOPPED", mapOf("filePath" to filePath))
            if (filePath != null) {
                val file = File(filePath)
                RecorderLogger.file("ScreenRecordingActivity", "SAVED", filePath, file.length())
                fileInfoTextView.text = "Recording saved to:\n$filePath"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_recording)

        try {
            mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            startButton = findViewById(R.id.startButton)
            stopButton = findViewById(R.id.stopButton)
            splitButton = findViewById(R.id.splitButton)
            fileInfoTextView = findViewById(R.id.fileInfoTextView)
            val viewRecordingsButton: Button = findViewById(R.id.viewRecordingsButton)

            stopButton.isEnabled = false
            splitButton.isEnabled = false

            requestPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    startScreenRecording()
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }

            requestNotificationPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    RecorderLogger.permission("ScreenRecordingActivity", "POST_NOTIFICATIONS", "GRANTED")
                    startScreenRecording()
                } else {
                    RecorderLogger.permission("ScreenRecordingActivity", "POST_NOTIFICATIONS", "DENIED")
                    Toast.makeText(this, "Notification permission is needed for recording status", Toast.LENGTH_LONG).show()
                }
            }

            requestMediaProjectionLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                RecorderLogger.d("ScreenRecordingActivity", "Media projection launcher callback triggered")
                RecorderLogger.d("ScreenRecordingActivity", "Media projection result: code=${result.resultCode}, data=${result.data != null}")

                if (result.resultCode == RESULT_OK && result.data != null) {
                    // Start Foreground Service with media projection data
                    val svc = Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_START
                        putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data!!)
                    }
                    RecorderLogger.media("ScreenRecordingActivity", "START", "Starting recording service with valid parameters")
                    startForegroundService(svc)
                    isRecording = true
                    startButton.isEnabled = false
                    stopButton.isEnabled = true
                    splitButton.isEnabled = true
                    Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                } else {
                    // Handle different error cases
                    when (result.resultCode) {
                        RESULT_CANCELED -> {
                            RecorderLogger.w("ScreenRecordingActivity", "Media projection cancelled by user")
                            Toast.makeText(this, "Screen recording was cancelled. Please try again and allow screen recording.", Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            RecorderLogger.e("ScreenRecordingActivity", "Media projection failed with code: ${result.resultCode}")
                            Toast.makeText(this, "Screen recording permission denied. Please allow screen recording to continue.", Toast.LENGTH_LONG).show()
                        }
                    }
                    // Reset UI state since recording didn't start
                    isRecording = false
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    splitButton.isEnabled = false
                }
            }

            startButton.setOnClickListener {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startScreenRecording()
                }
            }

            stopButton.setOnClickListener {
                stopRecording()
            }

            splitButton.setOnClickListener {
                splitRecording()
            }
            
            viewRecordingsButton.setOnClickListener {
                openRecordingsFolder()
            }
            
        } catch (e: Exception) {
            Log.e("ScreenRecordingActivity", "Error initializing activity", e)
            Toast.makeText(this, "Failed to initialize screen recording: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startScreenRecording() {
        try {
            RecorderLogger.methodEntry("ScreenRecordingActivity", "startScreenRecording")
            
            // Check if mediaProjectionManager is initialized
            if (mediaProjectionManager == null) {
                RecorderLogger.e("ScreenRecordingActivity", "MediaProjectionManager is null")
                Toast.makeText(this, "Screen recording is not available on this device", Toast.LENGTH_LONG).show()
                return
            }
            
            // Check for microphone permission first
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                RecorderLogger.permission("ScreenRecordingActivity", "RECORD_AUDIO", "NOT_GRANTED")
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            } else {
                RecorderLogger.permission("ScreenRecordingActivity", "RECORD_AUDIO", "GRANTED")
            }
            
            // Also check storage permissions on older Android versions
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                        != PackageManager.PERMISSION_GRANTED) {
                    RecorderLogger.permission("ScreenRecordingActivity", "WRITE_EXTERNAL_STORAGE", "NOT_GRANTED")
                    Toast.makeText(this, "Storage permission needed on this Android version", Toast.LENGTH_LONG).show()
                    return
                } else {
                    RecorderLogger.permission("ScreenRecordingActivity", "WRITE_EXTERNAL_STORAGE", "GRANTED")
                }
            }
            
            // Check for notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                        != PackageManager.PERMISSION_GRANTED) {
                    RecorderLogger.permission("ScreenRecordingActivity", "POST_NOTIFICATIONS", "NOT_GRANTED")
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                } else {
                    RecorderLogger.permission("ScreenRecordingActivity", "POST_NOTIFICATIONS", "GRANTED")
                }
            }
            
            // Check Android version compatibility
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                RecorderLogger.e("ScreenRecordingActivity", "Screen recording requires Android 5.0 or higher")
                Toast.makeText(this, "Screen recording requires Android 5.0 or higher", Toast.LENGTH_LONG).show()
                return
            }
            
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            if (intent == null) {
                RecorderLogger.e("ScreenRecordingActivity", "Failed to create screen capture intent")
                Toast.makeText(this, "Unable to create screen recording request", Toast.LENGTH_LONG).show()
                return
            }
            RecorderLogger.d("ScreenRecordingActivity", "Requesting media projection permission")
            requestMediaProjectionLauncher.launch(intent)
        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordingActivity", "Error requesting media projection", e)
            Toast.makeText(this, "Failed to start screen recording: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            RecorderLogger.methodEntry("ScreenRecordingActivity", "stopRecording")
            startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP })
            isRecording = false
            startButton.isEnabled = true
            stopButton.isEnabled = false
            splitButton.isEnabled = false

            RecorderLogger.state("ScreenRecordingActivity", "isRecording", true, false)
            RecorderLogger.media("ScreenRecordingActivity", "STOP", "Recording stopped by user")

            // Show the file location more clearly
            val moviesPath = getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath ?: "Movies folder"
            fileInfoTextView.text = "Recording stopped.\nFile saved in:\n$moviesPath"
            Toast.makeText(this, "Recording saved in app's Movies folder", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordingActivity", "Error stopping recording", e)
            Toast.makeText(this, "Failed to stop recording: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun splitRecording() {
        if (!isRecording) {
            Toast.makeText(this, "Không có phiên ghi nào để cắt", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            RecorderLogger.methodEntry("ScreenRecordingActivity", "splitRecording")
            startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_SPLIT })
            Toast.makeText(this, "Đã lưu đoạn hiện tại, tiếp tục ghi mới", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordingActivity", "Error splitting recording", e)
            Toast.makeText(this, "Không thể cắt bản ghi: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun openRecordingsFolder() {
        try {
            val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (moviesDir?.exists() == true) {
                // Show the folder contents
                val files = moviesDir.listFiles()
                val stringBuilder = StringBuilder()
                stringBuilder.append("Recordings folder: ${moviesDir.absolutePath}\n\n")
                
                if (files != null && files.isNotEmpty()) {
                    stringBuilder.append("Files found: ${files.size}\n\n")
                    
                    var emptyFileCount = 0
                    for (file in files) {
                        val lastModified = Date(file.lastModified())
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        val formattedDate = dateFormat.format(lastModified)
                        val fileSizeKb = file.length() / 1024
                        
                        stringBuilder.append("${file.name}\n")
                        stringBuilder.append("  Size: $fileSizeKb KB")
                        if (fileSizeKb == 0L) {
                            stringBuilder.append(" (EMPTY FILE!)")
                            emptyFileCount++
                        }
                        stringBuilder.append("\n")
                        stringBuilder.append("  Date: $formattedDate\n\n")
                    }
                    
                    if (emptyFileCount > 0) {
                        stringBuilder.append("\n\nWARNING: $emptyFileCount files are empty (0 KB).\n")
                        stringBuilder.append("This usually happens when recording fails or is stopped too quickly.\n")
                        stringBuilder.append("Try recording for at least 2-3 seconds before stopping.")
                    }
                } else {
                    stringBuilder.append("No recording files found")
                }
                
                fileInfoTextView.text = stringBuilder.toString()
                Toast.makeText(this, "Found ${files?.size ?: 0} recordings", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Recordings folder not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ScreenRecordingActivity", "Error opening recordings folder", e)
            Toast.makeText(this, "Failed to open recordings folder: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFileInfo() {
        // In this refactor, the service owns the file lifecycle. Optionally, implement a broadcast to update UI.
        // For now, we provide a simple message.
        fileInfoTextView.text = ""
    }

    override fun onResume() {
        super.onResume()
        RecorderLogger.methodEntry("ScreenRecordingActivity", "onResume")
        // Register the broadcast receiver to get file path when recording stops
        try {
            val filter = IntentFilter(ScreenRecordService.BROADCAST_RECORDING_STOPPED)
            
            // Use the appropriate registration method based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(recordingStoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(recordingStoppedReceiver, filter)
            }
            RecorderLogger.d("ScreenRecordingActivity", "Successfully registered broadcast receiver")
        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordingActivity", "Error registering broadcast receiver", e)
            // Continue without the receiver - the app will still work, just won't show exact file path
        }
    }

    override fun onPause() {
        super.onPause()
        RecorderLogger.methodEntry("ScreenRecordingActivity", "onPause")
        try {
            unregisterReceiver(recordingStoppedReceiver)
            RecorderLogger.d("ScreenRecordingActivity", "Successfully unregistered broadcast receiver")
        } catch (e: Exception) {
            // Receiver might not be registered
            RecorderLogger.e("ScreenRecordingActivity", "Error unregistering receiver", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
    }
}
