package com.udnahc.immichgallery.ui.util

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetStack
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.component.StackBrowserState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class PhotoGridReturnSourceTest {
    @Test
    fun currentAssetIsTheDefaultReturnTarget() {
        assertEquals(
            "current",
            resolvePhotoGridReturnAssetId(
                originAssetId = "origin",
                currentAssetId = "current"
            )
        )
    }

    @Test
    fun originModeKeepsTheCardCoverAcrossRapidCurrentPageChanges() {
        val currentPages = listOf("second", "third", "video", "second")

        assertEquals(
            List(currentPages.size) { "cover" },
            currentPages.map { current ->
                resolvePhotoGridReturnAssetId(
                    originAssetId = "cover",
                    currentAssetId = current,
                    returnTarget = PhotoOverlayReturnTarget.ORIGIN_ASSET
                )
            }
        )
    }

    @Test
    fun missingPreferredTargetsUseTheAvailableFallback() {
        assertEquals(
            "current",
            resolvePhotoGridReturnAssetId(
                originAssetId = null,
                currentAssetId = "current",
                returnTarget = PhotoOverlayReturnTarget.ORIGIN_ASSET
            )
        )
        assertEquals(
            "origin",
            resolvePhotoGridReturnAssetId(
                originAssetId = "origin",
                currentAssetId = null
            )
        )
        assertNull(
            resolvePhotoGridReturnAssetId(
                originAssetId = null,
                currentAssetId = null,
                returnTarget = PhotoOverlayReturnTarget.ORIGIN_ASSET
            )
        )
    }

    @Test
    fun nestedStackChildDoesNotReplaceTheMemoryPagerReturnChain() {
        val basePagerAsset = "memory-non-cover"
        val checking = StackBrowserState().beginPreflight(basePagerAsset)
        val available = checking.complete(
            requireNotNull(checking.activeRequest),
            Result.success(
                AssetStackLookup.Available(
                    AssetStack.normalized(
                        id = "stack",
                        primaryAssetId = basePagerAsset,
                        assets = listOf(asset(basePagerAsset), asset("stack-child"))
                    )
                )
            )
        )
        val stackOpen = available.beginSession(basePagerAsset)

        assertNotNull(stackOpen.session)
        assertNull(stackOpen.dismissStackSession().session)
        assertEquals(
            "memory-cover",
            resolvePhotoGridReturnAssetId(
                originAssetId = "memory-cover",
                currentAssetId = basePagerAsset,
                returnTarget = PhotoOverlayReturnTarget.ORIGIN_ASSET
            )
        )
    }

    private fun asset(id: String): Asset = Asset(
        id = id,
        type = AssetType.IMAGE,
        fileName = "$id.jpg",
        createdAt = "2026-08-09T00:00:00Z",
        thumbnailUrl = "https://example.test/$id/thumb",
        originalUrl = "https://example.test/$id/original"
    )
}
