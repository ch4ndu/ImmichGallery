package com.udnahc.immichgallery.domain.usecase.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import kotlinx.coroutines.flow.StateFlow

class GetTimelineGroupSizeUseCase(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(): String = serverConfigRepository.getTimelineGroupSize()
    fun observe(): StateFlow<String> = serverConfigRepository.observeTimelineGroupSize()
}
