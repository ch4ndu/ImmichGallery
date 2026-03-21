package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class SaveServerConfigAction(private val serverConfigRepository: ServerConfigRepository) {
    operator fun invoke(
        serverUrl: String,
        apiKey: String
    ) {
        serverConfigRepository.setServerUrl(serverUrl)
        serverConfigRepository.setApiKey(apiKey)
    }
}
