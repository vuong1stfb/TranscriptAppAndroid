package com.example.transcriptapp.repository.transcript

import com.example.transcriptapp.model.transcript.TranscriptRequest
import com.example.transcriptapp.service.transcript.TranscriptService
import com.example.transcriptapp.utils.RecorderLogger
import java.io.File

/**
 * Implementation of TranscriptRepository interface
 * Handles business logic for transcript operations and provides a clean API for the UI/ViewModel
 */
class TranscriptRepositoryImpl(
    private val transcriptService: TranscriptService
) : TranscriptRepository {
    
    private val TAG = "TranscriptRepositoryImpl"
    
    /**
     * Implementation of transcribeVideo method
     * Processes the response from the service and extracts the transcript text
     */
    override suspend fun transcribeVideo(
        videoFile: File,
        transcriptRequest: TranscriptRequest
    ): Result<String> {
        RecorderLogger.d(TAG, "Starting transcription request for ${videoFile.name}")
        
        return try {
            val response = transcriptService.transcribeVideo(videoFile, transcriptRequest)
            
            if (response.success && response.data?.text != null) {
                // Success case - return the transcription text
                RecorderLogger.d(TAG, "Transcription successful, text length: ${response.data.text.length}")
                Result.success(response.data.text)
            } else {
                // API returned an error
                val errorMessage = response.message ?: "Unknown error during transcription"
                RecorderLogger.e(TAG, "Transcription API error: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            // Exception during API call or processing
            RecorderLogger.e(TAG, "Exception during transcription", e)
            Result.failure(e)
        }
    }
}