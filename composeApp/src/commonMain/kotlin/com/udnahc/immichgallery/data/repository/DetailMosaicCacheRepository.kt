package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.DetailMosaicCacheDao
import com.udnahc.immichgallery.data.local.entity.DetailMosaicAggregateGeometryEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicAssignmentEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicDisplayCacheEntity
import com.udnahc.immichgallery.data.local.entity.DetailMosaicSectionGeometryEntity
import com.udnahc.immichgallery.domain.model.DetailMosaicAggregateGeometryEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicAssignmentEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifacts
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifactsUpsert
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.DetailMosaicSectionGeometryEntry
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.MosaicDisplayBandRecord
import com.udnahc.immichgallery.domain.model.MosaicBandAssignmentDto
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryBand
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DetailMosaicCacheRepository(
    private val detailMosaicCacheDao: DetailMosaicCacheDao
) {
    suspend fun getArtifacts(
        lookup: DetailMosaicCacheLookup,
        geometryVersion: Int
    ): DetailMosaicArtifacts =
        DetailMosaicArtifacts(
            assignments = getAssignments(lookup),
            displayCache = getDisplayCache(lookup),
            sectionGeometry = getSectionGeometry(lookup, geometryVersion),
            aggregateGeometry = getAggregateGeometry(lookup, geometryVersion)
        )

    suspend fun getAssignments(lookup: DetailMosaicCacheLookup): List<DetailMosaicAssignmentEntry> =
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.getAssignments(
                ownerType = lookup.ownerType.name,
                ownerId = lookup.ownerId,
                groupSize = lookup.groupSize.name,
                columnCount = lookup.columnCount,
                familiesKey = lookup.familiesKey
            ).mapNotNull { entity -> entity.toAssignmentDomainOrNull() }
        }

    suspend fun getDisplayCache(lookup: DetailMosaicCacheLookup): List<DetailMosaicCacheEntry> =
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.getDisplayCache(
                ownerType = lookup.ownerType.name,
                ownerId = lookup.ownerId,
                groupSize = lookup.groupSize.name,
                columnCount = lookup.columnCount,
                familiesKey = lookup.familiesKey,
                availableWidthKey = lookup.availableWidthKey,
                cellHeightKey = lookup.cellHeightKey,
                maxRowHeightKey = lookup.maxRowHeightKey,
                spacingKey = lookup.spacingKey,
                displayVersion = lookup.displayVersion
            ).mapNotNull { entity -> entity.toDomainOrNull() }
        }

    suspend fun getSectionGeometry(
        lookup: DetailMosaicCacheLookup,
        geometryVersion: Int
    ): List<DetailMosaicSectionGeometryEntry> =
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.getSectionGeometry(
                ownerType = lookup.ownerType.name,
                ownerId = lookup.ownerId,
                groupSize = lookup.groupSize.name,
                columnCount = lookup.columnCount,
                familiesKey = lookup.familiesKey,
                availableWidthKey = lookup.availableWidthKey,
                cellHeightKey = lookup.cellHeightKey,
                maxRowHeightKey = lookup.maxRowHeightKey,
                spacingKey = lookup.spacingKey,
                geometryVersion = geometryVersion
            ).mapNotNull { entity -> entity.toSectionGeometryDomainOrNull() }
        }

    suspend fun getAggregateGeometry(
        lookup: DetailMosaicCacheLookup,
        geometryVersion: Int
    ): DetailMosaicAggregateGeometryEntry? =
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.getAggregateGeometry(
                ownerType = lookup.ownerType.name,
                ownerId = lookup.ownerId,
                groupSize = lookup.groupSize.name,
                columnCount = lookup.columnCount,
                familiesKey = lookup.familiesKey,
                availableWidthKey = lookup.availableWidthKey,
                cellHeightKey = lookup.cellHeightKey,
                maxRowHeightKey = lookup.maxRowHeightKey,
                spacingKey = lookup.spacingKey,
                geometryVersion = geometryVersion
            )?.toAggregateGeometryDomainOrNull()
        }

    suspend fun upsertAssignments(entries: List<DetailMosaicAssignmentEntry>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.upsertAssignments(entries.map { it.toEntity() })
        }
    }

    suspend fun upsertDisplayCache(entry: DetailMosaicCacheEntry) {
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.upsertDisplayCache(listOf(entry.toEntity()))
        }
    }

    suspend fun upsertSectionGeometry(entries: List<DetailMosaicSectionGeometryEntry>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.upsertSectionGeometry(entries.map { it.toEntity() })
        }
    }

    suspend fun upsertAggregateGeometry(entry: DetailMosaicAggregateGeometryEntry) {
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.upsertAggregateGeometry(entry.toEntity())
        }
    }

    suspend fun upsertArtifacts(artifacts: DetailMosaicArtifactsUpsert) {
        withContext(Dispatchers.IO) {
            if (artifacts.assignments.isNotEmpty()) {
                detailMosaicCacheDao.upsertAssignments(artifacts.assignments.map { it.toEntity() })
            }
            if (artifacts.displayCache.isNotEmpty()) {
                detailMosaicCacheDao.upsertDisplayCache(artifacts.displayCache.map { it.toEntity() })
            }
            if (artifacts.sectionGeometry.isNotEmpty()) {
                detailMosaicCacheDao.upsertSectionGeometry(artifacts.sectionGeometry.map { it.toEntity() })
            }
            artifacts.aggregateGeometry?.let { detailMosaicCacheDao.upsertAggregateGeometry(it.toEntity()) }
        }
    }

    suspend fun clearOwnerCache(ownerType: DetailMosaicCacheOwnerType, ownerId: String) {
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.clearOwnerAssignments(ownerType.name, ownerId)
            detailMosaicCacheDao.clearOwnerDisplayCache(ownerType.name, ownerId)
            detailMosaicCacheDao.clearOwnerSectionGeometry(ownerType.name, ownerId)
            detailMosaicCacheDao.clearOwnerAggregateGeometry(ownerType.name, ownerId)
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            detailMosaicCacheDao.clearAllAssignments()
            detailMosaicCacheDao.clearAllDisplayCache()
            detailMosaicCacheDao.clearAllSectionGeometry()
            detailMosaicCacheDao.clearAllAggregateGeometry()
        }
    }
}

