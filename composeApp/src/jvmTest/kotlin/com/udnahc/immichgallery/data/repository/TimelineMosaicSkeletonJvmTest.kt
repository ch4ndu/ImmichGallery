package com.udnahc.immichgallery.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.MIGRATION_1_2
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketArtifacts
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketGeometryArtifact
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicSectionGeometryArtifact
import com.udnahc.immichgallery.domain.model.TimelineMosaicSectionManifestEntry
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import com.udnahc.immichgallery.domain.model.timelineMosaicFamiliesKey
import com.udnahc.immichgallery.test.TestSettings
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimelineMosaicSkeletonJvmTest {
    @Test
    fun snapshotSurvivesBucketMetadataReplacement() = runTest {
        withDatabase {
            seedBucket("2026-01", listOf(asset("a"), asset("b")))
            val before = database.timelineDao().getBucketSnapshots(listOf("2026-01")).single()

            database.timelineDao().replaceBuckets(
                listOf(
                    TimelineBucketEntity("2026-02", "February", 4, 0),
                    TimelineBucketEntity("2026-01", "January", 3, 1)
                )
            )

            assertEquals(before, database.timelineDao().getBucketSnapshots(listOf("2026-01")).single())
        }
    }

    @Test
    fun sharedAssetWriteFromAnotherOwnerRefreshesTimelineSnapshot() = runTest {
        withDatabase {
            seedBucket("2026-01", listOf(asset("a"), asset("b")))
            val before = database.timelineDao().getBucketSnapshots(listOf("2026-01")).single()

            database.assetDao().upsertAssetsRefreshingTimelineSnapshots(listOf(asset("b")))
            assertEquals(before, database.timelineDao().getBucketSnapshots(listOf("2026-01")).single())

            database.assetDao().upsertAssetsRefreshingTimelineSnapshots(
                listOf(asset("b", aspectRatio = 1.5f), asset("album-only"))
            )
            val after = database.timelineDao().getBucketSnapshots(listOf("2026-01")).single()
            assertEquals(
                orderedAssetsFingerprint(database.assetDao().getTimelineAssets("2026-01")),
                after.fingerprintHash
            )
            assertEquals(true, after.fingerprintHash != before.fingerprintHash)
            assertEquals(listOf("2026-01"), database.timelineDao().getAllBucketSnapshots().map { it.timeBucket })
        }
    }

    @Test
    fun skeletonReadReturnsPersistedRangesUntilSharedAssetChanges() = runTest {
        withDatabase {
            seedBucket("2026-01", listOf(asset("a"), asset("b")))
            repository.replaceTimelineMosaicArtifactsForBucketConfig(artifacts("2026-01")).getOrThrow()

            val skeleton = repository.getTimelineMosaicSkeleton(
                groupSize = TimelineGroupSize.MONTH,
                columnCount = COLUMNS,
                families = families,
                geometryRequest = request
            ).getOrThrow()
            assertEquals(
                listOf(range(0, 1, 100f), range(1, 1, 200f)),
                assertNotNull(skeleton.buckets["2026-01"]).sections.single().ranges
            )

            database.assetDao().upsertAssetsRefreshingTimelineSnapshots(listOf(asset("a", aspectRatio = 2f)))

            val afterChange = repository.getTimelineMosaicSkeleton(
                groupSize = TimelineGroupSize.MONTH,
                columnCount = COLUMNS,
                families = families,
                geometryRequest = request,
                timeBuckets = setOf("2026-01")
            ).getOrThrow()
            assertNull(afterChange.buckets["2026-01"])
        }
    }

    private class Harness(
        val database: AppDatabase,
        val repository: TimelineMosaicCacheRepository
    ) {
        suspend fun seedBucket(timeBucket: String, assets: List<AssetEntity>) {
            database.assetDao().upsertAssetsRefreshingTimelineSnapshots(assets)
            database.timelineDao().replaceBucketRefs(
                timeBucket,
                assets.mapIndexed { index, asset -> TimelineAssetCrossRef(timeBucket, asset.id, index) }
            )
            database.assetDao().refreshTimelineBucketSnapshots(listOf(timeBucket))
        }
    }

    private suspend fun withDatabase(block: suspend Harness.() -> Unit) {
        val tempDir = Files.createTempDirectory("timeline-skeleton").toFile()
        val database = Room.databaseBuilder<AppDatabase>(name = tempDir.resolve("test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_1_2)
            .build()
        val config = ServerConfigRepository(TestSettings()).apply {
            replaceSession("https://immich.example", "test-key")
        }
        val repository = TimelineMosaicCacheRepository(
            timelineDao = database.timelineDao(),
            assetDao = database.assetDao(),
            serverConfigRepository = config,
            sessionStateRepository = SessionStateRepository()
        )
        try {
            Harness(database, repository).block()
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    private suspend fun Harness.artifacts(timeBucket: String): TimelineMosaicBucketArtifacts {
        val fingerprint = orderedAssetsFingerprint(database.assetDao().getTimelineAssets(timeBucket))
        val sectionKey = "$timeBucket|month"
        val familiesKey = timelineMosaicFamiliesKey(families.normalizedMosaicFamilies())
        return TimelineMosaicBucketArtifacts(
            timeBucket = timeBucket,
            groupMode = TimelineGroupSize.MONTH.apiValue,
            columnCount = COLUMNS,
            familiesKey = familiesKey,
            assignments = emptyList(),
            sectionGeometry = listOf(
                TimelineMosaicSectionGeometryArtifact(
                    timeBucket = timeBucket,
                    groupMode = TimelineGroupSize.MONTH.apiValue,
                    sectionKey = sectionKey,
                    columnCount = COLUMNS,
                    familiesKey = familiesKey,
                    assetFingerprint = fingerprint,
                    geometryRequest = request,
                    placeholderHeight = 302f,
                    displayItemCount = 2,
                    ranges = listOf(range(0, 1, 100f), range(1, 1, 200f)),
                    updatedAt = 1L
                )
            ),
            bucketGeometry = TimelineMosaicBucketGeometryArtifact(
                timeBucket = timeBucket,
                groupMode = TimelineGroupSize.MONTH.apiValue,
                columnCount = COLUMNS,
                familiesKey = familiesKey,
                assetFingerprint = fingerprint,
                geometryRequest = request,
                placeholderHeight = 302f,
                displayItemCount = 1,
                sectionManifest = listOf(TimelineMosaicSectionManifestEntry(sectionKey, 2)),
                groupingZoneId = "",
                updatedAt = 1L
            )
        )
    }

    private fun asset(id: String, aspectRatio: Float = 1f) = AssetEntity(
        id = id,
        type = "IMAGE",
        fileName = "$id.jpg",
        createdAt = "2026-01-01T00:00:00Z",
        isFavorite = false,
        stackCount = 1,
        aspectRatio = aspectRatio
    )

    private fun range(start: Int, count: Int, height: Float) =
        MosaicSectionGeometryRange(sourceStartIndex = start, sourceCount = count, height = height)

    private val families = MosaicTemplateFamily.defaultSet()
    private val request = TimelineMosaicGeometryRequest(400f, 1000f, GRID_SPACING_DP)

    private companion object {
        const val COLUMNS = 4
    }
}
