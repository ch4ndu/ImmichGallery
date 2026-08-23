package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.ImmichPaginationCompatibilityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AlbumRawPaginationTest {
    @Test
    fun albumCrossRefsUseRawIndexesAfterUnsupportedItemsAreFiltered() {
        val supported = supportedDetailRawAssetResponses(
            responses = listOf(
                AssetResponse(id = "image-1", type = "IMAGE"),
                AssetResponse(id = "audio-1", type = "AUDIO"),
                AssetResponse(id = "video-1", type = "VIDEO"),
                AssetResponse(id = "other-1", type = "OTHER")
            ),
            baseRawOffset = 250
        )

        val refs = albumRawCrossRefs("album-1", supported)

        assertEquals(listOf("image-1", "video-1"), refs.map { it.assetId })
        assertEquals(listOf(250, 252), refs.map { it.sortOrder })
    }

    @Test
    fun forwardJumpClearsOnlySkippedRawRange() {
        assertEquals(
            DetailRawSortRange(startSortOrder = 250, endSortOrder = 500),
            albumSkippedForwardRawRange(page = 1, nextPage = 3, size = 250)
        )
        assertEquals(
            DetailRawSortRange(startSortOrder = 500, endSortOrder = 750),
            albumSkippedForwardRawRange(page = 2, nextPage = 4, size = 250)
        )
    }

    @Test
    fun contiguousOrTerminalPageHasNoSkippedRawRange() {
        assertEquals(null, albumSkippedForwardRawRange(page = 1, nextPage = 2, size = 250))
        assertEquals(null, albumSkippedForwardRawRange(page = 1, nextPage = null, size = 250))
    }

    @Test
    fun skippedForwardRangeRejectsOverflowingTargetWindow() {
        assertFailsWith<ImmichPaginationCompatibilityException> {
            albumSkippedForwardRawRange(page = 1, nextPage = 8_589_936, size = 250)
        }
    }
}
