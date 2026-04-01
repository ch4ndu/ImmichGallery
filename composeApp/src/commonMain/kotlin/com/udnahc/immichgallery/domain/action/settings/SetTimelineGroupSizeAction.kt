package com.udnahc.immichgallery.domain.action.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class SetTimelineGroupSizeAction(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(size: String) {
        serverConfigRepository.setTimelineGroupSize(size)
    }
}
