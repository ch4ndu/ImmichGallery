package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class ClearServerConfigAction(private val serverConfigRepository: ServerConfigRepository) {
    operator fun invoke() {
        serverConfigRepository.clear()
    }
}
