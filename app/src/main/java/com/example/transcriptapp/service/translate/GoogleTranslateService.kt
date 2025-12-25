package com.example.transcriptapp.service.translate

import com.example.transcriptapp.utils.ApiConfig
import com.example.transcriptapp.utils.RecorderLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Service for Google Translate API integration
 * Uses the free unofficial Google Translate endpoint
 */
class GoogleTranslateService {
    
    companion object {
        private const val TAG = "GoogleTranslateService"
        private const val DEFAULT_TARGET_LANGUAGE = "vi" // Vietnamese
        private const val DEFAULT_SOURCE_LANGUAGE = "auto" // Auto-detect
        private const val TRANSLATE_LIB = "te_lib"
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * Translate text using the same endpoint as extension (translate-pa)
     */
    suspend fun translateText(
        text: String,
        targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
        sourceLanguage: String = DEFAULT_SOURCE_LANGUAGE
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            RecorderLogger.w(TAG, "Cannot translate empty text")
            return@withContext null
        }
        
        try {
            // If sourceLanguage is auto and text looks like Japanese, force source to 'ja'
            val resolvedSource = if (sourceLanguage == DEFAULT_SOURCE_LANGUAGE && containsJapanese(text)) {
                RecorderLogger.d(TAG, "Detected Japanese characters in input, forcing sourceLanguage=ja")
                "ja"
            } else sourceLanguage

            if (ApiConfig.TRANSLATE_API_KEY.isBlank()) {
                RecorderLogger.e(TAG, "Missing TRANSLATE_API_KEY")
                return@withContext null
            }

            val payload = gson.toJson(
                listOf(
                    listOf(listOf(text), resolvedSource, targetLanguage),
                    TRANSLATE_LIB
                )
            )
            val request = Request.Builder()
                .url(ApiConfig.TRANSLATE_API_ENDPOINT)
                .post(payload.toRequestBody("application/json+protobuf".toMediaType()))
                .addHeader("content-type", "application/json+protobuf")
                .addHeader("x-goog-api-key", ApiConfig.TRANSLATE_API_KEY)
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                RecorderLogger.e(TAG, "Translation API request failed with code: ${response.code}")
                return@withContext null
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                RecorderLogger.e(TAG, "Translation API returned empty response")
                return@withContext null
            }

            val parsed = gson.fromJson(responseBody, List::class.java) as? List<*>
            val translations = (parsed?.getOrNull(0) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val translatedText = translations.joinToString("")

            if (translatedText.isNotBlank()) {
                RecorderLogger.d(TAG, "Translation successful: ${translatedText.take(50)}...")
                return@withContext translatedText
            }
            RecorderLogger.e(TAG, "Translation result is empty")
            return@withContext null
            
        } catch (e: Exception) {
            RecorderLogger.e(TAG, "Error during translation", e)
            return@withContext null
        }
    }
    
    /**
     * Check if translation is needed (source and target languages are different)
     * This is a simple heuristic - in real implementation you might want to 
     * detect the actual language first
     */
    fun isTranslationNeeded(
        text: String,
        targetLanguage: String = DEFAULT_TARGET_LANGUAGE
    ): Boolean {
        // Simple heuristic: if text contains mostly Latin characters, likely needs translation to Vietnamese
        // This is not perfect but works for basic cases
        if (targetLanguage == "vi") {
            // If input contains Japanese characters, definitely translate
            if (containsJapanese(text)) return true

            val latinChars = text.count { it.isLetter() && it.code < 256 }
            val totalChars = text.count { it.isLetter() }
            return totalChars > 0 && (latinChars.toDouble() / totalChars) > 0.7
        }
        return true // Default to translate if we can't determine
    }

    /**
     * Detect if text contains Japanese characters (Hiragana, Katakana, Kanji ranges)
     */
    private fun containsJapanese(text: String): Boolean {
        for (ch in text) {
            val code = ch.code
            // Hiragana
            if (code in 0x3040..0x309F) return true
            // Katakana
            if (code in 0x30A0..0x30FF) return true
            // Katakana Phonetic Extensions
            if (code in 0x31F0..0x31FF) return true
            // CJK Unified Ideographs (common Kanji range)
            if (code in 0x4E00..0x9FFF) return true
            // Halfwidth Katakana
            if (code in 0xFF66..0xFF9F) return true
        }
        return false
    }
}
