package com.udnahc.immichgallery.domain.action.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.SlideshowConfig

class SetSlideshowConfigAction(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(config: SlideshowConfig) {
        serverConfigRepository.setSlideshowConfig(config)
    }
}
