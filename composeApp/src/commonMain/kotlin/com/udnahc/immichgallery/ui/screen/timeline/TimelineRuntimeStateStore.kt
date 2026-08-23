package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import kotlinx.coroutines.flow.MutableStateFlow

internal data class TimelineRuntimeGenerationStamp(
    val globalGeneration: Long,
    val bucketGeneration: Long
)

internal data class TimelineRuntimeBucketGenerationStamp(
    val bucketGeneration: Long
)

internal data class TimelineDeferredMaterializedBucketStamp(
    val sessionStamp: SessionOperationStamp,
    val bucketGenerationStamp: TimelineRuntimeBucketGenerationStamp
)

internal data class TimelineRuntimeGenerationSnapshot(
    val globalGeneration: Long,
    val bucketGenerations: Map<String, Long>
) {
    fun generationStampFor(timeBucket: String): TimelineRuntimeGenerationStamp =
        TimelineRuntimeGenerationStamp(
            globalGeneration = globalGeneration,
            bucketGeneration = bucketGenerations[timeBucket] ?: 0L
        )

    fun bucketGenerationStampFor(timeBucket: String): TimelineRuntimeBucketGenerationStamp =
        TimelineRuntimeBucketGenerationStamp(
            bucketGeneration = bucketGenerations[timeBucket] ?: 0L
        )

    fun matches(
        stamp: TimelineRuntimeGenerationStamp,
        timeBucket: String
    ): Boolean =
        generationStampFor(timeBucket) == stamp

    fun matches(
        stamp: TimelineRuntimeBucketGenerationStamp,
        timeBucket: String
    ): Boolean =
        bucketGenerationStampFor(timeBucket) == stamp

    fun matches(
        stamp: TimelineMosaicPublishStamp,
        timeBucket: String
    ): Boolean =
        globalGeneration == stamp.globalGeneration &&
                (bucketGenerations[timeBucket] ?: 0L) == (stamp.bucketGenerations[timeBucket] ?: 0L)

    fun publishStampFor(keys: Set<MosaicCacheKey>): TimelineMosaicPublishStamp =
        publishStampForBuckets(keys.map { it.timeBucket }.toSet())

    fun publishStampForBuckets(timeBuckets: Set<String>): TimelineMosaicPublishStamp =
        TimelineMosaicPublishStamp(
            globalGeneration = globalGeneration,
            bucketGenerations = timeBuckets
                .distinct()
                .associateWith { timeBucket -> bucketGenerations[timeBucket] ?: 0L }
        )
}

private data class StoredTimelineRuntimeMosaicResumeState(
    val state: TimelineRuntimeMosaicResumeState,
    val stamp: TimelineRuntimeGenerationStamp
)

private sealed interface DeferredMosaicFragmentKey {
    data class State(val key: MosaicCacheKey) : DeferredMosaicFragmentKey
    data class Geometry(val key: MosaicCacheKey) : DeferredMosaicFragmentKey
    data class Repair(val timeBucket: String) : DeferredMosaicFragmentKey
}

private data class DeferredScrollMosaicBatch(
    val fragments: LinkedHashMap<DeferredMosaicFragmentKey, PendingMosaicPublish> = linkedMapOf()
) {
    val isEmpty: Boolean
        get() = fragments.isEmpty()

    fun withoutBucket(timeBucket: String): DeferredScrollMosaicBatch {
        if (isEmpty) return this
        val retainedFragments = LinkedHashMap<DeferredMosaicFragmentKey, PendingMosaicPublish>()
        fragments.forEach { (key, publish) ->
            val keep = when (key) {
                is DeferredMosaicFragmentKey.State -> key.key.timeBucket != timeBucket
                is DeferredMosaicFragmentKey.Geometry -> key.key.timeBucket != timeBucket
                is DeferredMosaicFragmentKey.Repair -> key.timeBucket != timeBucket
            }
            if (keep) retainedFragments[key] = publish
        }
        return copy(fragments = retainedFragments)
    }
}

