package com.example.transcriptapp.utils

import android.media.MediaCodec
import java.util.Locale

class RecordingStats(private val loggerTag: String) {

    private var videoFrameCount = 0
    private var videoFirstPtsUs = -1L
    private var videoLastPtsUs = -1L
    private var videoPtsOffsetUs = -1L
    private var videoFirstRawPtsUs = -1L

    private var audioFrameCount = 0
    private var audioFirstPtsUs = -1L
    private var audioLastPtsUs = -1L

    fun normaliseVideoPts(rawPtsUs: Long): Long {
        if (videoPtsOffsetUs < 0) {
            videoPtsOffsetUs = rawPtsUs
        }
        return rawPtsUs - videoPtsOffsetUs
    }

    fun onVideoSample(rawPtsUs: Long, normalisedPtsUs: Long, bufferInfo: MediaCodec.BufferInfo) {
        if (videoFirstPtsUs < 0) {
            videoFirstPtsUs = normalisedPtsUs
        }
        if (videoFirstRawPtsUs < 0) {
            videoFirstRawPtsUs = rawPtsUs
        }

        val index = ++videoFrameCount
        val delta = if (videoLastPtsUs >= 0) normalisedPtsUs - videoLastPtsUs else 0
        videoLastPtsUs = normalisedPtsUs

        if (delta > 60_000) {
            RecorderLogger.w(
                loggerTag,
                "Video cadence spike: frame#$index delta=${delta}us size=${bufferInfo.size} flags=${bufferInfo.flags}"
            )
        }
    }

    fun onAudioSample(bufferInfo: MediaCodec.BufferInfo) {
        val pts = bufferInfo.presentationTimeUs
        if (audioFirstPtsUs < 0) {
            audioFirstPtsUs = pts
        }

        val index = ++audioFrameCount
        val delta = if (audioLastPtsUs >= 0) pts - audioLastPtsUs else 0
        audioLastPtsUs = pts

        if (delta > 120_000) {
            RecorderLogger.w(
                loggerTag,
                "Audio cadence spike: buffer#$index delta=${delta}us size=${bufferInfo.size} flags=${bufferInfo.flags}"
            )
        }
    }

    fun summarise() {
        val videoDurationUs = if (videoFirstPtsUs >= 0 && videoLastPtsUs >= videoFirstPtsUs) {
            videoLastPtsUs - videoFirstPtsUs
        } else {
            -1
        }

        val audioDurationUs = if (audioFirstPtsUs >= 0 && audioLastPtsUs >= audioFirstPtsUs) {
            audioLastPtsUs - audioFirstPtsUs
        } else {
            -1
        }

        val videoFps = if (videoFrameCount > 1 && videoDurationUs > 0) {
            1_000_000f / (videoDurationUs / (videoFrameCount - 1).toFloat())
        } else 0f

        val audioRate = if (audioFrameCount > 1 && audioDurationUs > 0) {
            1_000_000f / (audioDurationUs / (audioFrameCount - 1).toFloat())
        } else 0f

        val summaryParts = mutableListOf<String>()
        if (videoFrameCount > 0) {
            summaryParts += "videoFrames=$videoFrameCount"
            if (videoDurationUs >= 0) {
                summaryParts += "videoMs=${videoDurationUs / 1000}"
            }
            if (videoFps > 0f) {
                summaryParts += String.format(Locale.US, "videoFps=%.2f", videoFps)
            }
        }
        if (audioFrameCount > 0) {
            summaryParts += "audioBuffers=$audioFrameCount"
            if (audioDurationUs >= 0) {
                summaryParts += "audioMs=${audioDurationUs / 1000}"
            }
            if (audioRate > 0f) {
                summaryParts += String.format(Locale.US, "audioRate=%.2f", audioRate)
            }
        }
        if (summaryParts.isNotEmpty()) {
            RecorderLogger.i(loggerTag, "Recording summary: ${summaryParts.joinToString(separator = " | ")}")
        }

        if (videoDurationUs >= 0 && audioDurationUs >= 0) {
            val skewMs = (videoDurationUs - audioDurationUs) / 1000
            if (kotlin.math.abs(skewMs) > 200) {
                RecorderLogger.w(loggerTag, "A/V duration skew=${skewMs}ms")
            }
        }
    }

    fun reset() {
        videoFrameCount = 0
        videoFirstPtsUs = -1L
        videoLastPtsUs = -1L
        videoPtsOffsetUs = -1L
        videoFirstRawPtsUs = -1L
        audioFrameCount = 0
        audioFirstPtsUs = -1L
        audioLastPtsUs = -1L
    }
}
