package com.example.transcriptapp.service.realtime

import com.example.transcriptapp.repository.AuthRepository
import com.example.transcriptapp.utils.ApiConfig
import com.example.transcriptapp.utils.RecorderLogger
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RealtimeTokenService(
    private val authRepository: AuthRepository,
    private val baseUrl: String = ApiConfig.BASE_URL
) {
    private val loggerTag = "RealtimeTokenService"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    data class ApiKeyResponse(val `x-api-key`: String?)
    data class ApiKeyEnvelope(val data: ApiKeyResponse?)

    suspend fun fetchApiKey(tokenLimit: Int = 10000): String? {
        val accessToken = authRepository.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            RecorderLogger.e(loggerTag, "No access token for fetching xi-api-key")
            return null
        }

        val url = "$baseUrl${ApiConfig.ELEVENLABS_TOKEN_LIMIT_ENDPOINT}?tokenLimit=$tokenLimit"
        RecorderLogger.d(loggerTag, "fetchApiKey start tokenLimit=$tokenLimit url=$url")
        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                val elapsed = System.currentTimeMillis() - startTime
                val bodyPreview = if (body.isNullOrBlank()) "<empty>" else body.take(2000)
                RecorderLogger.d(
                    loggerTag,
                    "fetchApiKey HTTP ${response.code} (${elapsed}ms) bodyLen=${body?.length ?: 0} body=${bodyPreview}"
                )
                if (!response.isSuccessful) {
                    RecorderLogger.e(loggerTag, "fetchApiKey failed: HTTP ${response.code}")
                    return@runCatching null
                }
                if (body.isNullOrBlank()) {
                    RecorderLogger.e(loggerTag, "fetchApiKey empty response")
                    return@runCatching null
                }
                val parsedEnvelope = gson.fromJson(body, ApiKeyEnvelope::class.java)
                val apiKey = parsedEnvelope?.data?.`x-api-key`
                RecorderLogger.d(loggerTag, "fetchApiKey parsed apiKey len=${apiKey?.length ?: 0}")
                apiKey
            }
        }.onFailure {
            RecorderLogger.e(loggerTag, "fetchApiKey exception", it)
        }.getOrNull()
    }

    suspend fun notifyTokenUsage(apiKey: String) {
        if (apiKey.isBlank()) return
        val accessToken = authRepository.getAccessToken()
        if (accessToken.isNullOrBlank()) return

        val url = "$baseUrl${ApiConfig.ELEVENLABS_TOKEN_USAGE_ENDPOINT}"
        val payload = gson.toJson(mapOf("x-api-key" to apiKey))
        val request = Request.Builder()
            .url(url)
            .patch(payload.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    RecorderLogger.e(loggerTag, "notifyTokenUsage failed: HTTP ${response.code}")
                } else {
                    RecorderLogger.d(loggerTag, "notifyTokenUsage success")
                }
            }
        }.onFailure {
            RecorderLogger.e(loggerTag, "notifyTokenUsage exception", it)
        }
    }
}
