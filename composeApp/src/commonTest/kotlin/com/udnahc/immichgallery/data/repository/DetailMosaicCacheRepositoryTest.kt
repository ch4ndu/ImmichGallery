package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.DetailMosaicCacheDao
import com.udnahc.immichgallery.data.local.entity.DetailMosaicAggregateGeometryEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicAssignmentEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicDisplayCacheEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicSectionGeometryEntity
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.MosaicDisplayItemRecord
import com.udnahc.immichgallery.domain.model.MosaicDisplayItemRecordKind
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailMosaicCacheRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun getDisplayCacheSkipsMalformedJsonRowAndRetainsValidSiblingRows() = runTest {
        val validItemsJson = json.encodeToString(
            listOf(
                MosaicDisplayItemRecord(
                    kind = MosaicDisplayItemRecordKind.MOSAIC_FALLBACK_ROW,
                    sourceStartIndex = 0,
                    sourceCount = 1,
                    height = 120f,
                    assetIds = listOf("asset-1")
                )
            )
        )
        val dao = FakeDetailMosaicCacheDao(
            displayCacheRows = listOf(
                displayCacheEntity(sectionIndex = 0, sectionKey = "s0", itemsJson = validItemsJson),
                displayCacheEntity(sectionIndex = 1, sectionKey = "s1", itemsJson = "{not-valid-json"),
                displayCacheEntity(sectionIndex = 2, sectionKey = "s2", itemsJson = validItemsJson)
            )
        )
        val repository = DetailMosaicCacheRepository(dao, SessionStateRepository())

        val entries = repository.getDisplayCache(defaultLookup())

        assertEquals(listOf("s0", "s2"), entries.map { it.sectionKey })
        assertEquals(listOf(1, 1), entries.map { it.displayRecords.size })
    }

    @Test
    fun getSectionGeometrySkipsMalformedNonEmptyJsonWhilePreservingEmptyJsonCompatibility() = runTest {
        val validRangesJson = json.encodeToString(
            listOf(
                MosaicSectionGeometryRange(
                    sourceStartIndex = 0,
                    sourceCount = 2,
                    height = 120f
                )
            )
        )
        val dao = FakeDetailMosaicCacheDao(
            sectionGeometryRows = listOf(
                sectionGeometryEntity(sectionIndex = 0, sectionKey = "valid", geometryRangesJson = validRangesJson),
                sectionGeometryEntity(sectionIndex = 1, sectionKey = "empty", geometryRangesJson = ""),
                sectionGeometryEntity(sectionIndex = 2, sectionKey = "corrupt", geometryRangesJson = "[broken")
            )
        )
        val repository = DetailMosaicCacheRepository(dao, SessionStateRepository())

        val entries = repository.getSectionGeometry(defaultLookup(), geometryVersion = 1)

        assertEquals(listOf("valid", "empty"), entries.map { it.sectionKey })
        assertEquals(1, entries[0].ranges.size)
        assertEquals(emptyList(), entries[1].ranges)
    }

    private fun defaultLookup(): DetailMosaicCacheLookup =
        DetailMosaicCacheLookup(
            ownerType = DetailMosaicCacheOwnerType.ALBUM,
            ownerId = "album-1",
            groupSize = GroupSize.MONTH,
            columnCount = 5,
            familiesKey = "2,3",
            availableWidthKey = 400,
            cellHeightKey = 8000,
            maxRowHeightKey = 24000,
            spacingKey = 200,
            displayVersion = 1
        )

    private fun displayCacheEntity(
        sectionIndex: Int,
        sectionKey: String,
        itemsJson: String
    ): DetailMosaicDisplayCacheEntity =
        DetailMosaicDisplayCacheEntity(
            ownerType = DetailMosaicCacheOwnerType.ALBUM.name,
            ownerId = "album-1",
            groupSize = GroupSize.MONTH.name,
            columnCount = 5,
            sectionIndex = sectionIndex,
            sectionKey = sectionKey,
            familiesKey = "2,3",
            assetFingerprint = "fp-$sectionKey",
            availableWidthKey = 400,
            cellHeightKey = 8000,
            maxRowHeightKey = 24000,
            spacingKey = 200,
            displayVersion = 1,
            itemsJson = itemsJson,
            displayItemCount = 1,
            placeholderHeight = 100f,
            updatedAt = 1L
        )

    private fun sectionGeometryEntity(
        sectionIndex: Int,
        sectionKey: String,
        geometryRangesJson: String
    ): DetailMosaicSectionGeometryEntity =
        DetailMosaicSectionGeometryEntity(
            ownerType = DetailMosaicCacheOwnerType.ALBUM.name,
            ownerId = "album-1",
            groupSize = GroupSize.MONTH.name,
            columnCount = 5,
            sectionIndex = sectionIndex,
            sectionKey = sectionKey,
            familiesKey = "2,3",
            assetFingerprint = "fp-$sectionKey",
            availableWidthKey = 400,
            cellHeightKey = 8000,
            maxRowHeightKey = 24000,
            spacingKey = 200,
            geometryVersion = 1,
            placeholderHeight = 100f,
            displayItemCount = 1,
            geometryRangesJson = geometryRangesJson,
            updatedAt = 1L
        )

    private class FakeDetailMosaicCacheDao(
        private val displayCacheRows: List<DetailMosaicDisplayCacheEntity> = emptyList(),
        private val sectionGeometryRows: List<DetailMosaicSectionGeometryEntity> = emptyList()
    ) : DetailMosaicCacheDao {
        override suspend fun getAssignments(
            ownerType: String,
            ownerId: String,
            groupSize: String,
            columnCount: Int,
            familiesKey: String
        ): List<DetailMosaicAssignmentEntity> = emptyList()

        override suspend fun getDisplayCache(
            ownerType: String,
            ownerId: String,
            groupSize: String,
            columnCount: Int,
            familiesKey: String,
            availableWidthKey: Int,
            cellHeightKey: Int,
            maxRowHeightKey: Int,
            spacingKey: Int,
            displayVersion: Int
        ): List<DetailMosaicDisplayCacheEntity> = displayCacheRows

        override suspend fun getSectionGeometry(
            ownerType: String,
            ownerId: String,
            groupSize: String,
            columnCount: Int,
            familiesKey: String,
            availableWidthKey: Int,
            cellHeightKey: Int,
            maxRowHeightKey: Int,
            spacingKey: Int,
            geometryVersion: Int
        ): List<DetailMosaicSectionGeometryEntity> = sectionGeometryRows

        override suspend fun getAggregateGeometry(
            ownerType: String,
            ownerId: String,
            groupSize: String,
            columnCount: Int,
            familiesKey: String,
            availableWidthKey: Int,
            cellHeightKey: Int,
            maxRowHeightKey: Int,
            spacingKey: Int,
            geometryVersion: Int
        ): DetailMosaicAggregateGeometryEntity? = null

        override suspend fun upsertAssignments(rows: List<DetailMosaicAssignmentEntity>) = Unit
        override suspend fun upsertDisplayCache(rows: List<DetailMosaicDisplayCacheEntity>) = Unit
        override suspend fun upsertSectionGeometry(rows: List<DetailMosaicSectionGeometryEntity>) = Unit
        override suspend fun upsertAggregateGeometry(row: DetailMosaicAggregateGeometryEntity) = Unit
        override suspend fun clearOwnerDisplayCache(ownerType: String, ownerId: String) = Unit
        override suspend fun clearOwnerAssignments(ownerType: String, ownerId: String) = Unit
        override suspend fun clearOwnerSectionGeometry(ownerType: String, ownerId: String) = Unit
        override suspend fun clearOwnerAggregateGeometry(ownerType: String, ownerId: String) = Unit
        override suspend fun clearOwnerDisplayCaches(ownerType: String, ownerIds: List<String>) = Unit
        override suspend fun clearOwnerAssignmentsForOwners(ownerType: String, ownerIds: List<String>) = Unit
        override suspend fun clearOwnerSectionGeometryForOwners(ownerType: String, ownerIds: List<String>) = Unit
        override suspend fun clearOwnerAggregateGeometryForOwners(ownerType: String, ownerIds: List<String>) = Unit
        override suspend fun clearAllDisplayCache() = Unit
        override suspend fun clearAllAssignments() = Unit
        override suspend fun clearAllSectionGeometry() = Unit
        override suspend fun clearAllAggregateGeometry() = Unit
    }
}
