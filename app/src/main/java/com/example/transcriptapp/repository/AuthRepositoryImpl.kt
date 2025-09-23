package com.example.transcriptapp.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.transcriptapp.model.User
import com.example.transcriptapp.service.AuthService
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
            if (response.success && data?.user != null && data.accessToken != null) {
                saveUser(data.user)
                saveToken(data.accessToken)
                Result.success(data.user)
            } else {
                Result.failure(Exception(response.message ?: "Login failed"))
            }
        } catch (e: Exception) {
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

    private fun saveUser(user: User) {
        val userJson = gson.toJson(user)
        prefs.edit().putString("user", userJson).apply()
    }

    private fun saveToken(token: String) {
        prefs.edit().putString("access_token", token).apply()
    }
}