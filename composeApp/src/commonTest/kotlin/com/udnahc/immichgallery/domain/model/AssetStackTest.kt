package com.udnahc.immichgallery.domain.model

import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.StackResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetStackTest {
    @Test
    fun normalizationMovesPrimaryFirstAndDeduplicatesInServerOrder() {
        val stack = AssetStack.normalized(
            id = "stack-1",
            primaryAssetId = "primary",
            assets = listOf(asset("child-a"), asset("primary"), asset("child-a"), asset("child-b"))
        )

        assertEquals(listOf("primary", "child-a", "child-b"), stack.assets.map(Asset::id))
    }

    @Test
    fun originatingMemberWinsInitialSelectionOtherwisePrimaryWins() {
        val stack = AssetStack.normalized(
            id = "stack-1",
            primaryAssetId = "primary",
            assets = listOf(asset("child"), asset("primary"))
        )

        assertEquals(1, stack.initialIndex("child"))
        assertEquals(0, stack.initialIndex("missing"))
    }

    @Test
    fun missingPrimaryPreservesSupportedServerOrder() {
        val stack = AssetStack.normalized(
            id = "stack-1",
            primaryAssetId = "unsupported-primary",
            assets = listOf(asset("child-a"), asset("child-b"))
        )

        assertEquals(listOf("child-a", "child-b"), stack.assets.map(Asset::id))
        assertEquals(0, stack.initialIndex("missing"))
    }

    @Test
    fun wireMappingFiltersUnsupportedMembersBeforeNormalization() {
        val stack = StackResponse(
            id = "stack-1",
            primaryAssetId = "image",
            assets = listOf(
                AssetResponse(id = "audio", type = "AUDIO"),
                AssetResponse(id = "image", type = "IMAGE"),
                AssetResponse(id = "video", type = "VIDEO")
            )
        ).toDomain("https://example.test")

        assertEquals(listOf("image", "video"), stack.assets.map(Asset::id))
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
