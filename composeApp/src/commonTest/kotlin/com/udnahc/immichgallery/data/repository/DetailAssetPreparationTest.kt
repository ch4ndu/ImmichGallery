package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.entity.AssetEntity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailAssetPreparationTest {
    @Test
    fun unchangedNonTerminalPageSkipsAssetAndReferenceWrites() {
        val existing = listOf(asset("a"), asset("b"), asset("c"))

        assertTrue(
            detailPageWriteUnchanged(
                before = existing,
                candidates = listOf(asset("a"), asset("b")),
                baseOffset = 0,
                pageChanged = false,
                hasMore = true
            )
        )
    }

    @Test
    fun changedFinalPageStillWritesToRemoveStaleReferences() {
        val existing = listOf(asset("a"), asset("b"), asset("c"))

        assertFalse(
            detailPageWriteUnchanged(
                before = existing,
                candidates = listOf(asset("c")),
                baseOffset = 2,
                pageChanged = false,
                hasMore = false
            )
        )
    }

    @Test
    fun changedResolvedAspectRequiresAWrite() {
        val existing = listOf(asset("a", aspectRatio = 1f, editsResolved = true))

        assertFalse(
            detailPageWriteUnchanged(
                before = existing,
                candidates = listOf(asset("a", aspectRatio = 1.5f, editsResolved = true)),
                baseOffset = 0,
                pageChanged = false,
                hasMore = true
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
