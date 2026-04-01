package com.udnahc.immichgallery.domain.action.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class SetTargetRowHeightAction(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(height: Float) {
        serverConfigRepository.setTargetRowHeight(height)
    }
}
