package com.example.transcriptapp.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingFileManager(private val context: Context) {

    fun createOutputFile(prefix: String = DEFAULT_PREFIX): File {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val timestamp = SimpleDateFormat(FILE_NAME_PATTERN, Locale.getDefault()).format(Date())
        val file = File(moviesDir, "${prefix}_${timestamp}.mp4")
        RecorderLogger.file(FILE_LOG_COMPONENT, "CREATE", file.absolutePath)
        return file
    }

    fun logMetadata(file: File, expectedDurationMs: Long) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)

            val issues = mutableListOf<String>()
            val metadataParts = mutableListOf<String>()
            durationMs?.let { metadataParts += "durationMs=$it" } ?: issues.add("durationMissing")
            width?.let { metadataParts += "width=$it" }
            height?.let { metadataParts += "height=$it" }
            rotation?.let { metadataParts += "rotation=$it" }
            bitrate?.let { metadataParts += "bitrate=$it" }
            captureFps?.let { metadataParts += "captureFps=$it" }
            hasAudio?.let { metadataParts += "hasAudio=$it" }
            hasVideo?.let { metadataParts += "hasVideo=$it" }

            durationMs?.let { recorded ->
                val skew = recorded - expectedDurationMs
                if (kotlin.math.abs(skew) > 1_000) {
                    issues += "durationSkew=${skew}ms"
                }
            }

            if (hasAudio == "no") {
                issues += "missingAudioTrack"
            }
            if (hasVideo == "no") {
                issues += "missingVideoTrack"
            }

            if (issues.isNotEmpty()) {
                val payload = buildString {
                    if (metadataParts.isNotEmpty()) {
                        append(metadataParts.joinToString())
                        append(" | ")
                    }
                    append("issues=${issues.joinToString()}")
                }
                RecorderLogger.w(FILE_LOG_COMPONENT, "Output verification: $payload")
            }
        } catch (t: Throwable) {
            RecorderLogger.e(FILE_LOG_COMPONENT, "Failed to read output metadata", t)
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val DEFAULT_PREFIX = "ScreenRecording"
        private const val FILE_NAME_PATTERN = "yyyyMMdd_HHmmss"
        private const val FILE_LOG_COMPONENT = "ScreenRecordService"
    }
}
