package com.example.transcriptapp.model.transcript

/**
 * Request parameters for transcript API 
 * This represents the parameters needed for the form-data API call
 */
data class TranscriptRequest(
    val modelId: String = "scribe_v1",
    val languageCode: String = "",
    val tagAudioEvents: Boolean = false,
    val useWebhook: Boolean = true
)