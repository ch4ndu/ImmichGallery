package com.udnahc.immichgallery.domain.usecase.settings

import com.udnahc.immichgallery.data.repository.ServerConfigRepository

class GetTargetRowHeightUseCase(
    private val serverConfigRepository: ServerConfigRepository
) {
    operator fun invoke(): Float = serverConfigRepository.getTargetRowHeight()
}
