package com.example.transcriptapp.utils.transcript

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.example.transcriptapp.overlay.SubtitleOverlayService
import com.example.transcriptapp.repository.AuthRepository
import com.example.transcriptapp.service.realtime.RealtimeTokenService
import com.example.transcriptapp.service.realtime.RealtimeTranscriptClient
import com.example.transcriptapp.utils.ApiConfig
import com.example.transcriptapp.utils.RecorderLogger
import com.example.transcriptapp.utils.audio.RealtimeAudioCapturer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.net.Uri
import android.util.Base64

class RealtimeTranscriptionManager(
    private val context: Context,
    private val authRepository: AuthRepository
) {
    private val loggerTag = "RealtimeTranscriptionManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenService = RealtimeTokenService(authRepository)

    private var client: RealtimeTranscriptClient? = null
    private var audioCapturer: RealtimeAudioCapturer? = null
    private var apiKey: String? = null
    private var sessionStarted = false
    private var sentAnyChunk = false

    private var chunkSizeBytes = 0
    private var buffer = ByteArray(0)
    private var bufferOffset = 0
    private var audioCallbackCount = 0
    private var audioBytesTotal = 0L
    private var chunkSentCount = 0
    private var skipBeforeSessionCount = 0

    private var runningJob: Job? = null
    private var reconnectJob: Job? = null
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var reconnectDelayMs = 1000L
    private var lastStartConfig: StartConfig? = null

    private data class StartConfig(
        val chunkMs: Int,
        val languageCode: String?,
        val sampleRate: Int,
        val commitStrategy: String,
        val vadThreshold: Float,
        val minSpeechDurationMs: Int,
        val minSilenceDurationMs: Int,
        val vadSilenceThresholdSecs: Float
    )

    fun start(
        mediaProjection: MediaProjection,
        chunkMs: Int = 1000,
        languageCode: String? = null,
        sampleRate: Int = 48000,
        commitStrategy: String = "vad",
        vadThreshold: Float = 0.7f,
        minSpeechDurationMs: Int = 60,
        minSilenceDurationMs: Int = 120,
        vadSilenceThresholdSecs: Float = 0.3f
    ) {
        if (runningJob != null) return

        lastStartConfig = StartConfig(
            chunkMs = chunkMs,
            languageCode = languageCode,
            sampleRate = sampleRate,
            commitStrategy = commitStrategy,
            vadThreshold = vadThreshold,
            minSpeechDurationMs = minSpeechDurationMs,
            minSilenceDurationMs = minSilenceDurationMs,
            vadSilenceThresholdSecs = vadSilenceThresholdSecs
        )
        shouldReconnect = true
        reconnectAttempts = 0
        reconnectDelayMs = 1000L

        runningJob = scope.launch {
            RecorderLogger.i(
                loggerTag,
                "Start realtime: chunkMs=$chunkMs sampleRate=$sampleRate language=${languageCode ?: "auto"} commit=$commitStrategy vad=$vadThreshold minSpeech=$minSpeechDurationMs minSilence=$minSilenceDurationMs vadSilence=$vadSilenceThresholdSecs"
            )
            apiKey = tokenService.fetchApiKey()
            if (apiKey.isNullOrBlank()) {
                RecorderLogger.e(loggerTag, "Cannot start realtime: missing xi-api-key")
                runningJob = null
                return@launch
            }
            RecorderLogger.d(loggerTag, "Fetched xi-api-key (len=${apiKey?.length ?: 0})")

            val wsUrl = buildWsUrl(
                ApiConfig.REALTIME_TRANSCRIPT_WS,
                buildMap {
                    put("language_code", languageCode ?: "")
                    put("commit_strategy", commitStrategy)
                    if (commitStrategy == "vad") {
                        put("vad_threshold", vadThreshold.toString())
                        put("min_speech_duration_ms", minSpeechDurationMs.toString())
                        put("min_silence_duration_ms", minSilenceDurationMs.toString())
                        put("vad_silence_threshold_secs", vadSilenceThresholdSecs.toString())
                    }
                }
            )
            RecorderLogger.d(loggerTag, "WS URL: $wsUrl")

            chunkSizeBytes = (sampleRate * chunkMs / 1000) * 2
            buffer = ByteArray(chunkSizeBytes * 2)
            bufferOffset = 0
            sentAnyChunk = false
            sessionStarted = false
            audioCallbackCount = 0
            audioBytesTotal = 0
            chunkSentCount = 0
            skipBeforeSessionCount = 0
            RecorderLogger.d(
                loggerTag,
                "Audio config: sampleRate=$sampleRate chunkBytes=$chunkSizeBytes bufferCap=${buffer.size}"
            )

            client = RealtimeTranscriptClient(object : RealtimeTranscriptClient.Callbacks {
                override fun onSessionStarted() {
                    sessionStarted = true
                    RecorderLogger.d(loggerTag, "Session started (ready to stream)")
                    reconnectAttempts = 0
                    reconnectDelayMs = 1000L
                }

                override fun onPartial(text: String) {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        showSubtitle(trimmed, true)
                    }
                }

                override fun onCommitted(text: String) {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        showSubtitle(trimmed, false)
                    }
                }

                override fun onQuotaExceeded(message: String?) {
                    RecorderLogger.e(loggerTag, "Quota exceeded: $message")
                }

                override fun onError(message: String, throwable: Throwable?) {
                    if (throwable != null) {
                        RecorderLogger.e(loggerTag, message, throwable)
                    } else {
                        RecorderLogger.e(loggerTag, message)
                    }
                    scheduleReconnectIfNeeded()
                }

                override fun onClosed() {
                    RecorderLogger.d(loggerTag, "WebSocket closed")
                    scheduleReconnectIfNeeded()
                }
            }).also { it.connect(wsUrl, apiKey!!) }

            audioCapturer = RealtimeAudioCapturer(mediaProjection, sampleRate) { data, length ->
                handleAudioData(data, length, sampleRate)
            }.also { it.start() }
            RecorderLogger.i(loggerTag, "Realtime audio capture started")
        }
    }

    fun stop() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        RecorderLogger.i(
            loggerTag,
            "Stop realtime: callbacks=$audioCallbackCount bytes=$audioBytesTotal chunks=$chunkSentCount buffered=$bufferOffset"
        )
        audioCapturer?.stop()
        audioCapturer = null
        if (sentAnyChunk) {
            RecorderLogger.d(loggerTag, "Sending final commit")
            client?.sendCommit(lastStartConfig?.sampleRate ?: 48000)
        }
        client?.close()
        client = null
        apiKey?.let { scope.launch { tokenService.notifyTokenUsage(it) } }
        apiKey = null
        runningJob?.cancel()
        runningJob = null
        bufferOffset = 0
        sentAnyChunk = false
    }

    private fun handleAudioData(data: ByteArray, length: Int, sampleRate: Int) {
        audioCallbackCount += 1
        if (!sessionStarted) {
            skipBeforeSessionCount += 1
            if (skipBeforeSessionCount <= 3 || skipBeforeSessionCount % 50 == 0) {
                RecorderLogger.w(
                    loggerTag,
                    "Audio received before session started: skip=$skipBeforeSessionCount len=$length"
                )
            }
            return
        }
        if (chunkSizeBytes <= 0 || length <= 0) return
        audioBytesTotal += length

        // Ensure buffer capacity
        if (bufferOffset + length > buffer.size) {
            val newBuffer = ByteArray(buffer.size + chunkSizeBytes)
            System.arraycopy(buffer, 0, newBuffer, 0, bufferOffset)
            buffer = newBuffer
            RecorderLogger.d(loggerTag, "Expanded audio buffer to ${buffer.size} bytes")
        }

        System.arraycopy(data, 0, buffer, bufferOffset, length)
        bufferOffset += length

        while (bufferOffset >= chunkSizeBytes) {
            val chunk = buffer.copyOfRange(0, chunkSizeBytes)
            // shift remaining
            val remaining = bufferOffset - chunkSizeBytes
            if (remaining > 0) {
                System.arraycopy(buffer, chunkSizeBytes, buffer, 0, remaining)
            }
            bufferOffset = remaining

            val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
            client?.sendAudioChunk(base64, sampleRate, false)
            sentAnyChunk = true
            chunkSentCount += 1
            if (chunkSentCount <= 3 || chunkSentCount % 25 == 0) {
                RecorderLogger.d(
                    loggerTag,
                    "Sent audio chunk #$chunkSentCount (bytes=$chunkSizeBytes b64len=${base64.length})"
                )
            }
        }
    }

    private fun showSubtitle(text: String, isPartial: Boolean) {
        RecorderLogger.d(loggerTag, "Show subtitle len=${text.length}")
        val serviceIntent = Intent(context, SubtitleOverlayService::class.java).apply {
            action = SubtitleOverlayService.ACTION_SHOW_SUBTITLE
            putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, text)
            putExtra(SubtitleOverlayService.EXTRA_IS_PARTIAL, isPartial)
        }
        context.startService(serviceIntent)

        val broadcastIntent = Intent(SubtitleOverlayService.ACTION_SHOW_SUBTITLE).apply {
            putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, text)
            putExtra(SubtitleOverlayService.EXTRA_IS_PARTIAL, isPartial)
        }
        context.sendBroadcast(broadcastIntent)
    }

    private fun buildWsUrl(base: String, extraParams: Map<String, String>): String {
        if (extraParams.isEmpty()) return base
        return try {
            val uri = Uri.parse(base)
            val builder = uri.buildUpon()
            extraParams.forEach { (key, value) ->
                if (value.isNotBlank() && uri.getQueryParameter(key) == null) {
                    builder.appendQueryParameter(key, value)
                }
            }
            builder.build().toString()
        } catch (t: Throwable) {
            RecorderLogger.e(loggerTag, "Failed to append WS params", t)
            base
        }
    }

    private fun scheduleReconnectIfNeeded() {
        if (!shouldReconnect) return
        if (reconnectJob?.isActive == true) return
        val config = lastStartConfig ?: return
        reconnectAttempts += 1
        val delay = reconnectDelayMs.coerceAtMost(15000L)
        RecorderLogger.w(loggerTag, "Scheduling reconnect #$reconnectAttempts in ${delay}ms")
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(delay)
            if (!shouldReconnect) return@launch
            RecorderLogger.w(loggerTag, "Reconnecting…")
            try {
                client?.close()
            } catch (_: Throwable) {}
            client = null
            sessionStarted = false

            // Reconnect socket only; keep current audio capturer running
            apiKey = tokenService.fetchApiKey() ?: apiKey
            if (apiKey.isNullOrBlank()) {
                RecorderLogger.e(loggerTag, "Reconnect failed: missing xi-api-key")
                return@launch
            }

            val wsUrl = buildWsUrl(
                ApiConfig.REALTIME_TRANSCRIPT_WS,
                buildMap {
                    put("language_code", config.languageCode ?: "")
                    put("commit_strategy", config.commitStrategy)
                    if (config.commitStrategy == "vad") {
                        put("vad_threshold", config.vadThreshold.toString())
                        put("min_speech_duration_ms", config.minSpeechDurationMs.toString())
                        put("min_silence_duration_ms", config.minSilenceDurationMs.toString())
                        put("vad_silence_threshold_secs", config.vadSilenceThresholdSecs.toString())
                    }
                }
            )

            client = RealtimeTranscriptClient(object : RealtimeTranscriptClient.Callbacks {
                override fun onSessionStarted() {
                    sessionStarted = true
                    RecorderLogger.d(loggerTag, "Session started (ready to stream)")
                    reconnectAttempts = 0
                    reconnectDelayMs = 1000L
                }

                override fun onPartial(text: String) {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        showSubtitle(trimmed, true)
                    }
                }

                override fun onCommitted(text: String) {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        showSubtitle(trimmed, false)
                    }
                }

                override fun onQuotaExceeded(message: String?) {
                    RecorderLogger.e(loggerTag, "Quota exceeded: $message")
                }

                override fun onError(message: String, throwable: Throwable?) {
                    if (throwable != null) {
                        RecorderLogger.e(loggerTag, message, throwable)
                    } else {
                        RecorderLogger.e(loggerTag, message)
                    }
                    scheduleReconnectIfNeeded()
                }

                override fun onClosed() {
                    RecorderLogger.d(loggerTag, "WebSocket closed")
                    scheduleReconnectIfNeeded()
                }
            }).also { it.connect(wsUrl, apiKey!!) }
        }
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15000L)
    }
}
