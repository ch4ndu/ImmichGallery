package com.udnahc.immichgallery.domain.usecase.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class GetTimelineGroupSizeUseCase(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(): String = serverConfigRepository.getTimelineGroupSize()
}
