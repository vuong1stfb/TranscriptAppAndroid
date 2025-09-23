package com.example.transcriptapp.model

data class User(
    val id: String,
    val email: String,
    val name: String?,
    val accessToken: String? = null
)