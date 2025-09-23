package com.example.transcriptapp.service

import com.example.transcriptapp.model.User

interface AuthService {
    suspend fun login(email: String, password: String): LoginResponse
}

data class LoginResponse(
    val success: Boolean,
    val statusCode: Int?,
    val message: String?,
    val data: LoginData?,
    val timestamp: String?
)

data class LoginData(
    val accessToken: String?,
    val refreshToken: String?,
    val user: User?
)