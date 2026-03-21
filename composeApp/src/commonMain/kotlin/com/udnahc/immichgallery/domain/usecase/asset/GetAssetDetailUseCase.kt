package com.udnahc.immichgallery.domain.usecase.asset

import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.toDetail

class GetAssetDetailUseCase(
    private val apiService: ImmichApiService,
    private val serverConfigRepository: ServerConfigRepository
) {
    suspend operator fun invoke(assetId: String): Result<AssetDetail> = runCatching {
        val baseUrl = serverConfigRepository.getServerUrl()
        val response = apiService.getAssetInfo(assetId)
        response.toDetail(baseUrl)
    }
}
