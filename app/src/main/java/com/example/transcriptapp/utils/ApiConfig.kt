package com.example.transcriptapp.utils

/**
 * Configuration class for API endpoints and settings
 * Centralizes URL configurations to make them easily modifiable
 */
object ApiConfig {
    // Base API URL for all services
    const val BASE_URL = "https://my-project-8kfa.onrender.com"
    
    // Auth-related endpoints
    const val AUTH_LOGIN_ENDPOINT = "/api/auth/login"
    const val AUTH_REFRESH_ENDPOINT = "/api/auth/refresh"
    
    // Transcript-related endpoints
    const val TRANSCRIPT_ENDPOINT = "/api/elevenlabs-api/transcript-file"
    
    // Timeouts
    const val CONNECT_TIMEOUT_SECONDS = 60L
    const val READ_TIMEOUT_SECONDS = 180L
    const val WRITE_TIMEOUT_SECONDS = 180L
}