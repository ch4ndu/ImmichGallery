package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketSnapshotEntity
import com.udnahc.immichgallery.data.repository.orderedAssetFingerprintChanged
import com.udnahc.immichgallery.data.repository.orderedAssetsFingerprint
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Upsert
    suspend fun upsertAssets(assets: List<AssetEntity>)

    @Query("SELECT DISTINCT timeBucket FROM timeline_asset_refs WHERE assetId IN (:assetIds)")
    suspend fun getTimelineBucketsForAssetIds(assetIds: List<String>): List<String>

    @Upsert
    suspend fun upsertTimelineBucketSnapshots(snapshots: List<TimelineBucketSnapshotEntity>)

    // Album, Person, and Timeline syncs share asset rows; every writer must refresh Timeline snapshots.
    @Transaction
    suspend fun upsertAssetsRefreshingTimelineSnapshots(assets: List<AssetEntity>) {
        if (assets.isEmpty()) return
        // Compare each chunk only against its own existing rows; never rescan the whole input per chunk.
        val changedIds = assets.chunked(ASSET_ID_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                val existing = getAssetsByIds(chunk.map { it.id }).associateBy { it.id }
                chunk.filter { orderedAssetFingerprintChanged(existing[it.id], it) }
                    .map { it.id }
            }
        upsertAssets(assets)
        if (changedIds.isEmpty()) return
        val affectedBuckets = changedIds.chunked(ASSET_ID_QUERY_CHUNK_SIZE)
            .flatMap { ids -> getTimelineBucketsForAssetIds(ids) }
            .distinct()
        refreshTimelineBucketSnapshots(affectedBuckets)
    }

    @Transaction
    suspend fun refreshTimelineBucketSnapshots(timeBuckets: List<String>) {
        if (timeBuckets.isEmpty()) return
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        upsertTimelineBucketSnapshots(
            timeBuckets.map { timeBucket ->
                val assets = getTimelineAssets(timeBucket)
                TimelineBucketSnapshotEntity(
                    timeBucket = timeBucket,
                    assetCount = assets.size,
                    fingerprintHash = orderedAssetsFingerprint(assets),
                    updatedAt = now
                )
            }
        )
    }

    @Transaction
    @Query(
        """
        SELECT a.* FROM assets a
        INNER JOIN timeline_asset_refs r ON a.id = r.assetId
        WHERE r.timeBucket = :timeBucket
        ORDER BY r.sortOrder ASC
        """
    )
    fun observeTimelineAssets(timeBucket: String): Flow<List<AssetEntity>>

    @Transaction
    @Query(
        """
        SELECT a.* FROM assets a
        INNER JOIN timeline_asset_refs r ON a.id = r.assetId
        WHERE r.timeBucket = :timeBucket
        ORDER BY r.sortOrder ASC
        """
    )
    suspend fun getTimelineAssets(timeBucket: String): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM timeline_asset_refs WHERE timeBucket = :timeBucket")
    suspend fun getTimelineAssetCount(timeBucket: String): Int

    @Query("SELECT DISTINCT timeBucket FROM timeline_asset_refs")
    suspend fun getLoadedTimelineBuckets(): List<String>

    @Query("DELETE FROM assets")
    suspend fun clearAll()

    /** Returns asset ids that still need an `/edits` fetch to fix their aspect. */
    @Query("SELECT id FROM assets WHERE id IN (:ids) AND isEdited = 1 AND editsResolved = 0")
    suspend fun getUnresolvedEditedAssetIds(ids: List<String>): List<String>

    /** Full rows for the given ids — used by the enrichment pass to read
     *  original dimensions before simulating edits. */
    @Query("SELECT * FROM assets WHERE id IN (:ids)")
    suspend fun getAssetsByIds(ids: List<String>): List<AssetEntity>

    @Query("UPDATE assets SET aspectRatio = :aspectRatio, editsResolved = 1 WHERE id = :id")
    suspend fun updateAspectRatioResolved(
        id: String,
        aspectRatio: Float
    )
}

private const val ASSET_ID_QUERY_CHUNK_SIZE = 500
