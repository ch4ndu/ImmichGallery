package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.udnahc.immichgallery.domain.model.Asset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TimelineBucketAssetsRuntimeCache {
    data class Entry(
        val assets: List<Asset>,
        val generationStamp: TimelineRuntimeBucketGenerationStamp
    )

    class Origin internal constructor(
        internal val timeBucket: String,
        internal val expectedStamp: TimelineRuntimeBucketGenerationStamp,
        internal val globalEpoch: Long,
        internal val bucketEpoch: Long
    )

    class CacheWrite internal constructor(
        internal val timeBucket: String,
        internal val installedEntry: Entry,
        internal val previousEntry: Entry?,
        internal val origin: Origin
    )

    private val mutex = Mutex()
    private val entries: SnapshotStateMap<String, Entry> = mutableStateMapOf()
    private val acceptedGenerations: SnapshotStateMap<String, TimelineRuntimeBucketGenerationStamp> =
        mutableStateMapOf()
    private var globalEpoch = 0L
    private val bucketEpochs = mutableMapOf<String, Long>()

    fun assetsForRender(
        timeBucket: String,
        expected: TimelineRuntimeBucketGenerationStamp
    ): List<Asset>? {
        val entry = entries[timeBucket] ?: return null
        return entry.assets.takeIf {
            entry.generationStamp == expected &&
                    acceptedGenerations[timeBucket] == expected
        }
    }

    fun assetsForRender(
        timeBucket: String,
        currentGenerationProvider: (String) -> TimelineRuntimeBucketGenerationStamp
    ): List<Asset>? =
        assetsForRender(timeBucket, currentGenerationProvider(timeBucket))

    fun containsCurrent(
        timeBucket: String,
        expected: TimelineRuntimeBucketGenerationStamp
    ): Boolean =
        entries[timeBucket]?.generationStamp == expected &&
                acceptedGenerations[timeBucket] == expected

    fun containsCurrent(
        timeBucket: String,
        currentGenerationProvider: (String) -> TimelineRuntimeBucketGenerationStamp
    ): Boolean =
        containsCurrent(timeBucket, currentGenerationProvider(timeBucket))

    fun currentKeys(
        expectedGenerations: Map<String, TimelineRuntimeBucketGenerationStamp>
    ): Set<String> =
        entries.mapNotNullTo(mutableSetOf()) { (timeBucket, entry) ->
            timeBucket.takeIf {
                expectedGenerations[timeBucket] == entry.generationStamp &&
                        acceptedGenerations[timeBucket] == entry.generationStamp
            }
        }

    fun currentKeys(
        currentGenerationProvider: (String) -> TimelineRuntimeBucketGenerationStamp
    ): Set<String> =
        entries.mapNotNullTo(mutableSetOf()) { (timeBucket, entry) ->
            timeBucket.takeIf {
                currentGenerationProvider(timeBucket) == entry.generationStamp &&
                        acceptedGenerations[timeBucket] == entry.generationStamp
            }
        }

    suspend fun acceptCurrentGeneration(
        timeBucket: String,
        current: TimelineRuntimeBucketGenerationStamp
    ) {
        mutex.withLock {
            val previousAccepted = acceptedGenerations[timeBucket]
            if (previousAccepted != null && previousAccepted != current) {
                bumpBucketEpoch(timeBucket)
            }
            acceptedGenerations[timeBucket] = current
            if (entries[timeBucket]?.generationStamp != current) {
                entries.remove(timeBucket)
            }
        }
    }

    suspend fun originFor(
        timeBucket: String,
        expected: TimelineRuntimeBucketGenerationStamp
    ): Origin? =
        mutex.withLock {
            originForLocked(timeBucket, expected)
        }

    suspend fun getCurrent(
        timeBucket: String,
        expected: TimelineRuntimeBucketGenerationStamp
    ): List<Asset>? =
        mutex.withLock {
            entries[timeBucket]
                ?.takeIf {
                    it.generationStamp == expected &&
                            acceptedGenerations[timeBucket] == expected
                }
                ?.assets
        }

    suspend fun getOrLoadCurrent(
        timeBucket: String,
        expected: TimelineRuntimeBucketGenerationStamp,
        loader: suspend () -> List<Asset>
    ): List<Asset>? {
        val origin = mutex.withLock {
            val current = entries[timeBucket]
            when {
                acceptedGenerations[timeBucket] != expected -> return null
                current == null -> originForLocked(timeBucket, expected) ?: return null
                current.generationStamp == expected -> return current.assets
                else -> return null
            }
        }

        val loaded = loader()

        return putIfCurrent(origin, loaded)?.installedEntry?.assets
            ?: getCurrent(timeBucket, expected)
    }

    suspend fun putIfCurrent(
        origin: Origin,
        assets: List<Asset>
    ): CacheWrite? =
        mutex.withLock {
            if (!isCurrentLocked(origin)) return@withLock null
            val current = entries[origin.timeBucket]
            if (current != null && current.generationStamp != origin.expectedStamp) return@withLock null
            val next = Entry(
                assets = assets,
                generationStamp = origin.expectedStamp
            )
            entries[origin.timeBucket] = next
            CacheWrite(
                timeBucket = origin.timeBucket,
                installedEntry = next,
                previousEntry = current,
                origin = origin
            )
        }

    suspend fun restoreIfOwned(write: CacheWrite) {
        mutex.withLock {
            if (!isCurrentLocked(write.origin)) return@withLock
            if (entries[write.timeBucket] !== write.installedEntry) return@withLock
            if (write.previousEntry == null) {
                entries.remove(write.timeBucket)
            } else {
                entries[write.timeBucket] = write.previousEntry
            }
        }
    }

    suspend fun invalidate(timeBucket: String) {
        mutex.withLock {
            entries.remove(timeBucket)
            acceptedGenerations.remove(timeBucket)
            bumpBucketEpoch(timeBucket)
        }
    }

    suspend fun invalidate(
        timeBucket: String,
        current: TimelineRuntimeBucketGenerationStamp
    ) {
        mutex.withLock {
            entries.remove(timeBucket)
            acceptedGenerations[timeBucket] = current
            bumpBucketEpoch(timeBucket)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            entries.clear()
            acceptedGenerations.clear()
            globalEpoch += 1L
        }
    }

    private fun originForLocked(
        timeBucket: String,
        expected: TimelineRuntimeBucketGenerationStamp
    ): Origin? {
        if (acceptedGenerations[timeBucket] != expected) return null
        return Origin(
            timeBucket = timeBucket,
            expectedStamp = expected,
            globalEpoch = globalEpoch,
            bucketEpoch = bucketEpochs[timeBucket] ?: 0L
        )
    }

    private fun isCurrentLocked(origin: Origin): Boolean =
        globalEpoch == origin.globalEpoch &&
                (bucketEpochs[origin.timeBucket] ?: 0L) == origin.bucketEpoch &&
                acceptedGenerations[origin.timeBucket] == origin.expectedStamp

    private fun bumpBucketEpoch(timeBucket: String) {
        bucketEpochs[timeBucket] = (bucketEpochs[timeBucket] ?: 0L) + 1L
    }
}
