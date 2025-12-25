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
import com.example.transcriptapp.utils.transcript.RealtimeTranscriptionManager
import com.example.transcriptapp.utils.RecorderLogger
import com.example.transcriptapp.repository.AuthRepository
import com.example.transcriptapp.repository.AuthRepositoryImpl

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OUTPUT_FILE_PATH = "output_file_path"
        const val EXTRA_STATE = "extra_state" // Added for state representation

        const val BROADCAST_RECORDING_STOPPED = "com.example.transcriptapp.RECORDING_STOPPED"
        const val BROADCAST_STATE = "com.example.transcriptapp.RECORDING_STATE" // Added for state broadcast

        private const val NOTIFICATION_CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var realtimeManager: RealtimeTranscriptionManager? = null
    private var isRecording = false

    private var projectionResultCode: Int = Activity.RESULT_CANCELED
    private var projectionResultData: Intent? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var authRepository: AuthRepository

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
        // Create AuthService first
        val authService = com.example.transcriptapp.service.AuthServiceImpl()
        // Then create AuthRepository with the service
        authRepository = AuthRepositoryImpl(this, authService)
        // Realtime transcription manager
        realtimeManager = RealtimeTranscriptionManager(this, authRepository)
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

                // Now that projection is ready, it's safe and expected to run as FGS
                try {
                    startForeground(NOTIFICATION_ID, createNotification("Recording…"))
                    RecorderLogger.service("ScreenRecordService", "FOREGROUND", "Service started in foreground")
                } catch (se: SecurityException) {
                    RecorderLogger.e("ScreenRecordService", "startForeground failed", se)
                }

            projectionResultCode = resultCode
            projectionResultData = Intent(resultData)

            // Get MediaProjection
            mediaProjection = acquireMediaProjection()

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

            val projection = acquireMediaProjection()
            registerMediaProjectionCallbackIfNeeded()
            val prefs = getSharedPreferences("realtime_prefs", MODE_PRIVATE)
            val commitStrategy = prefs.getString("commit_strategy", "vad") ?: "vad"
            val languageCode = prefs.getString("language_code", "") ?: ""
            val chunkMs = prefs.getInt("chunk_ms", 1000)
            val sampleRate = prefs.getInt("sample_rate", 48000)
            val vadThreshold = prefs.getFloat("vad_threshold", 0.7f)
            val minSpeechMs = prefs.getInt("min_speech_duration_ms", 60)
            val minSilenceMs = prefs.getInt("min_silence_duration_ms", 120)
            val vadSilenceSecs = prefs.getFloat("vad_silence_threshold_secs", 0.3f)

            RecorderLogger.d(
                "ScreenRecordService",
                "Starting realtime manager (projection=${projection.hashCode()}) commit=$commitStrategy vad=$vadThreshold minSpeech=$minSpeechMs minSilence=$minSilenceMs vadSilence=$vadSilenceSecs"
            )
            realtimeManager?.start(
                projection,
                languageCode = languageCode,
                chunkMs = chunkMs,
                sampleRate = sampleRate,
                commitStrategy = commitStrategy,
                vadThreshold = vadThreshold,
                minSpeechDurationMs = minSpeechMs,
                minSilenceDurationMs = minSilenceMs,
                vadSilenceThresholdSecs = vadSilenceSecs
            )
            isRecording = true

            RecorderLogger.media("ScreenRecordService", "START", "Realtime transcription started")
            updateNotification("Realtime transcript…")
            sendRecordingStateBroadcast("recording") // Thông báo trạng thái bắt đầu ghi

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

            stopRecordingInternal()
            sendRecordingStateBroadcast("stopped") // Thông báo trạng thái dừng ghi
        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Error stopping recording", e)
            cleanup()
            stopSelf()
        }
    }

    private fun stopRecordingInternal(
        restartAfterStop: Boolean = false,
        stopMediaProjection: Boolean = true
    ) {
        try {
            RecorderLogger.methodEntry(
                "ScreenRecordService",
                "stopRecordingInternal",
                "restartAfterStop" to restartAfterStop
            )

            if (!isRecording) {
                RecorderLogger.w("ScreenRecordService", "Stop requested with no active recording")
                cleanup(stopMediaProjection = stopMediaProjection)
                isRecording = false
                if (!restartAfterStop) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    RecorderLogger.service("ScreenRecordService", "BACKGROUND", "Service stopped foreground state (no active recording)")
                }
                if (!restartAfterStop) {
                    stopSelf()
                }
                return
            }
            if (!stopMediaProjection) {
                unregisterMediaProjectionCallbackIfNeeded()
            }

            cleanup(stopMediaProjection = stopMediaProjection)
            isRecording = false

            if (!restartAfterStop) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                RecorderLogger.service("ScreenRecordService", "BACKGROUND", "Service stopped foreground state")
                RecorderLogger.methodExit("ScreenRecordService", "stopRecordingInternal", "success")
                sendRecordingStateBroadcast("stopped") // Thông báo trạng thái dừng ghi
                stopSelf()
            } else {
                RecorderLogger.methodExit("ScreenRecordService", "stopRecordingInternal", "restart_pending")
                sendRecordingStateBroadcast("recording") // Nếu restart thì báo lại trạng thái đang ghi
            }

        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Error in stopRecordingInternal", e)
            cleanup()
            stopSelf()
        }
    }

    private fun cleanup(stopMediaProjection: Boolean = true) {
        try {
            RecorderLogger.d("ScreenRecordService", "Cleaning up resources")

            mainHandler.removeCallbacksAndMessages(null)

            realtimeManager?.stop()

            if (stopMediaProjection) {
                unregisterMediaProjectionCallbackIfNeeded()
                mediaProjection?.stop()
                mediaProjection = null
            }

            RecorderLogger.d("ScreenRecordService", "Resources cleaned up")
        } catch (e: Exception) {
            RecorderLogger.e("ScreenRecordService", "Error during cleanup", e)
        }
    }

    private fun acquireMediaProjection(): MediaProjection {
        mediaProjection?.let { return it }

        if (projectionResultCode != Activity.RESULT_OK) {
            throw IllegalStateException("MediaProjection not available: result code=$projectionResultCode")
        }

        val tokenIntent = projectionResultData
            ?: throw IllegalStateException("MediaProjection token missing")

        val projection = mediaProjectionManager.getMediaProjection(projectionResultCode, Intent(tokenIntent))
            ?: throw IllegalStateException("Failed to obtain MediaProjection")

        mediaProjection = projection
        mediaProjectionCallbackRegistered = false

        return projection
    }

    private fun unregisterMediaProjectionCallbackIfNeeded() {
        if (mediaProjectionCallbackRegistered) {
            runCatching {
                mediaProjection?.unregisterCallback(mediaProjectionCallback)
            }.onFailure {
                RecorderLogger.e("ScreenRecordService", "Error unregistering MediaProjection callback", it)
            }
            mediaProjectionCallbackRegistered = false
            RecorderLogger.d("ScreenRecordService", "MediaProjection callback unregistered")
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

    private fun sendRecordingStateBroadcast(state: String) {
        val intent = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE, state)
            setPackage(packageName)
        }
        sendBroadcast(intent)
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
