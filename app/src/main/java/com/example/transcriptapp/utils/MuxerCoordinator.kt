package com.example.transcriptapp.utils

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

class MuxerCoordinator(outputFile: File, private val loggerTag: String) {

    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var started = false

    fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        videoTrackIndex = muxer.addTrack(format)
        logFormat("Video", format)
        startIfReady()
        videoTrackIndex
    }

    fun addAudioTrack(format: MediaFormat): Int = synchronized(lock) {
        audioTrackIndex = muxer.addTrack(format)
        logFormat("Audio", format)
        startIfReady()
        audioTrackIndex
    }

    fun writeSample(trackType: TrackType, encodedBuffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started) return
            val trackIndex = when (trackType) {
                TrackType.VIDEO -> videoTrackIndex
                TrackType.AUDIO -> audioTrackIndex
            }
            if (trackIndex >= 0) {
                muxer.writeSampleData(trackIndex, encodedBuffer, bufferInfo)
            }
        }
    }

    fun stopAndRelease() {
        synchronized(lock) {
            runCatching {
                if (started) {
                    muxer.stop()
                }
            }.onFailure { RecorderLogger.e(loggerTag, "Error stopping muxer", it) }
            runCatching { muxer.release() }
                .onFailure { RecorderLogger.e(loggerTag, "Error releasing muxer", it) }
            started = false
            videoTrackIndex = -1
            audioTrackIndex = -1
        }
    }

    private fun startIfReady() {
        if (!started && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer.start()
            started = true
            RecorderLogger.d(loggerTag, "MediaMuxer started with tracks video=$videoTrackIndex audio=$audioTrackIndex")
        }
    }

    private fun logFormat(prefix: String, format: MediaFormat) {
        val mime = safeGetString(format, MediaFormat.KEY_MIME)
        val width = safeGetInt(format, MediaFormat.KEY_WIDTH)
        val height = safeGetInt(format, MediaFormat.KEY_HEIGHT)
        val frameRate = safeGetInt(format, MediaFormat.KEY_FRAME_RATE)
        val bitRate = safeGetInt(format, MediaFormat.KEY_BIT_RATE)
        RecorderLogger.d(
            loggerTag,
            "$prefix encoder output: ${mime ?: ""} ${dimension(width, height)} fps=${frameRate ?: -1} bitrate=${bitRate ?: -1}"
        )
    }

    private fun dimension(width: Int?, height: Int?): String =
        if (width != null && height != null) "${width}x$height" else "unknown"

    private fun safeGetInt(format: MediaFormat, key: String): Int? =
        if (format.containsKey(key)) format.getInteger(key) else null

    private fun safeGetString(format: MediaFormat, key: String): String? =
        if (format.containsKey(key)) format.getString(key) else null

    enum class TrackType { VIDEO, AUDIO }
}
