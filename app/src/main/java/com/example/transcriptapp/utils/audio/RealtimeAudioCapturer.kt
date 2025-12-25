package com.example.transcriptapp.utils.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.example.transcriptapp.utils.RecorderLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RealtimeAudioCapturer(
    val mediaProjection: MediaProjection,
    private val sampleRate: Int = 16000,
    private val onAudioData: (ByteArray, Int) -> Unit
) {
    private val loggerTag = "RealtimeAudioCapturer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    private var running = false
    private var readCount = 0
    private var totalBytes = 0L

    fun start() {
        if (running) return
        running = true

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .build()

        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize * 2).coerceAtLeast(sampleRate / 2)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            RecorderLogger.e(loggerTag, "AudioRecord init failed (state=${audioRecord?.state})")
            running = false
            return
        }

        job = scope.launch {
            val buffer = ByteArray(bufferSize)
            audioRecord?.startRecording()
            RecorderLogger.d(loggerTag, "Audio capture started (bufferSize=$bufferSize, sampleRate=$sampleRate)")

            while (running) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    readCount += 1
                    totalBytes += read
                    if (readCount <= 5 || readCount % 50 == 0) {
                        RecorderLogger.d(loggerTag, "Audio read #$readCount bytes=$read total=$totalBytes")
                    }
                    onAudioData(buffer, read)
                } else if (read < 0) {
                    RecorderLogger.w(loggerTag, "Audio read error code=$read")
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
        audioRecord?.release()
        audioRecord = null
        job?.cancel()
        job = null
        scope.cancel()
        RecorderLogger.d(loggerTag, "Audio capture stopped (reads=$readCount bytes=$totalBytes)")
    }
}
