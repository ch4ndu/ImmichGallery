package com.udnahc.immichgallery.domain.action.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.ViewConfig

class SetViewConfigAction(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(config: ViewConfig) {
        serverConfigRepository.setViewConfig(config)
    }
}
