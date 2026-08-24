package com.udnahc.immichgallery.ui.screen.memories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MemoriesOverlaySourceTest {
    @Test
    fun loadingSourceWithoutOwnerIsPending() {
        assertEquals(
            MemoryOverlaySourceResolution.Pending,
            resolveMemoryOverlaySource(source(isPending = true), "asset")
        )
    }

    @Test
    fun hydratedSourceRecoversOwnerFromItsCover() {
        val source = source(
            owners = listOf(
                owner("first", "one"),
                owner("second", "requested", "three")
            )
        )

        assertEquals(
            MemoryOverlaySourceResolution.Found("second", assetIndex = 0),
            resolveMemoryOverlaySource(source, "requested")
        )
    }

    @Test
    fun selectedOwnerWinsDuplicateMembership() {
        val source = source(
            selectedMemoryId = "selected",
            owners = listOf(
                owner("first", "duplicate"),
                owner("selected", "duplicate", "after")
            )
        )

        assertEquals(
            MemoryOverlaySourceResolution.Found("selected", assetIndex = 0),
            resolveMemoryOverlaySource(source, "duplicate")
        )
    }

    @Test
    fun missingSelectedOwnerFallsBackToLoadedOwner() {
        val source = source(
            selectedMemoryId = "stale",
            owners = listOf(owner("current", "requested"))
        )

        assertEquals(
            MemoryOverlaySourceResolution.Found("current", assetIndex = 0),
            resolveMemoryOverlaySource(source, "requested")
        )
    }

    @Test
    fun existingSelectedOwnerNeverTransfersToAnotherMemory() {
        val source = source(
            selectedMemoryId = "selected",
            owners = listOf(
                owner("selected", "other"),
                owner("different", "requested")
            )
        )

        assertEquals(
            MemoryOverlaySourceResolution.Absent,
            resolveMemoryOverlaySource(source, "requested")
        )
        assertEquals(
            MemoryOverlaySourceResolution.Pending,
            resolveMemoryOverlaySource(source.copy(isPending = true), "requested")
        )
    }

    @Test
    fun restoredOriginNeverTransfersToANonCoverAsset() {
        val source = source(
            owners = listOf(owner("different", "cover", "requested"))
        )

        assertEquals(
            MemoryOverlaySourceResolution.Absent,
            resolveMemoryOverlaySource(source, "requested")
        )
    }

    @Test
    fun authoritativeSourceWithoutAssetIsAbsentAndChangesPendingKey() {
        val pending = source(isPending = true)
        val authoritative = pending.copy(isPending = false)

        assertNotEquals(pending, authoritative)
        assertEquals(
            MemoryOverlaySourceResolution.Absent,
            resolveMemoryOverlaySource(authoritative, "missing")
        )
    }

    @Test
    fun failedReadRemainsPendingAndRetryCanRecoverTheCover() {
        val failed = source(isPending = true)
        val retrying = failed.copy(requestGeneration = 2L)
        val completed = retrying.copy(
            isPending = false,
            owners = listOf(owner("recovered", "requested"))
        )

        assertEquals(
            MemoryOverlaySourceResolution.Pending,
            resolveMemoryOverlaySource(failed, "requested")
        )
        assertNotEquals(failed, retrying)
        assertEquals(
            MemoryOverlaySourceResolution.Found("recovered", 0),
            resolveMemoryOverlaySource(completed, "requested")
        )
    }

    private fun source(
        isPending: Boolean = false,
        selectedMemoryId: String? = null,
        owners: List<MemoryOverlayOwnerSource> = emptyList()
    ) = MemoryOverlaySourceIdentity(
        requestGeneration = 1L,
        isPending = isPending,
        selectedMemoryId = selectedMemoryId,
        owners = owners
    )

    private fun owner(
        memoryId: String,
        vararg assetIds: String
    ) = MemoryOverlayOwnerSource(memoryId, assetIds.toList())
}
