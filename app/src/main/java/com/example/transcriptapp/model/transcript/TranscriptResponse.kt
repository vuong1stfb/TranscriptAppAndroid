package com.example.transcriptapp.model.transcript

/**
 * Response from transcript API
 * Follows the structure where we need to extract the text from the 'data' field
 */
data class TranscriptResponse(
    val success: Boolean,
    val statusCode: Int?,
    val message: String?,
    val data: TranscriptData?,
    val timestamp: String?
)

/**
 * Data content of transcript response
 * Contains the actual text transcript
 */
data class TranscriptData(
    val text: String?,
    val metadata: TranscriptMetadata?
)

/**
 * Additional metadata that may be provided with the transcript
 */
data class TranscriptMetadata(
    val duration: Float?,
    val language: String?,
    val modelName: String?
)