private data class TimelineRuntimeStateSnapshot(
    val globalGeneration: Long = 0L,
    val bucketGenerations: Map<String, Long> = emptyMap(),
    val dayGroups: Map<DayGroupsCacheKey, List<DayGroup>> = emptyMap(),
    val resumeStates: Map<MosaicCacheKey, StoredTimelineRuntimeMosaicResumeState> = emptyMap(),
    val deferredMaterializedBuckets: Map<String, TimelineDeferredMaterializedBucketStamp> = emptyMap(),
    val deferredMosaicBatch: DeferredScrollMosaicBatch = DeferredScrollMosaicBatch()
)

private data class DeferredMosaicFragmentVersion(
    val sessionGeneration: Long,
    val ownerEpoch: Long,
    val globalGeneration: Long,
    val bucketGeneration: Long,
    val revision: Int = 0
) : Comparable<DeferredMosaicFragmentVersion> {
    override fun compareTo(other: DeferredMosaicFragmentVersion): Int =
        compareValuesBy(
            this,
            other,
            DeferredMosaicFragmentVersion::sessionGeneration,
            DeferredMosaicFragmentVersion::ownerEpoch,
            DeferredMosaicFragmentVersion::globalGeneration,
            DeferredMosaicFragmentVersion::bucketGeneration,
            DeferredMosaicFragmentVersion::revision
        )
}

private fun DeferredScrollMosaicBatch.withPublish(
    publish: PendingMosaicPublish,
    replaceEqual: Boolean
): Pair<DeferredScrollMosaicBatch, Boolean> {
    val nextFragments = LinkedHashMap(fragments)
    var changed = false
    publish.stateUpdates.forEach { (key, state) ->
        val fragmentKey = DeferredMosaicFragmentKey.State(key)
        val fragment = publish.copy(
            stateUpdates = mapOf(key to state),
            geometryUpdates = emptyMap(),
            repairRequests = emptyList()
        )
        changed = nextFragments.putIfNewer(fragmentKey, fragment, replaceEqual) || changed
    }
    publish.geometryUpdates.forEach { (key, geometry) ->
        val fragmentKey = DeferredMosaicFragmentKey.Geometry(key)
        val fragment = publish.copy(
            stateUpdates = emptyMap(),
            geometryUpdates = mapOf(key to geometry),
            repairRequests = emptyList()
        )
        changed = nextFragments.putIfNewer(fragmentKey, fragment, replaceEqual) || changed
    }
    publish.repairRequests.forEach { request ->
        val fragmentKey = DeferredMosaicFragmentKey.Repair(request.timeBucket)
        val fragment = publish.copy(
            stateUpdates = emptyMap(),
            geometryUpdates = emptyMap(),
            repairRequests = listOf(request)
        )
        changed = nextFragments.putIfNewer(fragmentKey, fragment, replaceEqual) || changed
    }
    if (!changed) return this to false
    return copy(fragments = nextFragments) to true
}

private fun LinkedHashMap<DeferredMosaicFragmentKey, PendingMosaicPublish>.putIfNewer(
    key: DeferredMosaicFragmentKey,
    candidate: PendingMosaicPublish,
    replaceEqual: Boolean
): Boolean {
    val current = this[key]
    if (current != null) {
        val comparison = candidate.deferredVersion(key).compareTo(current.deferredVersion(key))
        if (comparison < 0 || comparison == 0 && !replaceEqual) return false
    }
    this[key] = candidate
    return true
}

private fun PendingMosaicPublish.deferredVersion(
    key: DeferredMosaicFragmentKey
): DeferredMosaicFragmentVersion =
    when (key) {
        is DeferredMosaicFragmentKey.State -> deferredPublishVersion(key.key.timeBucket)
        is DeferredMosaicFragmentKey.Geometry -> deferredPublishVersion(key.key.timeBucket)
        is DeferredMosaicFragmentKey.Repair -> {
            val request = repairRequests.firstOrNull()
            if (request == null) {
                deferredPublishVersion(key.timeBucket)
            } else {
                DeferredMosaicFragmentVersion(
                    sessionGeneration = request.sessionStamp.generation,
                    ownerEpoch = request.sessionStamp.ownerEpoch,
                    globalGeneration = request.globalGeneration,
                    bucketGeneration = request.bucketGeneration,
                    revision = request.assetRevision
                )
            }
        }
    }

