package com.udnahc.immichgallery.domain.usecase.auth

import com.udnahc.immichgallery.data.model.UserResponse
import com.udnahc.immichgallery.data.repository.AuthRepository
import com.udnahc.immichgallery.runSuspendCatching

class ValidateServerUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(
        serverUrl: String,
        apiKey: String
    ): Result<UserResponse> {
        return runSuspendCatching {
            authRepository.validateConnection(serverUrl, apiKey)
        }
    }
}
