package com.example.transcriptapp.utils.transcript

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.transcriptapp.model.transcript.TranscriptRequest
import com.example.transcriptapp.overlay.SubtitleOverlayService
import com.example.transcriptapp.repository.AuthRepository
import com.example.transcriptapp.repository.transcript.TranscriptRepository
import com.example.transcriptapp.repository.transcript.TranscriptRepositoryImpl
import com.example.transcriptapp.service.transcript.TranscriptService
import com.example.transcriptapp.service.transcript.TranscriptServiceImpl
import com.example.transcriptapp.service.translate.GoogleTranslateService
import com.example.transcriptapp.utils.RecorderLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Utility class to handle transcription operations
 * Acts as a facade for the transcription functionality
 */
class TranscriptionManager(
    private val context: Context,
    private val authRepository: AuthRepository
) {
    private val TAG = "TranscriptionManager"
    private val transcriptService: TranscriptService = TranscriptServiceImpl(authRepository)
    private val transcriptRepository: TranscriptRepository = TranscriptRepositoryImpl(transcriptService)
    private val translateService: GoogleTranslateService = GoogleTranslateService()
    
    /**
     * Request transcription for a video file and show the result in a toast notification
     * 
     * @param videoFile The video file to be transcribed
     */
    fun transcribeVideoAndNotify(videoFile: File) {
        if (!videoFile.exists()) {
            RecorderLogger.e(TAG, "Cannot transcribe: Video file doesn't exist at ${videoFile.absolutePath}")
            showToast("Cannot transcribe: Video file not found")
            return
        }
        
        // Get access token directly from repository
        val accessToken = authRepository.getAccessToken()
        val user = authRepository.getCurrentUser()
        
        if (accessToken.isNullOrEmpty()) {
            RecorderLogger.e(TAG, "Cannot transcribe: No valid authentication token. User: ${user?.email ?: "null"}")
            showToast("Không thể phiên âm: Cần đăng nhập trước")
            return
        }
        
        RecorderLogger.d(TAG, "Starting transcription process for ${videoFile.name}")
        RecorderLogger.d(TAG, "Using token: ${accessToken.take(10)}...${accessToken.takeLast(10)}")
        
        // Create default transcript request
        val request = TranscriptRequest()
        
        // Log request details
        RecorderLogger.d(TAG, "Transcription request details:")
        RecorderLogger.d(TAG, "- File: ${videoFile.absolutePath} (${videoFile.length()} bytes)")
        RecorderLogger.d(TAG, "- Model ID: ${request.modelId}")
        RecorderLogger.d(TAG, "- Language code: ${request.languageCode}")
        RecorderLogger.d(TAG, "- Tag audio events: ${request.tagAudioEvents}")
        RecorderLogger.d(TAG, "- Use webhook: ${request.useWebhook}")
        
        // Use IO dispatcher for network operations
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = transcriptRepository.transcribeVideo(videoFile, request)

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { transcriptionText ->
                            // Show subtitle overlay with transcription text
                            showSubtitleOverlay(transcriptionText)
                            RecorderLogger.d(TAG, "Transcription successful: $transcriptionText")
                        },
                        onFailure = { error ->
                            // Show error as toast (errors don't need subtitle overlay)
                            showToast("Transcription failed: ${error.localizedMessage}")
                            RecorderLogger.e(TAG, "Transcription failed", error)
                        }
                    )
                }

            } catch (e: Exception) {
                // Handle any unexpected errors
                withContext(Dispatchers.Main) {
                    showToast("Error during transcription: ${e.localizedMessage}")
                }
                RecorderLogger.e(TAG, "Exception during transcription process", e)
            } finally {
                // Delete the temporary video file after attempting transcription to avoid storing segments locally
                try {
                    if (videoFile.exists()) {
                        val deleted = videoFile.delete()
                        RecorderLogger.d(TAG, "Temporary recording file deletion attempted: ${videoFile.absolutePath}, deleted=$deleted")
                    }
                } catch (t: Throwable) {
                    RecorderLogger.e(TAG, "Failed to delete temporary recording file: ${videoFile.absolutePath}", t)
                }
            }
        }
    }
    
    /**
     * Show a toast message for errors
     */
    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    
    /**
     * Show subtitle overlay with transcription text
     * Now includes Google Translate integration for Vietnamese translation
     */
    private fun showSubtitleOverlay(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Attempt to translate the text to Vietnamese
                val translatedText = if (translateService.isTranslationNeeded(text)) {
                    translateService.translateText(text, "vi", "auto")
                } else {
                    null
                }
                
                // Use translated text if available, otherwise use original
                val displayText = translatedText ?: text
                
                withContext(Dispatchers.Main) {
                    // Start subtitle overlay service if not running
                    val intent = Intent(context, SubtitleOverlayService::class.java).apply {
                        action = SubtitleOverlayService.ACTION_SHOW_SUBTITLE
                        putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, displayText)
                    }
                    context.startService(intent)
                    
                    // Also send broadcast for already running service
                    val broadcastIntent = Intent(SubtitleOverlayService.ACTION_SHOW_SUBTITLE).apply {
                        putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, displayText)
                    }
                    context.sendBroadcast(broadcastIntent)
                    
                    // Log translation result
                    if (translatedText != null) {
                        RecorderLogger.d(TAG, "Text translated successfully for subtitle overlay")
                    } else {
                        RecorderLogger.d(TAG, "Using original text for subtitle overlay (translation not needed or failed)")
                    }
                }
                
            } catch (e: Exception) {
                RecorderLogger.e(TAG, "Error during translation, using original text", e)
                
                withContext(Dispatchers.Main) {
                    // Fallback to original text on error
                    val intent = Intent(context, SubtitleOverlayService::class.java).apply {
                        action = SubtitleOverlayService.ACTION_SHOW_SUBTITLE
                        putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, text)
                    }
                    context.startService(intent)
                    
                    val broadcastIntent = Intent(SubtitleOverlayService.ACTION_SHOW_SUBTITLE).apply {
                        putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, text)
                    }
                    context.sendBroadcast(broadcastIntent)
                }
            }
        }
    }
}