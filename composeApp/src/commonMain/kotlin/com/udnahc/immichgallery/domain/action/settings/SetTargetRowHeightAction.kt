package com.udnahc.immichgallery.domain.action.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.RowHeightScope

class SetTargetRowHeightAction(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(scope: RowHeightScope, height: Float) {
        serverConfigRepository.setTargetRowHeight(scope, height)
    }
}
