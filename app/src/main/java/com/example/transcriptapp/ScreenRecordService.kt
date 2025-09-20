package com.example.transcriptapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.transcriptapp.utils.RecordingConfig
import com.example.transcriptapp.utils.RecordingConfigFactory
import com.example.transcriptapp.utils.RecordingFileManager
import java.io.File
import com.example.transcriptapp.utils.ProjectionRecorder
import com.example.transcriptapp.utils.RecorderLogger

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OUTPUT_FILE_PATH = "output_file_path"

        const val BROADCAST_RECORDING_STOPPED = "com.example.transcriptapp.RECORDING_STOPPED"

        private const val NOTIFICATION_CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var projectionRecorder: ProjectionRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var recordingStartTime: Long = 0
    private var recordingDuration: Long = 0

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var recordingFileManager: RecordingFileManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            RecorderLogger.media("ScreenRecordService", "MEDIA_PROJECTION_STOP", "MediaProjection onStop received; stopping recording")
            mainHandler.post {
                if (isRecording) {
                    stopRecordingInternal()
                } else {
                    cleanup()
                }
            }
        }
    }
    private var mediaProjectionCallbackRegistered = false

    override fun onCreate() {
        super.onCreate()
        RecorderLogger.service("ScreenRecordService", "CREATE", "Service created")
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        recordingFileManager = RecordingFileManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        RecorderLogger.service("ScreenRecordService", "START_COMMAND", "action=$action")

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    RecorderLogger.e("ScreenRecordService", "Invalid start parameters: resultCode=$resultCode, resultData=$resultData")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startRecording(resultCode, resultData)
            }
            ACTION_STOP -> stopRecording()
        }

        return START_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        try {
            RecorderLogger.media("ScreenRecordService", "START", "Initiating recording")

            if (isRecording) {
                RecorderLogger.w("ScreenRecordService", "Recording already in progress")
                return
            }

            // Check POST_NOTIFICATIONS permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    RecorderLogger.w("ScreenRecordService", "POST_NOTIFICATIONS permission not granted")
                    // For now, we'll continue without notifications
                    // In a real app, you'd want to request this permission from the activity
                }
            }

            // Create notification channel
            createNotificationChannel()

            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification("Starting recording..."))
            RecorderLogger.service("ScreenRecordService", "FOREGROUND", "Service started in foreground")

            // Get MediaProjection
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                throw Exception("Failed to get MediaProjection")
            }

            // Start the recording process
            startRecordingInternal()

        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Failed to start recording", e)
            stopSelf()
        }
    }

    private fun startRecordingInternal() {
        try {
            RecorderLogger.methodEntry("ScreenRecordService", "startRecordingInternal")

            val projection = mediaProjection
                ?: throw IllegalStateException("MediaProjection not available")

            val config = RecordingConfigFactory.create(resources.displayMetrics)
            logRecordingConfig(config)

            outputFile = recordingFileManager.createOutputFile()

            registerMediaProjectionCallbackIfNeeded()

            projectionRecorder = ProjectionRecorder(
                mediaProjection = projection,
                recordingConfig = config,
                outputFile = outputFile ?: throw IllegalStateException("Output file missing")
            ).also { recorder ->
                recorder.start()
            }

            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            RecorderLogger.media("ScreenRecordService", "START", "Recording started with system audio only")
            updateNotification("Recording system audio…")

        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Failed to start recording internal", e)
            cleanup()
            stopSelf()
        }
    }


    private fun registerMediaProjectionCallbackIfNeeded() {
        if (!mediaProjectionCallbackRegistered) {
            mediaProjection?.registerCallback(mediaProjectionCallback, mainHandler)
            mediaProjectionCallbackRegistered = true
            RecorderLogger.d("ScreenRecordService", "MediaProjection callback registered")
        }
    }

    private fun stopRecording() {
        try {
            RecorderLogger.media("ScreenRecordService", "STOP", "Stopping recording")

            if (!isRecording) {
                RecorderLogger.w("ScreenRecordService", "No active recording to stop")
                return
            }

            // Check minimum recording time
            val currentTime = System.currentTimeMillis()
            val elapsedTime = currentTime - recordingStartTime

            if (elapsedTime < 2000) { // 2 seconds minimum
                RecorderLogger.w("ScreenRecordService", "Recording too short (${elapsedTime}ms), delaying stop")
                mainHandler.postDelayed({
                    stopRecordingInternal()
                }, 2000 - elapsedTime)
                return
            }

            stopRecordingInternal()

        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Error stopping recording", e)
            cleanup()
            stopSelf()
        }
    }

    private fun stopRecordingInternal() {
        try {
            RecorderLogger.methodEntry("ScreenRecordService", "stopRecordingInternal")

            if (!isRecording) {
                RecorderLogger.w("ScreenRecordService", "Stop requested with no active recording")
                cleanup()
                stopSelf()
                return
            }

            val finalOutput = outputFile
            if (finalOutput == null) {
                RecorderLogger.e("ScreenRecordService", "Output file reference missing")
                cleanup()
                stopSelf()
                return
            }

            projectionRecorder?.runCatching { stop() }
                ?.onFailure { RecorderLogger.e("ScreenRecordService", "Error stopping ProjectionRecorder", it) }
            projectionRecorder = null

            cleanup()

            if (finalOutput.exists() && finalOutput.length() > 0) {
                recordingDuration = System.currentTimeMillis() - recordingStartTime
                RecorderLogger.file("ScreenRecordService", "VERIFY", finalOutput.absolutePath, finalOutput.length())
                RecorderLogger.media("ScreenRecordService", "STOP", "Recording completed successfully (${recordingDuration}ms)")
                recordingFileManager.logMetadata(finalOutput, recordingDuration)
                sendRecordingStoppedBroadcast(finalOutput.absolutePath)
            } else {
                RecorderLogger.e("ScreenRecordService", "Recording file is empty or missing at ${finalOutput.absolutePath}")
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            RecorderLogger.service("ScreenRecordService", "BACKGROUND", "Service stopped foreground state")

            isRecording = false

            RecorderLogger.methodExit("ScreenRecordService", "stopRecordingInternal", "success")
            stopSelf()

        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Error in stopRecordingInternal", e)
            cleanup()
            stopSelf()
        }
    }

    private fun cleanup() {
        try {
            RecorderLogger.d("ScreenRecordService", "Cleaning up resources")

            mainHandler.removeCallbacksAndMessages(null)

            projectionRecorder?.runCatching { stop() }
                ?.onFailure { RecorderLogger.e("ScreenRecordService", "Error stopping recorder during cleanup", it) }
            projectionRecorder = null

            if (mediaProjectionCallbackRegistered) {
                mediaProjection?.unregisterCallback(mediaProjectionCallback)
                mediaProjectionCallbackRegistered = false
                RecorderLogger.d("ScreenRecordService", "MediaProjection callback unregistered")
            }

            mediaProjection?.stop()
            mediaProjection = null

            RecorderLogger.d("ScreenRecordService", "Resources cleaned up")
        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Error during cleanup", e)
        }
    }

    private fun sendRecordingStoppedBroadcast(filePath: String) {
        try {
            val intent = Intent(BROADCAST_RECORDING_STOPPED).apply {
                putExtra(EXTRA_OUTPUT_FILE_PATH, filePath)
                setPackage(packageName) // Security: only deliver to our app
            }
            sendBroadcast(intent)
            RecorderLogger.broadcast("ScreenRecordService", "RECORDING_STOPPED", mapOf("filePath" to filePath))
        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Error sending broadcast", e)
        }
    }

    private fun logRecordingConfig(config: RecordingConfig) {
        RecorderLogger.d(
            "ScreenRecordService",
            "Original screen dimensions: ${config.screen.widthPx} x ${config.screen.heightPx}, DPI: ${config.screen.densityDpi}"
        )
        RecorderLogger.d(
            "ScreenRecordService",
            "Scaled dimensions: ${config.dimensions.widthPx} x ${config.dimensions.heightPx}"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen recording notifications"
            }
            notificationManager.createNotificationChannel(channel)
            RecorderLogger.d("ScreenRecordService", "Notification channel created: $NOTIFICATION_CHANNEL_ID")
        }
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, ScreenRecordingActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen Recording")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        // Check POST_NOTIFICATIONS permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                RecorderLogger.w("ScreenRecordService", "Cannot update notification: POST_NOTIFICATIONS permission not granted")
                return
            }
        }

        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()
        RecorderLogger.service("ScreenRecordService", "DESTROY", "Service destroyed")
        cleanup()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
