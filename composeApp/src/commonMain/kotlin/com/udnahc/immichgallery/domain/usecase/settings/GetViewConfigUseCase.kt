package com.udnahc.immichgallery.domain.usecase.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.ViewConfig
import kotlinx.coroutines.flow.StateFlow

class GetViewConfigUseCase(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(): ViewConfig = serverConfigRepository.getViewConfig()
    fun observe(): StateFlow<ViewConfig> = serverConfigRepository.observeViewConfig()
}
