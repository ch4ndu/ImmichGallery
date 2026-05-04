package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.entity.AssetEntity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderedAssetChangeDetectionTest {
    @Test
    fun unchangedOrderedAssetsAreNotChanged() {
        val before = listOf(asset("a"), asset("b"))
        val after = listOf(asset("a"), asset("b"))

        assertFalse(orderedAssetsChanged(before, after))
    }

    @Test
    fun assetOrderChangeIsChanged() {
        val before = listOf(asset("a"), asset("b"))
        val after = listOf(asset("b"), asset("a"))

        assertTrue(orderedAssetsChanged(before, after))
    }

    @Test
    fun assetMetadataChangeIsChanged() {
        val before = listOf(asset("a"))
        val after = listOf(asset("a", stackCount = 2))

        assertTrue(orderedAssetsChanged(before, after))
    }

    @Test
    fun editEnrichmentAspectChangeIsChanged() {
        val before = listOf(asset("a", isEdited = true, aspectRatio = 1f))
        val after = listOf(asset("a", isEdited = true, aspectRatio = 1.5f))

        assertTrue(orderedAssetsChanged(before, after))
    }

    @Test
    fun roomBookkeepingOnlyChangeIsNotChanged() {
        val before = listOf(asset("a", editsResolved = false))
        val after = listOf(asset("a", editsResolved = true))

        assertFalse(orderedAssetsChanged(before, after))
    }

    private fun asset(
        id: String,
        type: String = "IMAGE",
        fileName: String = "$id.jpg",
        createdAt: String = "2026-05-03T00:00:00Z",
        isFavorite: Boolean = false,
        stackCount: Int = 0,
        aspectRatio: Float = 1f,
        isEdited: Boolean = false,
        editsResolved: Boolean = false
    ): AssetEntity =
        AssetEntity(
            id = id,
            type = type,
            fileName = fileName,
            createdAt = createdAt,
            isFavorite = isFavorite,
            stackCount = stackCount,
            aspectRatio = aspectRatio,
            isEdited = isEdited,
            editsResolved = editsResolved
        )
}
