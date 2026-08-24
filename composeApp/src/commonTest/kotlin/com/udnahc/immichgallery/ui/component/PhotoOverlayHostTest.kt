package com.udnahc.immichgallery.ui.component

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PhotoOverlayHostTest {
    @Test
    fun staticSourceResolvesFoundBeforePendingAndAbsentOnlyWhenAuthoritative() {
        val pending = StaticPhotoOverlaySourceIdentity(
            ownerKey = "album:one",
            assetIds = listOf("first", "requested"),
            isPending = true
        )

        assertEquals(
            PhotoOverlayInitialIndexResolution.Found(1),
            resolveStaticPhotoOverlayInitialIndex(pending, "requested")
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Pending,
            resolveStaticPhotoOverlayInitialIndex(pending, "missing")
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Absent,
            resolveStaticPhotoOverlayInitialIndex(
                pending.copy(isPending = false),
                "missing"
            )
        )
    }

    @Test
    fun staticSourceIdentityChangesForReadinessAndOwnerTransitions() {
        val pending = StaticPhotoOverlaySourceIdentity(
            ownerKey = "album:one",
            assetIds = emptyList(),
            isPending = true
        )

        assertNotEquals(pending, pending.copy(isPending = false))
        assertNotEquals(pending, pending.copy(ownerKey = "album:two"))
    }

    @Test
    fun sourceRefreshRetainsTheSettledCurrentAsset() {
        assertEquals(
            "paged-to",
            retainedCurrentViewedAssetId(
                currentAssetId = "paged-to",
                requestedAssetId = "origin"
            )
        )
        assertEquals(
            "origin",
            retainedCurrentViewedAssetId(
                currentAssetId = null,
                requestedAssetId = "origin"
            )
        )
    }

    @Test
    fun pendingRetainsSelectionAndAbsentClearsWithoutPageZeroFallback() {
        assertEquals(
            PhotoOverlayResolvedSelection("asset", initialIndex = null),
            resolvePhotoOverlaySelection(
                requestedAssetId = "asset",
                resolution = PhotoOverlayInitialIndexResolution.Pending
            )
        )
        assertEquals(
            PhotoOverlayResolvedSelection(selectedAssetId = null, initialIndex = null),
            resolvePhotoOverlaySelection(
                requestedAssetId = "asset",
                resolution = PhotoOverlayInitialIndexResolution.Absent
            )
        )
        assertEquals(
            PhotoOverlayResolvedSelection("asset", initialIndex = 7),
            resolvePhotoOverlaySelection(
                requestedAssetId = "asset",
                resolution = PhotoOverlayInitialIndexResolution.Found(7)
            )
        )
    }

    @Test
    fun returnSourcePreparationCompletesBeforeFinalDismissal() = runTest {
        val events = mutableListOf<String>()

        prepareThenDismissPhotoOverlay(
            returnAssetId = "asset",
            onPrepare = { events += "prepare:$it" },
            onDismissed = { events += "dismiss:$it" }
        )

        assertEquals(listOf("prepare:asset", "dismiss:asset"), events)
    }

    @Test
    fun missingReturnSourceSkipsScroll() = runTest {
        val ensuredIndexes = mutableListOf<Int>()

        preparePhotoGridReturnSource(
            assetId = "missing",
            displayIndexByAssetId = mapOf("present" to 4),
            ensureVisible = ensuredIndexes::add
        )
        preparePhotoGridReturnSource(
            assetId = "present",
            displayIndexByAssetId = mapOf("present" to 4),
            ensureVisible = ensuredIndexes::add
        )

        assertEquals(listOf(4), ensuredIndexes)
    }
}
