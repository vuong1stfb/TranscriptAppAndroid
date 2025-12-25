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
    
    // Realtime transcript token endpoints
    const val ELEVENLABS_TOKEN_LIMIT_ENDPOINT = "/api/elevenlabs-api/accounts/token-limit"
    const val ELEVENLABS_TOKEN_USAGE_ENDPOINT = "/api/elevenlabs-api/accounts/token-usage"

    // Realtime transcript websocket (example)
    const val REALTIME_TRANSCRIPT_WS =
        "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime"

    // Google translate (same as extension)
    const val TRANSLATE_API_ENDPOINT = "https://translate-pa.googleapis.com/v1/translateHtml"
    const val TRANSLATE_API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"
    
    // Timeouts
    const val CONNECT_TIMEOUT_SECONDS = 60L
    const val READ_TIMEOUT_SECONDS = 180L
    const val WRITE_TIMEOUT_SECONDS = 180L
}
