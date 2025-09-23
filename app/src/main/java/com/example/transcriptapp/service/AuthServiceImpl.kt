package com.example.transcriptapp.service

import com.example.transcriptapp.model.User
import com.example.transcriptapp.utils.ApiConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

class AuthServiceImpl : AuthService {
    private val api: AuthApi

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        api = retrofit.create(AuthApi::class.java)
    }

    override suspend fun login(email: String, password: String): LoginResponse {
        return try {
            val response = api.login(LoginRequest(email, password))
            com.example.transcriptapp.utils.RecorderLogger.d("AuthServiceImpl", "API response: $response")
            response
        } catch (e: Exception) {
            if (e is retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                com.example.transcriptapp.utils.RecorderLogger.e("AuthServiceImpl", "HTTP error ${e.code()} - body: $errorBody", e)
                return LoginResponse(success = false, statusCode = e.code(), message = errorBody ?: e.localizedMessage ?: "Unknown error", data = null, timestamp = null)
            }
            com.example.transcriptapp.utils.RecorderLogger.e("AuthServiceImpl", "API login error", e)
            LoginResponse(success = false, statusCode = null, message = e.localizedMessage ?: "Unknown error", data = null, timestamp = null)
        }
    }
}

interface AuthApi {
    @POST(ApiConfig.AUTH_LOGIN_ENDPOINT)
    suspend fun login(@Body request: LoginRequest): LoginResponse
}

data class LoginRequest(
    val email: String,
    val password: String
)