package com.example.transcriptapp.service.realtime

import com.example.transcriptapp.utils.RecorderLogger
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RealtimeTranscriptClient(
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onSessionStarted()
        fun onPartial(text: String)
        fun onCommitted(text: String)
        fun onQuotaExceeded(message: String?)
        fun onError(message: String, throwable: Throwable? = null)
        fun onClosed()
    }

    private val loggerTag = "RealtimeTranscriptClient"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var sessionStarted = false
    private var messageCount = 0
    private var partialCount = 0
    private var committedCount = 0

    fun connect(wsUrl: String, apiKey: String) {
        if (socket != null) {
            RecorderLogger.w(loggerTag, "connect ignored: socket already active")
            return
        }

        RecorderLogger.i(loggerTag, "Connecting to $wsUrl")
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("xi-api-key", apiKey)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                RecorderLogger.d(loggerTag, "WebSocket opened (code=${response.code})")
                sessionStarted = false
                RecorderLogger.d(loggerTag, "WebSocket authenticated via header (no handshake)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val codeInfo = response?.code?.let { " code=$it" } ?: ""
                RecorderLogger.e(loggerTag, "WebSocket failure$codeInfo", t)
                callbacks.onError("WebSocket error", t)
                socket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                RecorderLogger.d(loggerTag, "WebSocket closed: $code $reason")
                callbacks.onClosed()
                socket = null
            }
        })
    }

    fun sendAudioChunk(base64Pcm: String, sampleRate: Int, commit: Boolean = false) {
        val payload = mapOf(
            "message_type" to "input_audio_chunk",
            "audio_base_64" to base64Pcm,
            "commit" to commit,
            "sample_rate" to sampleRate
        )
        socket?.send(gson.toJson(payload))
    }

    fun sendCommit(sampleRate: Int) {
        sendAudioChunk("", sampleRate, true)
    }

    fun close() {
        socket?.close(1000, "client_close")
        socket = null
    }

    private fun handleMessage(raw: String) {
        try {
            val payload = JSONObject(raw)
            val messageType = payload.optString("message_type", payload.optString("type", ""))
            messageCount += 1
            if (messageCount <= 5 || messageCount % 25 == 0) {
                RecorderLogger.d(loggerTag, "WS message #$messageCount type=$messageType")
            }

            if (messageType == "session_started") {
                if (!sessionStarted) {
                    sessionStarted = true
                    callbacks.onSessionStarted()
                }
                return
            }

            if (messageType == "quota_exceeded") {
                callbacks.onQuotaExceeded(payload.optString("error"))
                return
            }

            val text = extractTranscriptText(payload)
            if (text.isNullOrBlank()) return

            when {
                messageType == "partial_transcript" || payload.has("partial_transcript") -> {
                    partialCount += 1
                    if (partialCount <= 5 || partialCount % 20 == 0) {
                        RecorderLogger.d(loggerTag, "Partial #$partialCount len=${text.length}")
                    }
                    callbacks.onPartial(text)
                }
                messageType == "committed_transcript" || payload.has("committed_transcript") -> {
                    committedCount += 1
                    RecorderLogger.d(loggerTag, "Committed #$committedCount len=${text.length}")
                    callbacks.onCommitted(text)
                }
                else -> callbacks.onPartial(text)
            }
        } catch (t: Throwable) {
            RecorderLogger.e(loggerTag, "Failed to parse message", t)
        }
    }

    private fun extractTranscriptText(payload: JSONObject): String? {
        return when {
            payload.has("text") -> payload.optString("text")
            payload.has("transcript") -> payload.optString("transcript")
            payload.has("committed_transcript") -> payload.optString("committed_transcript")
            payload.has("partial_transcript") -> payload.optString("partial_transcript")
            else -> null
        }
    }
}
