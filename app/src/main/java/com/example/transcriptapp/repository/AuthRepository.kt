package com.example.transcriptapp.repository

import com.example.transcriptapp.model.User

interface AuthRepository {
    suspend fun login(email: String, password: String): Result<User>
    fun logout()
    fun getCurrentUser(): User?
    fun isLoggedIn(): Boolean
    fun getAccessToken(): String?
}