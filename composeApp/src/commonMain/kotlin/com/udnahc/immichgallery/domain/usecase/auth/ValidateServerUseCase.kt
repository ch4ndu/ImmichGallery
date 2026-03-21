package com.udnahc.immichgallery.domain.usecase.auth

import com.udnahc.immichgallery.data.model.UserResponse
import com.udnahc.immichgallery.data.repository.AuthRepository

class ValidateServerUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(
        serverUrl: String,
        apiKey: String
    ): Result<UserResponse> {
        return runCatching {
            authRepository.validateConnection(serverUrl, apiKey)
        }
    }
}
