package com.example.transcriptapp.repository.transcript

import com.example.transcriptapp.model.transcript.TranscriptRequest
import java.io.File

/**
 * Repository interface for transcript-related operations
 * Acts as an abstraction layer between the data source and the business logic
 */
interface TranscriptRepository {
    /**
     * Request transcription for a video file
     * 
     * @param videoFile The video file to be transcribed
     * @param transcriptRequest Additional parameters for the transcription
     * @return Result object with either success containing the transcription text or failure with error
     */
    suspend fun transcribeVideo(
        videoFile: File,
        transcriptRequest: TranscriptRequest = TranscriptRequest()
    ): Result<String>
}