package com.example.transcriptapp.utils

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

class MuxerCoordinator(private val outputFile: File) {

    private var muxer: MediaMuxer? = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var started = false

    fun setVideoFormat(format: MediaFormat): Int = synchronized(lock) {
        videoTrackIndex = muxer!!.addTrack(format)
        startIfReady()
        videoTrackIndex
    }

    fun setAudioFormat(format: MediaFormat): Int = synchronized(lock) {
        audioTrackIndex = muxer!!.addTrack(format)
        startIfReady()
        audioTrackIndex
    }

    fun writeSampleVideo(encodedBuffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started) return
            if (videoTrackIndex >= 0) {
                muxer?.writeSampleData(videoTrackIndex, encodedBuffer, bufferInfo)
            }
        }
    }

    fun writeSampleAudio(encodedBuffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started) return
            if (audioTrackIndex >= 0) {
                muxer?.writeSampleData(audioTrackIndex, encodedBuffer, bufferInfo)
            }
        }
    }

    fun stopAndRelease() {
        synchronized(lock) {
            try {
                if (started) {
                    muxer?.stop()
                }
            } catch (_: Exception) {
            } finally {
                try { muxer?.release() } catch (_: Exception) {}
                muxer = null
                videoTrackIndex = -1
                audioTrackIndex = -1
                started = false
            }
        }
    }

    private fun startIfReady() {
        if (!started && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer?.start()
            started = true
        }
    }

    /**
     * Rotate muxer output to a new file without stopping encoders.
     * Caller must pass the cached encoder output formats.
     */
    fun rotateTo(newFile: File, videoFormat: MediaFormat, audioFormat: MediaFormat) {
        synchronized(lock) {
            // Close current file
            try {
                if (started) {
                    muxer?.stop()
                }
            } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}

            // Open new muxer
            muxer = MediaMuxer(newFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = muxer!!.addTrack(videoFormat)
            audioTrackIndex = muxer!!.addTrack(audioFormat)
            muxer!!.start()
            started = true
        }
    }

    fun isStarted(): Boolean = synchronized(lock) { started }
    fun hasMuxer(): Boolean = synchronized(lock) { muxer != null }
}
