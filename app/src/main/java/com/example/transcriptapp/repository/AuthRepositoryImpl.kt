package com.example.transcriptapp.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.transcriptapp.model.User
import com.example.transcriptapp.service.AuthService
import com.example.transcriptapp.utils.RecorderLogger
import com.google.gson.Gson

class AuthRepositoryImpl(
    private val context: Context,
    private val authService: AuthService
) : AuthRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    override suspend fun login(email: String, password: String): Result<User> {
        return try {
            val response = authService.login(email, password)
            val data = response.data
            
            if (response.success && data != null) {
                // Log authentication details
                RecorderLogger.d("AuthRepositoryImpl", "Login successful for ${email}")
                
                if (data.accessToken != null) {
                    RecorderLogger.d("AuthRepositoryImpl", "Received token: ${data.accessToken.take(10)}...${data.accessToken.takeLast(10)}")
                    
                    // Save access token
                    saveToken(data.accessToken)
                    
                    // Save user if available
                    if (data.user != null) {
                        saveUser(data.user)
                    }
                    
                    // Return the user from response
                    if (data.user != null) {
                        Result.success(data.user)
                    } else {
                        // Create minimal user if none in response
                        val user = User(
                            id = "unknown",
                            email = email,
                            name = null,
                            accessToken = data.accessToken
                        )
                        saveUser(user)
                        Result.success(user)
                    }
                } else {
                    RecorderLogger.e("AuthRepositoryImpl", "Login response missing access token")
                    Result.failure(Exception("Login response missing access token"))
                }
            } else {
                RecorderLogger.e("AuthRepositoryImpl", "Login failed: ${response.message ?: "Unknown error"}")
                Result.failure(Exception(response.message ?: "Login failed"))
            }
        } catch (e: Exception) {
            RecorderLogger.e("AuthRepositoryImpl", "Login exception", e)
            Result.failure(e)
        }
    }

    override fun logout() {
        prefs.edit().clear().apply()
    }

    override fun getCurrentUser(): User? {
        val userJson = prefs.getString("user", null)
        return userJson?.let { gson.fromJson(it, User::class.java) }
    }

    override fun isLoggedIn(): Boolean {
        return prefs.contains("access_token")
    }
    
    override fun getAccessToken(): String? {
        return prefs.getString("access_token", null)
    }

    fun saveUser(user: User) {
        val userJson = gson.toJson(user)
        prefs.edit().putString("user", userJson).apply()
    }

    private fun saveToken(token: String) {
        prefs.edit().putString("access_token", token).apply()
    }
}