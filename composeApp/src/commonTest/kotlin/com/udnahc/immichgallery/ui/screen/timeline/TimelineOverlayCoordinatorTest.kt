package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.AssetDetail
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TimelineOverlayCoordinatorTest {
    @Test
    fun returnTargetUpdatesAsOneAssetAndBucketPair() {
        val coordinator = coordinator()

        val target = coordinator.rememberReturnTarget("asset-2", "2026-05")

        assertEquals(TimelineOverlayReturnTarget("asset-2", "2026-05"), target)
        assertSame(target, coordinator.returnTarget)

        coordinator.rememberReturnTarget(null, null)
        assertNull(coordinator.returnTarget)
    }

    @Test
    fun overlayReadsUseExactAssetArguments() = runTest {
        var detailAssetId: String? = null
        var fileNameArguments: Pair<String, String>? = null
        val detail = assetDetail("asset-7")
        val coordinator = coordinator(
            loadAssetDetail = { assetId ->
                detailAssetId = assetId
                Result.success(detail)
            },
            loadAssetFileName = { assetId, fallback ->
                fileNameArguments = assetId to fallback
                Result.success("resolved.jpg")
            }
        )

        assertSame(detail, coordinator.getDetail("asset-7").getOrThrow())
        assertEquals("asset-7", detailAssetId)
        assertEquals("resolved.jpg", coordinator.getFileName("asset-7", "fallback.jpg").getOrThrow())
        assertEquals("asset-7" to "fallback.jpg", fileNameArguments)
        assertEquals("api-key", coordinator.apiKey)
    }

    private fun coordinator(
        loadAssetDetail: suspend (String) -> Result<AssetDetail> = {
            Result.failure(IllegalStateException("not used"))
        },
        loadAssetFileName: suspend (String, String) -> Result<String> = { _, _ ->
            Result.failure(IllegalStateException("not used"))
        }
    ): TimelineOverlayCoordinator = TimelineOverlayCoordinator(
        apiKey = "api-key",
        loadAssetDetail = loadAssetDetail,
        loadAssetFileName = loadAssetFileName
    )

    private fun assetDetail(id: String): AssetDetail = AssetDetail(
        id = id,
        fileName = "$id.jpg",
        dateTime = null,
        cameraMake = null,
        cameraModel = null,
        lensModel = null,
        focalLength = null,
        aperture = null,
        shutterSpeed = null,
        iso = null,
        latitude = null,
        longitude = null,
        city = null,
        state = null,
        country = null,
        fileSizeInByte = null,
        width = null,
        height = null,
        description = null,
        people = emptyList()
    )
}
