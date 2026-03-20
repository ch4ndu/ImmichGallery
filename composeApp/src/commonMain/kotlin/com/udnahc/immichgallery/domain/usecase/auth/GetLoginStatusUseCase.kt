package com.udnahc.immichgallery.domain.usecase.auth

import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class GetLoginStatusUseCase(private val serverConfigRepository: ServerConfigRepository) {
    operator fun invoke(): Boolean = serverConfigRepository.isLoggedIn()
}
