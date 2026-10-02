package com.udnahc.immichgallery.domain.usecase.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import kotlinx.coroutines.flow.StateFlow

class GetSlideshowConfigUseCase(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(): SlideshowConfig = serverConfigRepository.getSlideshowConfig()
    fun observe(): StateFlow<SlideshowConfig> = serverConfigRepository.observeSlideshowConfig()
}
