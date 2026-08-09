package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase

@Immutable
data class TimelineOverlayReturnTarget(
    val assetId: String?,
    val bucketTimeBucket: String?
)

class TimelineOverlayCoordinator internal constructor(
    val apiKey: String,
    private val loadAssetDetail: suspend (String) -> Result<AssetDetail>,
    private val loadAssetFileName: suspend (String, String) -> Result<String>
) {
    constructor(
        getApiKeyUseCase: GetApiKeyUseCase,
        getAssetFileNameUseCase: GetAssetFileNameUseCase,
        getAssetDetailUseCase: GetAssetDetailUseCase
    ) : this(
        apiKey = getApiKeyUseCase(),
        loadAssetDetail = { assetId -> getAssetDetailUseCase(assetId) },
        loadAssetFileName = { assetId, fallback ->
            getAssetFileNameUseCase(assetId, fallback)
        }
    )

    var returnTarget by mutableStateOf<TimelineOverlayReturnTarget?>(null)
        private set

    fun rememberReturnTarget(
        assetId: String?,
        bucketTimeBucket: String?
    ): TimelineOverlayReturnTarget? {
        val target = if (assetId == null && bucketTimeBucket == null) {
            null
        } else {
            TimelineOverlayReturnTarget(assetId, bucketTimeBucket)
        }
        returnTarget = target
        return target
    }

    suspend fun getDetail(assetId: String): Result<AssetDetail> =
        loadAssetDetail(assetId)

    suspend fun getFileName(assetId: String, fallback: String): Result<String> =
        loadAssetFileName(assetId, fallback)
}
