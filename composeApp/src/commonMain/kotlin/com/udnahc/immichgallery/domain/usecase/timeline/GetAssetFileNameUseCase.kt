package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository

class GetAssetFileNameUseCase(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(assetId: String, currentFileName: String): Result<String> {
        if (currentFileName.isNotEmpty()) return Result.success(currentFileName)
        return runCatching { repository.getAssetFileName(assetId) }
    }
}
