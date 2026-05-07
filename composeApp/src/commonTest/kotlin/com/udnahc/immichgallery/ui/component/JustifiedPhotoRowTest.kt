package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowItemKind
import kotlin.test.Test
import kotlin.test.assertTrue

class JustifiedPhotoRowTest {
    @Test
    fun mosaicFallbackRowsUseWeightedFullWidthLayoutEvenWhenIncomplete() {
        val row = row(isComplete = false, kind = RowItemKind.MOSAIC_FALLBACK)

        assertTrue(row.usesWeightedFullWidthLayout())
    }

    @Test
    fun standardIncompleteRowsKeepNaturalWidthLayout() {
        val row = row(isComplete = false, kind = RowItemKind.STANDARD)

        assertTrue(!row.usesWeightedFullWidthLayout())
    }

    private fun row(isComplete: Boolean, kind: RowItemKind): RowItem =
        RowItem(
            gridKey = "row",
            bucketIndex = 0,
            sectionLabel = "section",
            photos = listOf(
                PhotoItem(
                    gridKey = "p_asset",
                    bucketIndex = 0,
                    sectionLabel = "section",
                    asset = Asset(
                        id = "asset",
                        type = AssetType.IMAGE,
                        fileName = "asset.jpg",
                        createdAt = "2026-01-01T00:00:00Z",
                        thumbnailUrl = "",
                        originalUrl = "",
                        aspectRatio = 1f
                    )
                )
            ),
            rowHeight = 100f,
            isComplete = isComplete,
            kind = kind
        )
}
