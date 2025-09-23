package com.example.transcriptapp.service.transcript

import com.example.transcriptapp.model.transcript.TranscriptRequest
import com.example.transcriptapp.model.transcript.TranscriptResponse
import java.io.File

/**
 * Interface for transcript-related API services
 * Provides methods to send video files for transcription
 */
interface TranscriptService {
    /**
     * Send a video file for transcription with given parameters
     * 
     * @param videoFile The video file to be transcribed
     * @param transcriptRequest Parameters for the transcription request
     * @return TranscriptResponse containing the transcription results
     */
    suspend fun transcribeVideo(
        videoFile: File,
        transcriptRequest: TranscriptRequest = TranscriptRequest()
    ): TranscriptResponse
    
    /**
     * Get access token for authentication
     * 
     * @return Current access token or null if not authenticated
     */
    fun getAccessToken(): String?
}