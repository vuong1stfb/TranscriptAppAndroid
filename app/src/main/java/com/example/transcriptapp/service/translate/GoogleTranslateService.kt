package com.example.transcriptapp.service.translate

import com.example.transcriptapp.model.translate.TranslationResponse
import com.example.transcriptapp.utils.RecorderLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Service for Google Translate API integration
 * Uses the free unofficial Google Translate endpoint
 */
class GoogleTranslateService {
    
    companion object {
        private const val TAG = "GoogleTranslateService"
        private const val BASE_URL = "https://translate.googleapis.com/translate_a/single"
        private const val DEFAULT_TARGET_LANGUAGE = "vi" // Vietnamese
        private const val DEFAULT_SOURCE_LANGUAGE = "auto" // Auto-detect
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * Translate text using Google Translate free API
     * 
     * @param text Text to translate
     * @param targetLanguage Target language code (default: vi for Vietnamese)
     * @param sourceLanguage Source language code (default: auto for auto-detect)
     * @return Translated text or null if translation fails
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
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "$BASE_URL?client=gtx&dt=t&dj=1&sl=$sourceLanguage&tl=$targetLanguage&q=$encodedText"
            
            RecorderLogger.d(TAG, "Translating text from '$sourceLanguage' to '$targetLanguage': ${text.take(50)}...")
            
            val request = Request.Builder()
                .url(url)
                .get()
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
            
            val translationResponse = gson.fromJson(responseBody, TranslationResponse::class.java)
            
            val translatedText = translationResponse.sentences
                .map { it.trans }
                .joinToString("")
            
            if (translatedText.isNotBlank()) {
                RecorderLogger.d(TAG, "Translation successful: ${translatedText.take(50)}...")
                return@withContext translatedText
            } else {
                RecorderLogger.e(TAG, "Translation result is empty")
                return@withContext null
            }
            
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
            val latinChars = text.count { it.isLetter() && it.code < 256 }
            val totalChars = text.count { it.isLetter() }
            return totalChars > 0 && (latinChars.toDouble() / totalChars) > 0.7
        }
        return true // Default to translate if we can't determine
    }
}