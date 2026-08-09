package com.udnahc.immichgallery.domain.usecase.asset

import com.udnahc.immichgallery.data.repository.AssetRepository
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.runSuspendCatching

class GetAssetStackUseCase(
    private val assetRepository: AssetRepository
) {
    suspend operator fun invoke(assetId: String): Result<AssetStackLookup> =
        runSuspendCatching { assetRepository.getAssetStack(assetId) }
}