private fun PendingMosaicPublish.deferredPublishVersion(
    timeBucket: String
): DeferredMosaicFragmentVersion =
    DeferredMosaicFragmentVersion(
        sessionGeneration = sessionStamp.generation,
        ownerEpoch = sessionStamp.ownerEpoch,
        globalGeneration = stamp.globalGeneration,
        bucketGeneration = stamp.bucketGenerations[timeBucket] ?: 0L
    )

internal class TimelineRuntimeStateStore {
    private val snapshot = MutableStateFlow(TimelineRuntimeStateSnapshot())

    fun generationSnapshot(): TimelineRuntimeGenerationSnapshot {
        val state = snapshot.value
        return TimelineRuntimeGenerationSnapshot(
            globalGeneration = state.globalGeneration,
            bucketGenerations = state.bucketGenerations
        )
    }

    fun advanceGlobalGeneration(): Long =
        updateAndGet { state ->
            state.copy(
                globalGeneration = state.globalGeneration + 1L,
                resumeStates = emptyMap(),
                deferredMosaicBatch = DeferredScrollMosaicBatch()
            )
        }.globalGeneration

    fun invalidateBucket(timeBucket: String): Long =
        updateAndGet { state ->
            val nextBucketGeneration = (state.bucketGenerations[timeBucket] ?: 0L) + 1L
            state.copy(
                bucketGenerations = state.bucketGenerations + (timeBucket to nextBucketGeneration),
                dayGroups = state.dayGroups.filterKeys { it.timeBucket != timeBucket },
                resumeStates = state.resumeStates.filterKeys { it.timeBucket != timeBucket },
                deferredMaterializedBuckets = state.deferredMaterializedBuckets - timeBucket,
                deferredMosaicBatch = state.deferredMosaicBatch.withoutBucket(timeBucket)
            )
        }.bucketGenerations[timeBucket] ?: 0L

    fun publishStampFor(keys: Set<MosaicCacheKey>): TimelineMosaicPublishStamp {
        return generationSnapshot().publishStampFor(keys)
    }

    fun publishStampForBuckets(timeBuckets: Set<String>): TimelineMosaicPublishStamp {
        return generationSnapshot().publishStampForBuckets(timeBuckets)
    }

    fun generationStampFor(timeBucket: String): TimelineRuntimeGenerationStamp {
        return generationSnapshot().generationStampFor(timeBucket)
    }

    fun isCurrent(stamp: TimelineMosaicPublishStamp): Boolean {
        val generationSnapshot = generationSnapshot()
        return stamp.globalGeneration == generationSnapshot.globalGeneration &&
                stamp.bucketGenerations.all { (timeBucket, generation) ->
                    generationSnapshot.bucketGenerations[timeBucket] ?: 0L == generation
                }
    }

    fun dayGroupsFor(
        key: DayGroupsCacheKey,
        compute: () -> List<DayGroup>
    ): List<DayGroup> {
        snapshot.value.dayGroups[key]?.let { return it }
        val expectedStamp = generationStampFor(key.timeBucket)
        val computed = compute()
        while (true) {
            val current = snapshot.value
            current.dayGroups[key]?.let { return it }
            if (current.generationStampFor(key.timeBucket) != expectedStamp) {
                return computed
            }
            val next = current.copy(dayGroups = current.dayGroups + (key to computed))
            if (snapshot.compareAndSet(current, next)) return computed
        }
    }

    fun resumeStateFor(
        key: MosaicCacheKey,
        sessionStamp: SessionOperationStamp,
        expectedStamp: TimelineRuntimeGenerationStamp
    ): TimelineRuntimeMosaicResumeState? {
        while (true) {
            val current = snapshot.value
            val stored = current.resumeStates[key] ?: return null
            if (stored.stamp != expectedStamp) return null
            if (stored.state.sessionStamp == sessionStamp) return stored.state
            if (sessionStamp < stored.state.sessionStamp) return null
            val next = current.copy(resumeStates = current.resumeStates - key)
            if (snapshot.compareAndSet(current, next)) return null
        }
    }

