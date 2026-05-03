package com.udnahc.immichgallery.domain.usecase.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.RowHeightScope

class GetTargetRowHeightUseCase(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(scope: RowHeightScope): Float =
        serverConfigRepository.getTargetRowHeight(scope)
}