private fun DetailMosaicAssignmentEntity.toAssignmentDomainOrNull(): DetailMosaicAssignmentEntry? {
    val ownerType = DetailMosaicCacheOwnerType.entries.firstOrNull { it.name == ownerType } ?: return null
    val parsedGroupSize = runCatching { GroupSize.valueOf(groupSize) }.getOrNull() ?: return null
    val assignments = runCatching {
        json.decodeFromString<List<MosaicBandAssignmentDto>>(assignmentsJson).map { it.toDomain() }
    }.getOrNull() ?: return null
    return DetailMosaicAssignmentEntry(
        ownerType = ownerType,
        ownerId = ownerId,
        groupSize = parsedGroupSize,
        columnCount = columnCount,
        sectionIndex = sectionIndex,
        sectionKey = sectionKey,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        assignments = assignments,
        updatedAt = updatedAt
    )
}

private fun DetailMosaicDisplayCacheEntity.toDomainOrNull(): DetailMosaicCacheEntry? {
    val ownerType = DetailMosaicCacheOwnerType.entries.firstOrNull { it.name == ownerType } ?: return null
    val parsedGroupSize = runCatching { GroupSize.valueOf(groupSize) }.getOrNull() ?: return null
    return DetailMosaicCacheEntry(
        ownerType = ownerType,
        ownerId = ownerId,
        groupSize = parsedGroupSize,
        columnCount = columnCount,
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

private fun DetailMosaicSectionGeometryEntity.toSectionGeometryDomainOrNull(): DetailMosaicSectionGeometryEntry? {
    val ownerType = DetailMosaicCacheOwnerType.entries.firstOrNull { it.name == ownerType } ?: return null
    val parsedGroupSize = runCatching { GroupSize.valueOf(groupSize) }.getOrNull() ?: return null
    return DetailMosaicSectionGeometryEntry(
        ownerType = ownerType,
        ownerId = ownerId,
        groupSize = parsedGroupSize,
        columnCount = columnCount,
        sectionIndex = sectionIndex,
        sectionKey = sectionKey,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = availableWidthKey,
        cellHeightKey = cellHeightKey,
        maxRowHeightKey = maxRowHeightKey,
        spacingKey = spacingKey,
        geometryVersion = geometryVersion,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount,
        bands = if (geometryBandsJson.isEmpty()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<MosaicSectionGeometryBand>>(geometryBandsJson) }
                .getOrDefault(emptyList())
        },
        updatedAt = updatedAt
    )
}

private fun DetailMosaicAggregateGeometryEntity.toAggregateGeometryDomainOrNull(): DetailMosaicAggregateGeometryEntry? {
    val ownerType = DetailMosaicCacheOwnerType.entries.firstOrNull { it.name == ownerType } ?: return null
    val parsedGroupSize = runCatching { GroupSize.valueOf(groupSize) }.getOrNull() ?: return null
    return DetailMosaicAggregateGeometryEntry(
        ownerType = ownerType,
        ownerId = ownerId,
        groupSize = parsedGroupSize,
        columnCount = columnCount,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = availableWidthKey,
        cellHeightKey = cellHeightKey,
        maxRowHeightKey = maxRowHeightKey,
        spacingKey = spacingKey,
        geometryVersion = geometryVersion,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount,
        updatedAt = updatedAt
    )
}

private fun DetailMosaicAssignmentEntry.toEntity(): DetailMosaicAssignmentEntity =
    DetailMosaicAssignmentEntity(
        ownerType = ownerType.name,
        ownerId = ownerId,
        groupSize = groupSize.name,
        columnCount = columnCount,
        sectionIndex = sectionIndex,
        sectionKey = sectionKey,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        assignmentsJson = json.encodeToString(assignments.map { it.toDto() }),
        updatedAt = updatedAt
    )

private fun DetailMosaicCacheEntry.toEntity(): DetailMosaicDisplayCacheEntity =
    DetailMosaicDisplayCacheEntity(
        ownerType = ownerType.name,
        ownerId = ownerId,
        groupSize = groupSize.name,
        columnCount = columnCount,
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

private fun DetailMosaicSectionGeometryEntry.toEntity(): DetailMosaicSectionGeometryEntity =
    DetailMosaicSectionGeometryEntity(
        ownerType = ownerType.name,
        ownerId = ownerId,
        groupSize = groupSize.name,
        columnCount = columnCount,
        sectionIndex = sectionIndex,
        sectionKey = sectionKey,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = availableWidthKey,
        cellHeightKey = cellHeightKey,
        maxRowHeightKey = maxRowHeightKey,
        spacingKey = spacingKey,
        geometryVersion = geometryVersion,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount,
        geometryBandsJson = json.encodeToString(bands),
        updatedAt = updatedAt
    )

private fun DetailMosaicAggregateGeometryEntry.toEntity(): DetailMosaicAggregateGeometryEntity =
    DetailMosaicAggregateGeometryEntity(
        ownerType = ownerType.name,
        ownerId = ownerId,
        groupSize = groupSize.name,
        columnCount = columnCount,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = availableWidthKey,
        cellHeightKey = cellHeightKey,
        maxRowHeightKey = maxRowHeightKey,
        spacingKey = spacingKey,
        geometryVersion = geometryVersion,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount,
        updatedAt = updatedAt
    )

private val json = Json {
    ignoreUnknownKeys = true
}
