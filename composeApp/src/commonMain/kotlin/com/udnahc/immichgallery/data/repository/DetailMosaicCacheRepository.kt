package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.DetailMosaicCacheDao
import com.udnahc.immichgallery.data.local.entity.DetailMosaicDisplayCacheEntity
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.MosaicDisplayBandRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DetailMosaicCacheRepository(
    private val detailMosaicCacheDao: DetailMosaicCacheDao
) {
    suspend fun getDisplayCache(lookup: DetailMosaicCacheLookup): List<DetailMosaicCacheEntry> =
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.getDisplayCache(
                ownerType = lookup.ownerType.name,
                ownerId = lookup.ownerId,
                groupSize = lookup.groupSize.name,
                familiesKey = lookup.familiesKey,
                availableWidthKey = lookup.availableWidthKey,
                cellHeightKey = lookup.cellHeightKey,
                maxRowHeightKey = lookup.maxRowHeightKey,
                spacingKey = lookup.spacingKey,
                displayVersion = lookup.displayVersion
            ).mapNotNull { entity -> entity.toDomainOrNull() }
        }

    suspend fun upsertDisplayCache(entry: DetailMosaicCacheEntry) {
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.upsertDisplayCache(listOf(entry.toEntity()))
        }
    }

    suspend fun clearOwnerCache(ownerType: DetailMosaicCacheOwnerType, ownerId: String) {
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.clearOwnerCache(ownerType.name, ownerId)
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.clearAll()
        }
    }
}

private fun DetailMosaicDisplayCacheEntity.toDomainOrNull(): DetailMosaicCacheEntry? {
    val ownerType = DetailMosaicCacheOwnerType.entries.firstOrNull { it.name == ownerType } ?: return null
    val parsedGroupSize = runCatching { GroupSize.valueOf(groupSize) }.getOrNull() ?: return null
    return DetailMosaicCacheEntry(
        ownerType = ownerType,
        ownerId = ownerId,
        groupSize = parsedGroupSize,
        sectionIndex = sectionIndex,
        sectionKey = sectionKey,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = availableWidthKey,
        cellHeightKey = cellHeightKey,
        maxRowHeightKey = maxRowHeightKey,
        spacingKey = spacingKey,
        displayVersion = displayVersion,
        bands = json.decodeFromString(bandsJson),
        displayItemCount = displayItemCount,
        placeholderHeight = placeholderHeight,
        updatedAt = updatedAt
    )
}

private fun DetailMosaicCacheEntry.toEntity(): DetailMosaicDisplayCacheEntity =
    DetailMosaicDisplayCacheEntity(
        ownerType = ownerType.name,
        ownerId = ownerId,
        groupSize = groupSize.name,
        sectionIndex = sectionIndex,
        sectionKey = sectionKey,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = availableWidthKey,
        cellHeightKey = cellHeightKey,
        maxRowHeightKey = maxRowHeightKey,
        spacingKey = spacingKey,
        displayVersion = displayVersion,
        bandsJson = json.encodeToString(bands),
        displayItemCount = displayItemCount,
        placeholderHeight = placeholderHeight,
        updatedAt = updatedAt
    )

private val json = Json {
    ignoreUnknownKeys = true
}
