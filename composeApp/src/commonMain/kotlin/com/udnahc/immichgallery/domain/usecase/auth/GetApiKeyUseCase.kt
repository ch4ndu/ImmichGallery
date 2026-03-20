package com.udnahc.immichgallery.domain.usecase.auth

import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class GetApiKeyUseCase(private val serverConfigRepository: ServerConfigRepository) {
    operator fun invoke(): String = serverConfigRepository.getApiKey()
}
