package com.udnahc.immichgallery.domain.usecase.auth

import com.udnahc.immichgallery.data.model.UserResponse
import com.udnahc.immichgallery.data.repository.AuthRepository
import com.udnahc.immichgallery.runSuspendCatching

class GetCurrentUserUseCase(private val authRepository: AuthRepository) {
    suspend operator fun invoke(): Result<UserResponse> = runSuspendCatching {
        authRepository.getCurrentUser()
    }
}
