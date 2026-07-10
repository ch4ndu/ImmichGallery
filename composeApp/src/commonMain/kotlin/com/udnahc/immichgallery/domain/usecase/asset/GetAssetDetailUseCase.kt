package com.udnahc.immichgallery.domain.usecase.asset

import com.udnahc.immichgallery.data.repository.AssetRepository
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.runSuspendCatching

class GetAssetDetailUseCase(
    private val assetRepository: AssetRepository
) {
    suspend operator fun invoke(assetId: String): Result<AssetDetail> =
        runSuspendCatching { assetRepository.getAssetDetail(assetId) }
}
