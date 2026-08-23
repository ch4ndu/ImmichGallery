package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.AssetResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonPageChangeDetectionTest {
    @Test
    fun personCrossRefsUseRawIndexesAfterUnsupportedItemsAreFiltered() {
        val supported = supportedDetailRawAssetResponses(
            responses = listOf(
                AssetResponse(id = "image-1", type = "IMAGE"),
                AssetResponse(id = "audio-1", type = "AUDIO"),
                AssetResponse(id = "video-1", type = "VIDEO"),
                AssetResponse(id = "other-1", type = "OTHER")
            ),
            baseRawOffset = 250
        )

        val refs = personRawCrossRefs("person-1", supported)

        assertEquals(listOf("image-1", "video-1"), refs.map { it.assetId })
        assertEquals(listOf(250, 252), refs.map { it.sortOrder })
    }

    @Test
    fun rawRefWindowDetectsLegacyCompactOrderAndTerminalTail() {
        val fetched = listOf(
            DetailRawAssetRef("image-1", 0),
            DetailRawAssetRef("video-1", 2)
        )

        assertTrue(
            detailRawWindowChanged(
                existingWindowRefs = listOf(
                    DetailRawAssetRef("image-1", 0),
                    DetailRawAssetRef("video-1", 1)
                ),
                fetchedRefs = fetched,
                tailRefsAfterWindow = emptyList(),
                hasMore = true
            )
        )
        assertTrue(
            detailRawWindowChanged(
                existingWindowRefs = fetched,
                fetchedRefs = fetched,
                tailRefsAfterWindow = listOf(DetailRawAssetRef("old-tail", 3)),
                hasMore = false
            )
        )
        assertFalse(
            detailRawWindowChanged(
                existingWindowRefs = fetched,
                fetchedRefs = fetched,
                tailRefsAfterWindow = listOf(DetailRawAssetRef("next-page", 250)),
                hasMore = true
            )
        )
    }
}
