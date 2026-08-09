package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase

class TimelineAssetAccessor(
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetFileNameUseCase: GetAssetFileNameUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase
) {
    val apiKey: String = getApiKeyUseCase()

    suspend fun getDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    suspend fun getFileName(assetId: String, fallback: String): Result<String> =
        getAssetFileNameUseCase(assetId, fallback)
}
