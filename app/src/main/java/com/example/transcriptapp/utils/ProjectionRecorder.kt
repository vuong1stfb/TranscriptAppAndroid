package com.example.transcriptapp.utils

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screen + system audio recorder built on top of MediaCodec/MediaMuxer to avoid microphone capture.
 */
class ProjectionRecorder(
    private val mediaProjection: MediaProjection,
    private val recordingConfig: RecordingConfig,
    private val muxerCoordinator: MuxerCoordinator,
    private val outputFile: File,
    private val logger: (String) -> Unit = { RecorderLogger.d("ProjectionRecorder", it) }
) {

    private val recorderLogger = RecorderLogger
    private val loggerTag = "ProjectionRecorder"

    private val recording = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableListOf<Job>()

    private val videoWidth = recordingConfig.dimensions.widthPx
    private val videoHeight = recordingConfig.dimensions.heightPx
    private val densityDpi = recordingConfig.screen.densityDpi

    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    // Cached formats to allow muxer rotation
    @Volatile private var cachedVideoFormat: MediaFormat? = null
    @Volatile private var cachedAudioFormat: MediaFormat? = null

    private val recordingStats = RecordingStats("ProjectionRecorder")

    private val videoBufferInfo = MediaCodec.BufferInfo()
    private val audioBufferInfo = MediaCodec.BufferInfo()

    private val audioSampleRate = 44100
    private val audioChannelCount = 2
    private val audioChannelMask = AudioFormat.CHANNEL_IN_STEREO
    private val audioBytesPerFrame = 2 * audioChannelCount // 16-bit PCM

    private val dequeueTimeoutUs = 10_000L

    private var audioPresentationTimeUs = 0L

    fun start() {
        recorderLogger.methodEntry(
            loggerTag,
            "start",
            "width" to videoWidth,
            "height" to videoHeight,
            "densityDpi" to densityDpi,
            "output" to outputFile.absolutePath
        )
        if (recording.get()) {
            recorderLogger.w(loggerTag, "Recorder already running")
            return
        }

        outputFile.parentFile?.mkdirs()

        recordingStats.reset()
        audioPresentationTimeUs = 0L

        prepareEncoders()

        startVirtualDisplay()

        recording.set(true)
        jobs += scope.launch { drainVideoEncoderLoop() }
        jobs += scope.launch { captureAndEncodeAudioLoop() }
    }

    fun stop() {
        recorderLogger.methodEntry(loggerTag, "stop")
        if (!recording.getAndSet(false)) {
            recorderLogger.w(loggerTag, "Recorder not running")
            return
        }

        try {
            audioRecord?.runCatching { stop() }?.onFailure {
                recorderLogger.e(loggerTag, "Error stopping AudioRecord", it)
            }

            videoEncoder?.runCatching { signalEndOfInputStream() }?.onFailure {
                recorderLogger.e(loggerTag, "Error signaling video EOS", it)
            }

            runBlocking {
                jobs.forEach { job ->
                    try {
                        job.join()
                    } catch (e: Exception) {
                        recorderLogger.e(loggerTag, "Error joining recording job", e)
                    }
                }
            }
            recordingStats.summarise()
        } finally {
            scope.coroutineContext.cancel()
            releaseResources()
            jobs.clear()
            recordingStats.reset()
            audioPresentationTimeUs = 0L
        }
    }

    /**
     * Rotate current output to a new file. Keep encoders and virtual display alive.
     * Returns true if rotation succeeded.
     */
    fun rotateMuxer(newFile: File): Boolean {
        val vFmt = cachedVideoFormat
        val aFmt = cachedAudioFormat
        if (vFmt == null || aFmt == null) {
            logger("rotateMuxer() skipped: formats not ready")
            return false
        }
        muxerCoordinator.rotateTo(newFile, vFmt, aFmt)
        // Try to request a keyframe so the new file starts cleanly
        try {
            val b = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            videoEncoder?.setParameters(b)
        } catch (_: Throwable) {}
        return true
    }

    private fun prepareEncoders() {
        recorderLogger.d(loggerTag, "Preparing encoders")
        setupVideoEncoder()
        setupAudioEncoder()
    }

    private fun setupVideoEncoder() {
        val targetBitrate = calculateVideoBitrate(videoWidth, videoHeight)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        recorderLogger.d(
            loggerTag,
            "Video encoder request: ${videoWidth}x$videoHeight @30fps bitrate=${targetBitrate / 1000}kbps"
        )

        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(format)
            ?: throw IllegalStateException("No suitable video encoder found")

        videoEncoder = MediaCodec.createByCodecName(codecName).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            this@ProjectionRecorder.inputSurface = createInputSurface()
            start()
        }

        recorderLogger.d(loggerTag, "Video encoder configured using $codecName")
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            audioSampleRate,
            audioChannelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_CHANNEL_MASK, audioChannelMask)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioChannelCount)
        }

        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(format)
            ?: throw IllegalStateException("No suitable audio encoder found")

        audioEncoder = MediaCodec.createByCodecName(codecName).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        recorderLogger.d(loggerTag, "Audio encoder configured using $codecName")

        prepareAudioRecord()
    }

    private fun prepareAudioRecord() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            audioSampleRate,
            audioChannelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = (minBufferSize * 2).coerceAtLeast(1024 * audioBytesPerFrame)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audioSampleRate)
                    .setChannelMask(audioChannelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        recorderLogger.d(loggerTag, "AudioRecord prepared with bufferSize=$bufferSize")
    }

    private fun startVirtualDisplay() {
        val surface = inputSurface
            ?: throw IllegalStateException("Video encoder surface unavailable")

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "TranscriptAppRecorder",
            videoWidth,
            videoHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        recorderLogger.d(loggerTag, "Virtual display created (${videoWidth}x$videoHeight @ $densityDpi dpi)")
    }

    private suspend fun drainVideoEncoderLoop() {
        val encoder = videoEncoder ?: return
        try {
            var ended = false
            while (!ended) {
                val outputIndex = encoder.dequeueOutputBuffer(videoBufferInfo, dequeueTimeoutUs)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!recording.get()) {
                            // After stop request, keep draining until EOS appears
                            delay(5)
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        cachedVideoFormat = encoder.outputFormat
                        muxerCoordinator.setVideoFormat(encoder.outputFormat)
                    }
                    outputIndex >= 0 -> {
                        val encodedBuffer = encoder.getOutputBuffer(outputIndex)
                        if (encodedBuffer != null) {
                            if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encoder.releaseOutputBuffer(outputIndex, false)
                                continue
                            }

                            if (videoBufferInfo.size > 0) {
                                encodedBuffer.position(videoBufferInfo.offset)
                                encodedBuffer.limit(videoBufferInfo.offset + videoBufferInfo.size)
                                val rawPtsUs = videoBufferInfo.presentationTimeUs
                                val normalizedPtsUs = recordingStats.normaliseVideoPts(rawPtsUs)
                                videoBufferInfo.presentationTimeUs = normalizedPtsUs
                                muxerCoordinator.writeSampleVideo(encodedBuffer, videoBufferInfo)
                                recordingStats.onVideoSample(rawPtsUs, normalizedPtsUs, videoBufferInfo)
                            }

                            val endOfStream = (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if (endOfStream) {
                                recorderLogger.d(loggerTag, "Video encoder reached end of stream")
                                ended = true
                            }
                        } else {
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            recorderLogger.e(loggerTag, "Error draining video encoder", e)
        }
    }

    private suspend fun captureAndEncodeAudioLoop() {
        val encoder = audioEncoder ?: return
        val record = audioRecord ?: return

        val bufferSizeBytes = (record.bufferSizeInFrames * audioBytesPerFrame)
            .takeIf { it > 0 }
            ?.coerceAtLeast(1024 * audioBytesPerFrame)
            ?: (1024 * audioBytesPerFrame)
        val buffer = ByteArray(bufferSizeBytes)
        try {
            record.startRecording()
            recorderLogger.d(loggerTag, "Audio capture started")
            while (recording.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    queueAudioInput(encoder, buffer, read)
                    drainAudioEncoder(encoder, false)
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                    recorderLogger.e(loggerTag, "AudioRecord invalid operation")
                } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                    recorderLogger.e(loggerTag, "AudioRecord dead object")
                    break
                }
            }
        } catch (e: Exception) {
            recorderLogger.e(loggerTag, "Error during audio capture", e)
        } finally {
            queueAudioEndOfStream(encoder)
            drainAudioEncoder(encoder, true)
            record.runCatching { stop() }?.onFailure {
                recorderLogger.e(loggerTag, "Error stopping AudioRecord after loop", it)
            }
            recorderLogger.d(loggerTag, "Audio capture loop finished")
        }
    }

    private suspend fun queueAudioInput(encoder: MediaCodec, data: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val inputBufferIndex = encoder.dequeueInputBuffer(dequeueTimeoutUs)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: continue
                inputBuffer.clear()

                val chunkSize = minOf(length - offset, inputBuffer.remaining())
                inputBuffer.put(data, offset, chunkSize)
                val presentationTimeUs = audioPresentationTimeUs
                val frames = chunkSize / audioBytesPerFrame
                audioPresentationTimeUs += frames * 1_000_000L / audioSampleRate

                encoder.queueInputBuffer(inputBufferIndex, 0, chunkSize, presentationTimeUs, 0)
                offset += chunkSize
            } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                delay(2)
            }
        }
    }

    private fun queueAudioEndOfStream(encoder: MediaCodec) {
        var eosQueued = false
        while (!eosQueued) {
            val inputIndex = encoder.dequeueInputBuffer(dequeueTimeoutUs)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    audioPresentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                eosQueued = true
            }
        }
    }

    private suspend fun drainAudioEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        var finished = false
        while (!finished) {
            val outputIndex = encoder.dequeueOutputBuffer(audioBufferInfo, dequeueTimeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        finished = true
                    } else {
                        delay(5)
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    cachedAudioFormat = encoder.outputFormat
                    muxerCoordinator.setAudioFormat(encoder.outputFormat)
                }
                outputIndex >= 0 -> {
                    val encodedBuffer = encoder.getOutputBuffer(outputIndex)
                    if (encodedBuffer != null) {
                        if (audioBufferInfo.size > 0) {
                            encodedBuffer.position(audioBufferInfo.offset)
                            encodedBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
                            recordingStats.onAudioSample(audioBufferInfo)
                            muxerCoordinator.writeSampleAudio(encodedBuffer, audioBufferInfo)
                        }
                        val eos = (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (eos) {
                            recorderLogger.d(loggerTag, "Audio encoder reached end of stream")
                            finished = true
                        }
                    } else {
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    private fun releaseResources() {
        recorderLogger.d(loggerTag, "Releasing recorder resources")

        virtualDisplay?.runCatching { release() }?.onFailure {
            recorderLogger.e(loggerTag, "Error releasing virtual display", it)
        }
        virtualDisplay = null

        inputSurface?.runCatching { release() }
            ?.onFailure { recorderLogger.e(loggerTag, "Error releasing input surface", it) }
        inputSurface = null

        audioRecord?.runCatching { release() }
            ?.onFailure { recorderLogger.e(loggerTag, "Error releasing AudioRecord", it) }
        audioRecord = null

        audioEncoder?.let { encoder ->
            runCatching { encoder.stop() }
                .onFailure { recorderLogger.e(loggerTag, "Error stopping audio encoder", it) }
            runCatching { encoder.release() }
                .onFailure { recorderLogger.e(loggerTag, "Error releasing audio encoder", it) }
        }
        audioEncoder = null

        videoEncoder?.let { encoder ->
            runCatching { encoder.stop() }
                .onFailure { recorderLogger.e(loggerTag, "Error stopping video encoder", it) }
            runCatching { encoder.release() }
                .onFailure { recorderLogger.e(loggerTag, "Error releasing video encoder", it) }
        }
        videoEncoder = null

        muxerCoordinator.stopAndRelease()
    }

    private fun calculateVideoBitrate(width: Int, height: Int): Int {
        val pixels = width * height
        val baseline = 4_500_000 // 4.5 Mbps baseline for ~1080p
        val reference = 1920 * 1080
        return (baseline * (pixels / reference.toFloat())).toInt().coerceAtLeast(2_000_000)
    }
}
