package com.example.transcriptapp.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.transcriptapp.model.User
import com.example.transcriptapp.repository.AuthRepository
import kotlinx.coroutines.launch

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _loginResult = MutableLiveData<Result<User>>()
    val loginResult: LiveData<Result<User>> = _loginResult

    private val _logoutResult = MutableLiveData<Unit>()
    val logoutResult: LiveData<Unit> = _logoutResult

    fun login(email: String, password: String) {
        viewModelScope.launch {
            val result = authRepository.login(email, password)
            _loginResult.value = result
        }
    }

    fun logout() {
        authRepository.logout()
        _logoutResult.value = Unit
    }

    fun isLoggedIn(): Boolean {
        return authRepository.isLoggedIn()
    }

    fun getCurrentUser(): User? {
        return authRepository.getCurrentUser()
    }
}