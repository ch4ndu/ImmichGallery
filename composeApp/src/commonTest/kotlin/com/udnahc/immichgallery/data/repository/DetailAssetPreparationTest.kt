package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.ImmichPaginationCompatibilityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailAssetPreparationTest {
    @Test
    fun supportedRawResponsesKeepSparseSortOrders() {
        val responses = listOf(
            AssetResponse(id = "a", type = "IMAGE"),
            AssetResponse(id = "audio", type = "AUDIO"),
            AssetResponse(id = "b", type = "VIDEO")
        )

        val supported = supportedDetailRawAssetResponses(responses, baseRawOffset = 10)

        assertEquals(
            listOf(
                DetailRawAssetResponse(responses[0], sortOrder = 10),
                DetailRawAssetResponse(responses[2], sortOrder = 12)
            ),
            supported
        )
    }

    @Test
    fun forwardJumpPageUsesActualPageRawOffset() {
        val responses = listOf(
            AssetResponse(id = "a", type = "IMAGE"),
            AssetResponse(id = "b", type = "VIDEO")
        )

        val supported = supportedDetailRawAssetResponses(responses, baseRawOffset = 500)

        assertEquals(listOf(500, 501), supported.map { it.sortOrder })
    }

    @Test
    fun unsupportedItemsAtPageEdgesStillReserveRawSlots() {
        val responses = listOf(
            AssetResponse(id = "audio", type = "AUDIO"),
            AssetResponse(id = "a", type = "IMAGE"),
            AssetResponse(id = "other", type = "OTHER")
        )

        val supported = supportedDetailRawAssetResponses(responses, baseRawOffset = 20)

        assertEquals(listOf(DetailRawAssetResponse(responses[1], sortOrder = 21)), supported)
    }

    @Test
    fun rawPageWindowUsesCheckedLongArithmetic() {
        assertEquals(
            DetailRawSortRange(startSortOrder = 500, endSortOrder = 750),
            detailRawPageWindow(page = 3, size = 250)
        )

        assertFailsWith<ImmichPaginationCompatibilityException> {
            detailRawPageWindow(page = 8_589_936, size = 250)
        }
        assertFailsWith<ImmichPaginationCompatibilityException> {
            detailRawPageWindow(page = 0, size = 250)
        }
    }

    @Test
    fun rawOffsetsRejectOrdinalsOutsideSchemaSortOrder() {
        assertFailsWith<ImmichPaginationCompatibilityException> {
            supportedDetailRawAssetResponses(
                responses = listOf(
                    AssetResponse(id = "a", type = "IMAGE"),
                    AssetResponse(id = "b", type = "IMAGE")
                ),
                baseRawOffset = Int.MAX_VALUE
            )
        }
        assertFailsWith<ImmichPaginationCompatibilityException> {
            detailRawBoundaryAfter(startSortOrder = Int.MAX_VALUE, rawCount = 1)
        }
    }

    @Test
    fun unchangedSparseRawWindowSkipsWrite() {
        val existing = listOf(asset("a"), asset("b"))
        val refs = listOf(DetailRawAssetRef("a", 10), DetailRawAssetRef("b", 12))

        assertTrue(
            detailRawPageWriteUnchanged(
                existingWindowRefs = refs,
                fetchedRefs = refs,
                tailRefsAfterWindow = emptyList(),
                existingAssetsById = existing.associateBy { it.id },
                candidates = existing,
                hasMore = true
            )
        )
    }

    @Test
    fun legacyCompactRefsAreNotTreatedAsUnchanged() {
        val existing = listOf(asset("a"), asset("b"))

        assertFalse(
            detailRawPageWriteUnchanged(
                existingWindowRefs = listOf(DetailRawAssetRef("a", 10), DetailRawAssetRef("b", 11)),
                fetchedRefs = listOf(DetailRawAssetRef("a", 10), DetailRawAssetRef("b", 12)),
                tailRefsAfterWindow = emptyList(),
                existingAssetsById = existing.associateBy { it.id },
                candidates = existing,
                hasMore = true
            )
        )
    }

    @Test
    fun shortNonTerminalPageWindowDetectsStaleRowsBeforeNextPage() {
        val existing = listOf(asset("a"), asset("b"))

        assertFalse(
            detailRawPageWriteUnchanged(
                existingWindowRefs = listOf(
                    DetailRawAssetRef("a", 10),
                    DetailRawAssetRef("b", 11),
                    DetailRawAssetRef("stale", 12)
                ),
                fetchedRefs = listOf(DetailRawAssetRef("a", 10), DetailRawAssetRef("b", 11)),
                tailRefsAfterWindow = emptyList(),
                existingAssetsById = existing.associateBy { it.id },
                candidates = existing,
                hasMore = true
            )
        )
    }

    @Test
    fun finalRawWindowUsesRawBoundaryForTailCleanup() {
        val existing = listOf(asset("a"))
        val refs = listOf(DetailRawAssetRef("a", 10))

        assertFalse(
            detailRawPageWriteUnchanged(
                existingWindowRefs = refs,
                fetchedRefs = refs,
                tailRefsAfterWindow = listOf(DetailRawAssetRef("stale", 13)),
                existingAssetsById = existing.associateBy { it.id },
                candidates = existing,
                hasMore = false
            )
        )
    }

    @Test
    fun fullRefreshComparesSparseRefsAndAssetRows() {
        val existing = listOf(asset("a"), asset("b"))

        assertTrue(
            detailRawFullWriteUnchanged(
                existingRefs = listOf(DetailRawAssetRef("a", 0), DetailRawAssetRef("b", 2)),
                fetchedRefs = listOf(DetailRawAssetRef("a", 0), DetailRawAssetRef("b", 2)),
                existingAssetsById = existing.associateBy { it.id },
                candidates = existing
            )
        )
        assertFalse(
            detailRawFullWriteUnchanged(
                existingRefs = listOf(DetailRawAssetRef("a", 0), DetailRawAssetRef("b", 1)),
                fetchedRefs = listOf(DetailRawAssetRef("a", 0), DetailRawAssetRef("b", 2)),
                existingAssetsById = existing.associateBy { it.id },
                candidates = existing
            )
        )
    }

    private fun asset(
        id: String,
        aspectRatio: Float = 1f,
        editsResolved: Boolean = false
    ) = AssetEntity(
        id = id,
        type = "IMAGE",
        fileName = "$id.jpg",
        createdAt = "2026-01-01T00:00:00.000Z",
        isFavorite = false,
        stackCount = 0,
        aspectRatio = aspectRatio,
        isEdited = editsResolved,
        editsResolved = editsResolved
    )
}
