package com.example.transcriptapp.service.transcript

import com.example.transcriptapp.model.transcript.TranscriptRequest
import com.example.transcriptapp.model.transcript.TranscriptResponse
import com.example.transcriptapp.repository.AuthRepository
import com.example.transcriptapp.utils.ApiConfig
import com.example.transcriptapp.utils.RecorderLogger
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Implementation of TranscriptService interface
 * Handles API communication for transcription service
 */
class TranscriptServiceImpl(
    private val authRepository: AuthRepository,
    private val baseUrl: String = ApiConfig.BASE_URL
) : TranscriptService {
    
    private val TAG = "TranscriptServiceImpl"
    
    // Create OkHttpClient with extended timeouts for large file uploads
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(ApiConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            RecorderLogger.d(TAG, "Sending request: ${request.method} ${request.url}")
            RecorderLogger.d(TAG, "Headers: ${request.headers}")
            val response = chain.proceed(request)
            RecorderLogger.d(TAG, "Received response: ${response.code} for ${request.url}")
            response
        }
        .build()
    
    // Create Retrofit instance
    private val api: TranscriptApi
    
    init {
        RecorderLogger.d(TAG, "Initializing TranscriptServiceImpl with base URL: $baseUrl")
        RecorderLogger.d(TAG, "Transcript API endpoint: ${baseUrl}${ApiConfig.TRANSCRIPT_ENDPOINT}")
        
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            
        api = retrofit.create(TranscriptApi::class.java)
    }
    
    /**
     * Implementation of transcribeVideo method to handle file upload to API
     */
    override suspend fun transcribeVideo(
        videoFile: File, 
        transcriptRequest: TranscriptRequest
    ): TranscriptResponse {
        RecorderLogger.d(TAG, "Preparing to transcribe video: ${videoFile.name}")
        
        if (!videoFile.exists()) {
            RecorderLogger.e(TAG, "Video file doesn't exist: ${videoFile.absolutePath}")
            return TranscriptResponse(
                success = false,
                statusCode = 400,
                message = "Video file not found",
                data = null,
                timestamp = null
            )
        }
        
        // Get the access token for authorization
        val accessToken = getAccessToken()
        if (accessToken.isNullOrEmpty()) {
            RecorderLogger.e(TAG, "Cannot proceed with transcription: No access token available")
            return TranscriptResponse(
                success = false,
                statusCode = 401,
                message = "Authentication required",
                data = null,
                timestamp = null
            )
        }
        
        RecorderLogger.d(TAG, "Using access token for authentication: ${accessToken.take(15)}...${accessToken.takeLast(15)}")
        
        try {
            // Create multipart request parts
            val videoRequestBody = videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())
            val videoPart = MultipartBody.Part.createFormData("file", videoFile.name, videoRequestBody)
            
            // Create text parts
            val modelIdPart = createTextPart("model_id", transcriptRequest.modelId)
            val languageCodePart = createTextPart("language_code", transcriptRequest.languageCode)
            val tagAudioEventsPart = createTextPart("tag_audio_events", transcriptRequest.tagAudioEvents.toString())
            val useWebhookPart = createTextPart("useWebhook", transcriptRequest.useWebhook.toString())
            
            RecorderLogger.d(TAG, "Sending transcription request for: ${videoFile.name}")
            
            // Call the API with bearer token authentication
            val response = api.transcribeVideo(
                "Bearer $accessToken",
                videoPart,
                modelIdPart,
                languageCodePart,
                tagAudioEventsPart,
                useWebhookPart
            )
            
            RecorderLogger.d(TAG, "Transcription response: $response")
            return response
            
        } catch (e: Exception) {
            RecorderLogger.e(TAG, "Error during transcription request", e)
            
            // Handle HTTP errors
            if (e is retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                RecorderLogger.e(TAG, "HTTP error ${e.code()} - body: $errorBody", e)
                return TranscriptResponse(
                    success = false,
                    statusCode = e.code(),
                    message = errorBody ?: e.localizedMessage ?: "Unknown HTTP error",
                    data = null,
                    timestamp = null
                )
            }
            
            // Handle other errors
            return TranscriptResponse(
                success = false,
                statusCode = null,
                message = e.localizedMessage ?: "Unknown error during transcription",
                data = null,
                timestamp = null
            )
        }
    }
    
    /**
     * Get the current access token from AuthRepository
     */
    override fun getAccessToken(): String? {
        return authRepository.getAccessToken()
    }
    
    /**
     * Helper method to create text part for multipart request
     */
    private fun createTextPart(name: String, value: String): RequestBody {
        return value.toRequestBody("text/plain".toMediaTypeOrNull())
    }
}

/**
 * Retrofit API interface for transcript endpoints
 */
interface TranscriptApi {
    @Multipart
    @POST(ApiConfig.TRANSCRIPT_ENDPOINT)
    suspend fun transcribeVideo(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model_id") modelId: RequestBody,
        @Part("language_code") languageCode: RequestBody,
        @Part("tag_audio_events") tagAudioEvents: RequestBody,
        @Part("useWebhook") useWebhook: RequestBody
    ): TranscriptResponse
}