package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimelineBucketAssetsRuntimeCacheTest {
    @Test
    fun getOrLoadCurrentInstallsAndReturnsCurrentEntry() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val generation = generation(1L)
        val loadedAssets = listOf(asset("one"))
        var loadCount = 0
        cache.acceptCurrentGeneration("2026-01", generation)

        val first = cache.getOrLoadCurrent("2026-01", generation) {
            loadCount++
            loadedAssets
        }
        val second = cache.getOrLoadCurrent("2026-01", generation) {
            loadCount++
            listOf(asset("two"))
        }

        assertSame(loadedAssets, first)
        assertSame(loadedAssets, second)
        assertSame(loadedAssets, cache.getCurrent("2026-01", generation))
        assertSame(loadedAssets, cache.assetsForRender("2026-01", generation))
        assertEquals(1, loadCount)
        assertTrue(cache.containsCurrent("2026-01", generation))
        assertEquals(setOf("2026-01"), cache.currentKeys(mapOf("2026-01" to generation)))
    }

    @Test
    fun invalidationBetweenLoadAndInstallRejectsLoadedAssets() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val generation = generation(1L)
        cache.acceptCurrentGeneration("2026-01", generation)

        val loaded = cache.getOrLoadCurrent("2026-01", generation) {
            cache.invalidate("2026-01")
            listOf(asset("stale"))
        }

        assertNull(loaded)
        assertNull(cache.assetsForRender("2026-01", generation))
        assertFalse(cache.containsCurrent("2026-01", generation))
    }

    @Test
    fun staleRestoreDoesNotClobberNewerWrite() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val oldGeneration = generation(1L)
        val newGeneration = generation(2L)
        val oldAssets = listOf(asset("old"))
        val newAssets = listOf(asset("new"))
        cache.acceptCurrentGeneration("2026-01", oldGeneration)

        val oldWrite = cache.putIfCurrent(origin(cache, "2026-01", oldGeneration), oldAssets)
        requireNotNull(oldWrite)
        cache.invalidate("2026-01", newGeneration)
        val newWrite = cache.putIfCurrent(origin(cache, "2026-01", newGeneration), newAssets)
        requireNotNull(newWrite)

        cache.restoreIfOwned(oldWrite)

        assertSame(newAssets, cache.assetsForRender("2026-01", newGeneration))
        assertFalse(cache.containsCurrent("2026-01", oldGeneration))
        assertTrue(cache.containsCurrent("2026-01", newGeneration))
    }

    @Test
    fun generationMismatchReturnsNullAndDoesNotOverwriteCurrentEntry() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val currentGeneration = generation(1L)
        val staleGeneration = generation(0L)
        val currentAssets = listOf(asset("current"))
        cache.acceptCurrentGeneration("2026-01", currentGeneration)

        val write = cache.putIfCurrent(origin(cache, "2026-01", currentGeneration), currentAssets)
        requireNotNull(write)

        assertNull(cache.getCurrent("2026-01", staleGeneration))
        assertNull(
            cache.getOrLoadCurrent("2026-01", staleGeneration) {
                listOf(asset("stale"))
            }
        )
        assertNull(cache.originFor("2026-01", staleGeneration))
        assertSame(currentAssets, cache.assetsForRender("2026-01", currentGeneration))
        assertEquals(emptySet(), cache.currentKeys(mapOf("2026-01" to staleGeneration)))
    }

    @Test
    fun assetsForRenderTracksObservableMapUpdatesAndInvalidation() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val generation = generation(1L)
        val assets = listOf(asset("visible"))
        cache.acceptCurrentGeneration("2026-01", generation)

        assertNull(cache.assetsForRender("2026-01", generation))

        val write = cache.putIfCurrent(origin(cache, "2026-01", generation), assets)
        requireNotNull(write)

        assertSame(assets, cache.assetsForRender("2026-01", generation))
        assertEquals(setOf("2026-01"), cache.currentKeys { generation })

        cache.invalidate("2026-01")

        assertNull(cache.assetsForRender("2026-01", generation))
        assertEquals(emptySet(), cache.currentKeys { generation })
    }

    @Test
    fun stalePutAfterInvalidateIsRejectedEvenWhenEntryIsAbsent() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val oldGeneration = generation(1L)
        cache.acceptCurrentGeneration("2026-01", oldGeneration)
        val staleOrigin = origin(cache, "2026-01", oldGeneration)

        cache.invalidate("2026-01")

        assertNull(cache.originFor("2026-01", oldGeneration))
        assertNull(cache.putIfCurrent(staleOrigin, listOf(asset("stale"))))
        assertNull(cache.getCurrent("2026-01", oldGeneration))
        assertNull(cache.assetsForRender("2026-01", oldGeneration))
    }

    @Test
    fun loadAcrossClearIsRejectedEvenIfSameGenerationIsAcceptedAgain() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val generation = generation(1L)
        cache.acceptCurrentGeneration("2026-01", generation)

        val loaded = cache.getOrLoadCurrent("2026-01", generation) {
            cache.clear()
            cache.acceptCurrentGeneration("2026-01", generation)
            listOf(asset("stale"))
        }

        assertNull(loaded)
        assertNull(cache.assetsForRender("2026-01", generation))

        val currentAssets = listOf(asset("current"))
        val currentWrite = cache.putIfCurrent(origin(cache, "2026-01", generation), currentAssets)

        requireNotNull(currentWrite)
        assertSame(currentAssets, cache.assetsForRender("2026-01", generation))
    }

    @Test
    fun clearEpochRejectsPreClearOriginAfterSameGenerationIsAcceptedAgain() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val generation = generation(1L)
        cache.acceptCurrentGeneration("2026-01", generation)
        val preClearOrigin = cache.originFor("2026-01", generation)
        requireNotNull(preClearOrigin)

        cache.clear()
        cache.acceptCurrentGeneration("2026-01", generation)

        assertNull(cache.putIfCurrent(preClearOrigin, listOf(asset("stale"))))
        assertNull(cache.assetsForRender("2026-01", generation))
    }

    @Test
    fun renderAccessHidesStaleGeneration() = runTest {
        val cache = TimelineBucketAssetsRuntimeCache()
        val currentGeneration = generation(1L)
        val staleGeneration = generation(0L)
        val assets = listOf(asset("visible"))
        cache.acceptCurrentGeneration("2026-01", currentGeneration)

        val write = cache.putIfCurrent(origin(cache, "2026-01", currentGeneration), assets)
        requireNotNull(write)

        assertNull(cache.assetsForRender("2026-01", staleGeneration))
        assertNull(cache.assetsForRender("2026-01") { staleGeneration })
        assertSame(assets, cache.assetsForRender("2026-01") { currentGeneration })
    }

    private fun generation(value: Long): TimelineRuntimeBucketGenerationStamp =
        TimelineRuntimeBucketGenerationStamp(bucketGeneration = value)

    private suspend fun origin(
        cache: TimelineBucketAssetsRuntimeCache,
        timeBucket: String,
        generation: TimelineRuntimeBucketGenerationStamp
    ): TimelineBucketAssetsRuntimeCache.Origin {
        val origin = cache.originFor(timeBucket, generation)
        requireNotNull(origin)
        return origin
    }

    private fun asset(id: String): Asset =
        Asset(
            id = id,
            type = AssetType.IMAGE,
            fileName = "$id.jpg",
            createdAt = "2026-01-01T00:00:00Z",
            thumbnailUrl = "",
            originalUrl = ""
        )
}