    fun removeResumeState(
        key: MosaicCacheKey,
        expectedStamp: TimelineRuntimeGenerationStamp,
        sessionStamp: SessionOperationStamp
    ) {
        update { state ->
            val stored = state.resumeStates[key]
            if (stored?.stamp == expectedStamp && stored.state.sessionStamp == sessionStamp) {
                state.copy(resumeStates = state.resumeStates - key)
            } else {
                state
            }
        }
    }

    fun saveResumeState(
        key: MosaicCacheKey,
        resumeState: TimelineRuntimeMosaicResumeState,
        expectedStamp: TimelineRuntimeGenerationStamp
    ): Boolean {
        while (true) {
            val current = snapshot.value
            if (current.generationStampFor(key.timeBucket) != expectedStamp) return false
            val existing = current.resumeStates[key]
            if (existing?.stamp == expectedStamp &&
                resumeState.sessionStamp < existing.state.sessionStamp
            ) {
                return false
            }
            val next = current.copy(
                resumeStates = current.resumeStates + (
                        key to StoredTimelineRuntimeMosaicResumeState(
                            state = resumeState,
                            stamp = expectedStamp
                        )
                        )
            )
            if (snapshot.compareAndSet(current, next)) return true
        }
    }

    fun deferMaterializedBuckets(
        timeBuckets: Set<String>,
        expectedGenerations: Map<String, TimelineDeferredMaterializedBucketStamp>
    ): Boolean {
        if (timeBuckets.isEmpty()) return false
        return updateAndGetChange { state ->
            val generationSnapshot = state.generationSnapshot()
            val currentBuckets = timeBuckets.filterTo(mutableSetOf()) { timeBucket ->
                expectedGenerations[timeBucket]?.let { expected ->
                    generationSnapshot.matches(expected.bucketGenerationStamp, timeBucket)
                } == true
            }
            if (currentBuckets.isEmpty()) return@updateAndGetChange state to false
            val retainedMaterializedBuckets = LinkedHashMap(state.deferredMaterializedBuckets)
            var changed = false
            currentBuckets.forEach { timeBucket ->
                val candidate = checkNotNull(expectedGenerations[timeBucket])
                val existing = retainedMaterializedBuckets[timeBucket]
                if (existing == null || candidate.sessionStamp >= existing.sessionStamp) {
                    retainedMaterializedBuckets[timeBucket] = candidate
                    changed = true
                }
            }
            if (!changed) return@updateAndGetChange state to false
            state.copy(
                deferredMaterializedBuckets = retainedMaterializedBuckets
            ) to true
        }
    }

    fun drainDeferredMaterializedBuckets(
        priorityBuckets: Set<String>
    ): Map<String, TimelineDeferredMaterializedBucketStamp> {
        return updateAndGetPrevious { state ->
            state.copy(deferredMaterializedBuckets = emptyMap())
        }.deferredMaterializedBuckets.filterKeys { it in priorityBuckets }
    }

    fun deferMosaicPublish(publish: PendingMosaicPublish): Boolean {
        return updateAndGetChange { state ->
            val currentPublish = publish.filterCurrentFor(state)
                ?: return@updateAndGetChange state to false
            val (batch, changed) = state.deferredMosaicBatch.withPublish(
                publish = currentPublish,
                replaceEqual = true
            )
            state.copy(deferredMosaicBatch = batch) to changed
        }
    }

    fun requeueMosaicPublishes(publishes: List<PendingMosaicPublish>) {
        if (publishes.isEmpty()) return
        update { state ->
            var batch = state.deferredMosaicBatch
            publishes.forEach { publish ->
                val currentPublish = publish.filterCurrentFor(state) ?: return@forEach
                batch = batch.withPublish(
                    publish = currentPublish,
                    replaceEqual = false
                ).first
            }
            state.copy(deferredMosaicBatch = batch)
        }
    }

