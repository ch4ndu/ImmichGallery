package com.udnahc.immichgallery.domain.usecase.asset

import com.udnahc.immichgallery.data.repository.AssetRepository
import com.udnahc.immichgallery.domain.model.AssetDetail

class GetAssetDetailUseCase(
    private val assetRepository: AssetRepository
) {
    suspend operator fun invoke(assetId: String): Result<AssetDetail> =
        runCatching { assetRepository.getAssetDetail(assetId) }
}
