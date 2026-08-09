package com.udnahc.immichgallery.domain.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AssetGroupingTest {
    @Test
    fun monthGroupingPreservesMalformedDatesInUnknownGroup() {
        val valid = asset("valid", "2026-05-10T12:00:00Z")
        val missing = asset("missing", "")
        val malformed = asset("malformed", "not-a-date")

        val groups = groupAssets(listOf(missing, valid, malformed), GroupSize.MONTH)

        assertEquals(listOf("valid"), groups[0].assets.map(Asset::id))
        assertEquals(AssetGroupDisplayLabel.Month(2026, 5), groups[0].displayLabel)
        assertEquals(setOf("missing", "malformed"), groups[1].assets.mapTo(mutableSetOf(), Asset::id))
        assertIs<AssetGroupDisplayLabel.UnknownDate>(groups[1].displayLabel)
    }

    @Test
    fun dayGroupingPreservesMalformedDatesInUnknownGroup() {
        val valid = asset("valid", "2026-05-10")
        val missing = asset("missing", "")

        val groups = groupAssets(listOf(missing, valid), GroupSize.DAY)

        assertEquals(AssetGroupDisplayLabel.Day(LocalDate(2026, 5, 10)), groups[0].displayLabel)
        assertEquals(listOf("valid"), groups[0].assets.map(Asset::id))
        assertEquals(listOf("missing"), groups[1].assets.map(Asset::id))
        assertIs<AssetGroupDisplayLabel.UnknownDate>(groups[1].displayLabel)
    }

    @Test
    fun noGroupingPreservesOriginalOrderWithoutHeader() {
        val assets = listOf(asset("first", ""), asset("second", "2026-05-10"))

        val group = groupAssets(assets, GroupSize.NONE).single()

        assertEquals("", group.label)
        assertEquals(null, group.displayLabel)
        assertEquals(assets, group.assets)
    }

    private fun asset(id: String, createdAt: String): Asset = Asset(
        id = id,
        type = AssetType.IMAGE,
        fileName = "$id.jpg",
        createdAt = createdAt,
        thumbnailUrl = "https://example.test/$id/thumb",
        originalUrl = "https://example.test/$id/original"
    )
}