    fun drainDeferredMosaicPublishes(priorityBuckets: Set<String>): List<PendingMosaicPublish> {
        val batch = updateAndGetPrevious { state ->
            state.copy(deferredMosaicBatch = DeferredScrollMosaicBatch())
        }.deferredMosaicBatch
        if (batch.isEmpty) return emptyList()
        return batch.fragments.mapNotNull { (key, publish) ->
            val keep = when (key) {
                is DeferredMosaicFragmentKey.State -> key.key.timeBucket in priorityBuckets
                is DeferredMosaicFragmentKey.Geometry -> key.key.timeBucket in priorityBuckets
                is DeferredMosaicFragmentKey.Repair -> key.timeBucket in priorityBuckets
            }
            publish.takeIf { keep }
        }
    }

    fun hasDeferredMosaicWork(): Boolean =
        !snapshot.value.deferredMosaicBatch.isEmpty

    fun snapshotSizeForTests(): Int {
        val state = snapshot.value
        return state.dayGroups.size +
                state.resumeStates.size +
                state.deferredMaterializedBuckets.size +
                state.deferredMosaicBatch.fragments.size
    }

    private fun TimelineRuntimeStateSnapshot.generationStampFor(
        timeBucket: String
    ): TimelineRuntimeGenerationStamp =
        TimelineRuntimeGenerationStamp(
            globalGeneration = globalGeneration,
            bucketGeneration = bucketGenerations[timeBucket] ?: 0L
        )

    private fun TimelineRuntimeStateSnapshot.generationSnapshot(): TimelineRuntimeGenerationSnapshot =
        TimelineRuntimeGenerationSnapshot(
            globalGeneration = globalGeneration,
            bucketGenerations = bucketGenerations
        )

    private fun PendingMosaicPublish.filterCurrentFor(
        state: TimelineRuntimeStateSnapshot
    ): PendingMosaicPublish? {
        val generationSnapshot = state.generationSnapshot()
        val currentStateUpdates = stateUpdates.filterKeys { key ->
            generationSnapshot.matches(stamp, key.timeBucket)
        }
        val currentGeometryUpdates = geometryUpdates.filterKeys { key ->
            generationSnapshot.matches(stamp, key.timeBucket)
        }
        val currentRepairRequests = repairRequests.filter { request ->
            request.globalGeneration == generationSnapshot.globalGeneration &&
                    request.bucketGeneration == (
                    generationSnapshot.bucketGenerations[request.timeBucket] ?: 0L
                    )
        }
        if (currentStateUpdates.isEmpty() &&
            currentGeometryUpdates.isEmpty() &&
            currentRepairRequests.isEmpty()
        ) {
            return null
        }
        return copy(
            stateUpdates = currentStateUpdates,
            geometryUpdates = currentGeometryUpdates,
            repairRequests = currentRepairRequests
        )
    }

    private fun update(
        transform: (TimelineRuntimeStateSnapshot) -> TimelineRuntimeStateSnapshot
    ) {
        while (true) {
            val current = snapshot.value
            val next = transform(current)
            if (snapshot.compareAndSet(current, next)) return
        }
    }

    private fun updateAndGet(
        transform: (TimelineRuntimeStateSnapshot) -> TimelineRuntimeStateSnapshot
    ): TimelineRuntimeStateSnapshot {
        while (true) {
            val current = snapshot.value
            val next = transform(current)
            if (snapshot.compareAndSet(current, next)) return next
        }
    }

    private fun updateAndGetPrevious(
        transform: (TimelineRuntimeStateSnapshot) -> TimelineRuntimeStateSnapshot
    ): TimelineRuntimeStateSnapshot {
        while (true) {
            val current = snapshot.value
            val next = transform(current)
            if (snapshot.compareAndSet(current, next)) return current
        }
    }

    private fun updateAndGetChange(
        transform: (TimelineRuntimeStateSnapshot) -> Pair<TimelineRuntimeStateSnapshot, Boolean>
    ): Boolean {
        while (true) {
            val current = snapshot.value
            val (next, changed) = transform(current)
            if (!changed) return false
            if (snapshot.compareAndSet(current, next)) return true
        }
    }
}

private operator fun SessionOperationStamp.compareTo(other: SessionOperationStamp): Int =
    compareValuesBy(
        this,
        other,
        SessionOperationStamp::generation,
        SessionOperationStamp::ownerEpoch
    )
