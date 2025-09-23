package com.example.transcriptapp.model.translate

/**
 * Data models for Google Translate API response
 */
data class TranslationResponse(
    val sentences: List<Sentence>,
    val src: String,
    val confidence: Double?,
    val ld_result: LanguageDetectionResult?
)

data class Sentence(
    val trans: String,
    val orig: String,
    val backend: Int?
)

data class LanguageDetectionResult(
    val srclangs: List<String>,
    val srclangs_confidences: List<Double>,
    val extended_srclangs: List<String>
)