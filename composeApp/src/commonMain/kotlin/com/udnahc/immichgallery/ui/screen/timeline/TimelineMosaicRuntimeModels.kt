package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.data.repository.SessionOperationRejectedException
import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.data.repository.StaleSessionOperationException
import com.udnahc.immichgallery.domain.action.auth.SessionOperationRunner
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetGroupDisplayLabel
import com.udnahc.immichgallery.domain.model.GeometryAlignedRange
import com.udnahc.immichgallery.domain.model.GeometryAlignedSection
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketSkeleton
import com.udnahc.immichgallery.domain.model.timelineDaySectionDisplayLabel
import com.udnahc.immichgallery.domain.model.MosaicAssignmentCheckpoint
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.ProgressChunk
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TIMELINE_MOSAIC_WIDTH_QUANTUM
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicRepairSectionInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import com.udnahc.immichgallery.domain.model.MosaicSectionState as RuntimeMosaicState

internal data class MosaicCacheKey(
    val timeBucket: String,
    val sectionKey: String,
    val columnCount: Int,
    val assetRevision: Int,
    val familiesKey: String
)

internal data class TimelineMosaicSectionGeometry(
    val placeholderHeight: Float,
    val ranges: List<MosaicSectionGeometryRange>
) {
    val contentToken: Int = ranges.hashCode() * 31 + placeholderHeight.hashCode()
}

internal data class TimelineSkeletonIdentity(
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val familiesKey: String,
    val geometryRequest: TimelineMosaicGeometryRequest,
    val groupingZoneId: String
)

internal enum class TimelineSkeletonLoadStatus {
    Loading,
    Loaded,
    Failed
}

internal data class TimelineSkeletonState(
    val identity: TimelineSkeletonIdentity? = null,
    val status: TimelineSkeletonLoadStatus = TimelineSkeletonLoadStatus.Loading,
    val buckets: Map<String, TimelineMosaicBucketSkeleton> = emptyMap(),
    // Bumped whenever a bucket is invalidated or re-read, so an older in-flight read cannot overwrite it.
    val bucketEpochs: Map<String, Long> = emptyMap()
) {
    fun startingLoad(next: TimelineSkeletonIdentity): TimelineSkeletonState =
        if (identity == next) this else TimelineSkeletonState(identity = next, bucketEpochs = bucketEpochs)

    fun withBumpedEpochs(timeBuckets: Set<String>): TimelineSkeletonState =
        copy(bucketEpochs = bucketEpochs + timeBuckets.associateWith { (bucketEpochs[it] ?: 0L) + 1L })

    fun withLoadedBuckets(
        loadedIdentity: TimelineSkeletonIdentity,
        loaded: Map<String, TimelineMosaicBucketSkeleton>,
        requestedBuckets: Set<String>?,
        originEpochs: Map<String, Long>
    ): TimelineSkeletonState {
        if (identity != loadedIdentity) return this
        fun unchanged(timeBucket: String) = (bucketEpochs[timeBucket] ?: 0L) == (originEpochs[timeBucket] ?: 0L)
        val nextBuckets = if (requestedBuckets == null) {
            val fresh = loaded.filterKeys(::unchanged)
            val retained = buckets.filterKeys { !unchanged(it) }
            fresh + retained
        } else {
            val reloaded = requestedBuckets.filter(::unchanged)
            (buckets - reloaded.toSet()) + reloaded.mapNotNull { bucket -> loaded[bucket]?.let { bucket to it } }
        }
        return copy(
            status = if (requestedBuckets == null) TimelineSkeletonLoadStatus.Loaded else status,
            buckets = nextBuckets
        )
    }
}

internal fun timelineSkeletonGateOpen(
    state: TimelineSkeletonState,
    required: TimelineSkeletonIdentity?
): Boolean =
    required == null || (state.identity == required && state.status != TimelineSkeletonLoadStatus.Loading)

internal fun timelineMosaicRangeGridKey(
    timeBucket: String,
    sectionKey: String,
    sourceStartIndex: Int,
    sourceCount: Int
): String = "mr_${timeBucket}_${sectionKey}_${sourceStartIndex}_$sourceCount"

internal fun timelineDayHeaderGridKey(timeBucket: String, sectionKey: String): String =
    "dh_${timeBucket}_$sectionKey"

internal fun timelineGeometryAlignedItems(
    timeBucket: String,
    sectionKey: String,
    bucketIndex: Int,
    sectionLabel: String,
    aligned: GeometryAlignedSection
): List<TimelineDisplayItem> =
    aligned.ranges.map { alignedRange ->
        val range = alignedRange.range
        val key = timelineMosaicRangeGridKey(timeBucket, sectionKey, range.sourceStartIndex, range.sourceCount)
        when (val item = alignedRange.item) {
            is MosaicBandItem -> item.copy(gridKey = key)
            is RowItem -> item.copy(gridKey = key)
            else -> PlaceholderItem(
                gridKey = key,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                estimatedHeight = range.height
            )
        }
    }

internal fun timelineSkeletonBucketItems(
    timeBucket: String,
    bucketIndex: Int,
    groupSize: TimelineGroupSize,
    skeleton: TimelineMosaicBucketSkeleton
): List<TimelineDisplayItem> = buildList {
    skeleton.sections.forEach { section ->
        val sectionLabel = if (groupSize == TimelineGroupSize.DAY) {
            add(
                HeaderItem(
                    gridKey = timelineDayHeaderGridKey(timeBucket, section.sectionKey),
                    bucketIndex = bucketIndex,
                    sectionLabel = section.sectionKey,
                    label = section.sectionKey,
                    assetGroupDisplayLabel = timelineDaySectionDisplayLabel(section.sectionKey)
                        ?: AssetGroupDisplayLabel.UnknownDate
                )
            )
            section.sectionKey
        } else {
            monthMosaicSectionKey(timeBucket)
        }
        addAll(
            timelineGeometryAlignedItems(
                timeBucket = timeBucket,
                sectionKey = section.sectionKey,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                aligned = GeometryAlignedSection(
                    section.ranges.sortedBy { it.sourceStartIndex }.map { GeometryAlignedRange(it, null) }
                )
            )
        )
    }
}

internal data class TimelineMosaicPublishStamp(
    val globalGeneration: Long,
    val bucketGenerations: Map<String, Long>
)

internal data class PendingMosaicPublish(
    val sessionStamp: SessionOperationStamp,
    val stateUpdates: Map<MosaicCacheKey, RuntimeMosaicState>,
    val geometryUpdates: Map<MosaicCacheKey, TimelineMosaicSectionGeometry>,
    val stamp: TimelineMosaicPublishStamp,
    val originConfig: ActiveMosaicConfig? = null,
    val originGeometryRequest: TimelineMosaicGeometryRequest? = null,
    val originAssetRevisions: Map<String, Int> = emptyMap(),
    val repairRequests: List<TimelineMosaicRepairRequest> = emptyList()
)

internal data class TimelineMosaicRepairRequest(
    val sessionStamp: SessionOperationStamp,
    val timeBucket: String,
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val families: Set<MosaicTemplateFamily>,
    val geometryRequest: TimelineMosaicGeometryRequest,
    val assetRevision: Int,
    val globalGeneration: Long,
    val bucketGeneration: Long,
    val sectionsByKey: Map<String, TimelineMosaicRepairSectionInput>
)

internal fun TimelineMosaicRepairRequest.belongsToSession(stamp: SessionOperationStamp): Boolean =
    sessionStamp == stamp

internal fun coalesceTimelineMosaicRepairRequests(
    requests: List<TimelineMosaicRepairRequest>
): List<TimelineMosaicRepairRequest> =
    LinkedHashMap<String, TimelineMosaicRepairRequest>().apply {
        requests.forEach { request ->
            val existing = get(request.timeBucket)
            if (existing == null || request.timelineRepairVersion() >= existing.timelineRepairVersion()) {
                put(request.timeBucket, request)
            }
        }
    }.values.toList()

internal class TimelineMosaicPublishBuffer {
    fun coalesceCurrent(
        pending: List<PendingMosaicPublish>,
        currentGlobalGeneration: Long,
        currentBucketGenerations: Map<String, Long>
    ): PendingMosaicPublish? {
        val sessionStamp = pending.firstOrNull()?.sessionStamp ?: return null
        require(pending.all { it.sessionStamp == sessionStamp }) {
            "Timeline Mosaic publishes must be coalesced within one session stamp"
        }
        val accumulatedStates = LinkedHashMap<MosaicCacheKey, RuntimeMosaicState>()
        val accumulatedGeometry = LinkedHashMap<MosaicCacheKey, TimelineMosaicSectionGeometry>()
        val accumulatedRepairs = LinkedHashMap<String, TimelineMosaicRepairRequest>()
        val originAssetRevisions = LinkedHashMap<String, Int>()
        val originConfigs = pending.map { it.originConfig }.distinct()
        val originGeometryRequests = pending.map { it.originGeometryRequest }.distinct()
        pending.forEach { item ->
            originAssetRevisions.putAll(item.originAssetRevisions)
            if (item.stamp.globalGeneration != currentGlobalGeneration) return@forEach
            item.stateUpdates.forEach { (key, state) ->
                if (item.stamp.bucketGenerations[key.timeBucket] == (currentBucketGenerations[key.timeBucket]
                        ?: 0L)
                ) {
                    accumulatedStates[key] = state
                }
            }
            item.geometryUpdates.forEach { (key, geometry) ->
                if (item.stamp.bucketGenerations[key.timeBucket] == (currentBucketGenerations[key.timeBucket]
                        ?: 0L)
                ) {
                    accumulatedGeometry[key] = geometry
                }
            }
            item.repairRequests.forEach { request ->
                if (request.sessionStamp == sessionStamp &&
                    request.globalGeneration == currentGlobalGeneration &&
                    request.bucketGeneration == (currentBucketGenerations[request.timeBucket] ?: 0L)
                ) {
                    accumulatedRepairs[request.timeBucket] = request
                }
            }
        }
        if (accumulatedStates.isEmpty() && accumulatedGeometry.isEmpty() && accumulatedRepairs.isEmpty()) {
            return null
        }
        return PendingMosaicPublish(
            sessionStamp = sessionStamp,
            stateUpdates = accumulatedStates,
            geometryUpdates = accumulatedGeometry,
            stamp = TimelineMosaicPublishStamp(
                globalGeneration = currentGlobalGeneration,
                bucketGenerations = currentBucketGenerations.filterKeys { timeBucket ->
                    accumulatedStates.keys.any { it.timeBucket == timeBucket } ||
                            accumulatedGeometry.keys.any { it.timeBucket == timeBucket } ||
                            timeBucket in accumulatedRepairs
                }
            ),
            originConfig = originConfigs.singleOrNull(),
            originGeometryRequest = originGeometryRequests.singleOrNull(),
            originAssetRevisions = originAssetRevisions,
            repairRequests = accumulatedRepairs.values.toList()
        )
    }
}

private data class TimelineKeyedSignalBufferState<K, V>(
    val valuesByKey: LinkedHashMap<K, V> = linkedMapOf(),
    val wakeupPending: Boolean = false
)

private class TimelineKeyedSignalBuffer<K, V> {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow(
        TimelineKeyedSignalBufferState<K, V>()
    )

    fun offer(
        key: K,
        value: V,
        shouldReplace: (key: K, current: V, candidate: V) -> Boolean = { _, _, _ -> true }
    ) {
        var shouldWake = false
        while (true) {
            val current = state.value
            val values = LinkedHashMap(current.valuesByKey)
            val existing = values[key]
            if (existing != null && !shouldReplace(key, existing, value)) return
            values[key] = value
            val next = current.copy(
                valuesByKey = values,
                wakeupPending = true
            )
            shouldWake = !current.wakeupPending
            if (state.compareAndSet(current, next)) break
        }
        if (shouldWake) {
            wakeups.trySend(Unit)
        }
    }

    fun offerAll(items: Iterable<Pair<K, V>>) {
        offerAll(items) { _, _, _ -> true }
    }

    fun offerAll(
        items: Iterable<Pair<K, V>>,
        shouldReplace: (key: K, current: V, candidate: V) -> Boolean
    ) {
        var shouldWake = false
        while (true) {
            var offered = false
            val current = state.value
            val values = LinkedHashMap(current.valuesByKey)
            items.forEach { (key, value) ->
                val existing = values[key]
                if (existing == null || shouldReplace(key, existing, value)) {
                    values[key] = value
                    offered = true
                }
            }
            if (!offered) return
            val next = current.copy(
                valuesByKey = values,
                wakeupPending = true
            )
            shouldWake = !current.wakeupPending
            if (state.compareAndSet(current, next)) break
        }
        if (shouldWake) {
            wakeups.trySend(Unit)
        }
    }

    suspend fun receiveBatch(): List<V>? =
        wakeups.receiveCatching().getOrNull()?.let { drainAvailable() }

    fun drainAvailable(): List<V> {
        while (true) {
            val current = state.value
            if (current.valuesByKey.isEmpty()) {
                val next = current.copy(wakeupPending = false)
                if (snapshotCompareAndSet(current, next)) return emptyList()
                continue
            }
            val values = current.valuesByKey.values.toList()
            val next = current.copy(
                valuesByKey = linkedMapOf(),
                wakeupPending = false
            )
            if (snapshotCompareAndSet(current, next)) return values
        }
    }

    fun close() {
        wakeups.close()
    }

    fun pendingWakeupCountForTests(): Int =
        if (state.value.wakeupPending) 1 else 0

    private fun snapshotCompareAndSet(
        current: TimelineKeyedSignalBufferState<K, V>,
        next: TimelineKeyedSignalBufferState<K, V>
    ): Boolean =
        state.compareAndSet(current, next)
}

private sealed interface TimelineMosaicPublishSignalKey {
    data class State(val key: MosaicCacheKey) : TimelineMosaicPublishSignalKey
    data class Geometry(val key: MosaicCacheKey) : TimelineMosaicPublishSignalKey
    data class Repair(val timeBucket: String) : TimelineMosaicPublishSignalKey
}

private data class TimelineMosaicSignalVersion(
    val sessionGeneration: Long,
    val ownerEpoch: Long,
    val globalGeneration: Long,
    val bucketGeneration: Long,
    val revision: Int = 0
) : Comparable<TimelineMosaicSignalVersion> {
    override fun compareTo(other: TimelineMosaicSignalVersion): Int =
        compareValuesBy(
            this,
            other,
            TimelineMosaicSignalVersion::sessionGeneration,
            TimelineMosaicSignalVersion::ownerEpoch,
            TimelineMosaicSignalVersion::globalGeneration,
            TimelineMosaicSignalVersion::bucketGeneration,
            TimelineMosaicSignalVersion::revision
        )
}

private fun PendingMosaicPublish.timelineVersion(timeBucket: String): TimelineMosaicSignalVersion =
    TimelineMosaicSignalVersion(
        sessionGeneration = sessionStamp.generation,
        ownerEpoch = sessionStamp.ownerEpoch,
        globalGeneration = stamp.globalGeneration,
        bucketGeneration = stamp.bucketGenerations[timeBucket] ?: 0L
    )

private fun TimelineMosaicRepairRequest.timelineRepairVersion(): TimelineMosaicSignalVersion =
    TimelineMosaicSignalVersion(
        sessionGeneration = sessionStamp.generation,
        ownerEpoch = sessionStamp.ownerEpoch,
        globalGeneration = globalGeneration,
        bucketGeneration = bucketGeneration,
        revision = assetRevision
    )

private fun newerOrEqualPublish(
    current: PendingMosaicPublish,
    candidate: PendingMosaicPublish,
    timeBucket: String
): Boolean =
    candidate.timelineVersion(timeBucket) >= current.timelineVersion(timeBucket)

private fun newerOrEqualPublish(
    key: TimelineMosaicPublishSignalKey,
    current: PendingMosaicPublish,
    candidate: PendingMosaicPublish
): Boolean =
    when (key) {
        is TimelineMosaicPublishSignalKey.State ->
            newerOrEqualPublish(current, candidate, key.key.timeBucket)

        is TimelineMosaicPublishSignalKey.Geometry ->
            newerOrEqualPublish(current, candidate, key.key.timeBucket)

        is TimelineMosaicPublishSignalKey.Repair -> {
            val currentRequest = current.repairRequests.firstOrNull()
            val candidateRequest = candidate.repairRequests.firstOrNull()
            currentRequest == null ||
                    candidateRequest != null &&
                    candidateRequest.timelineRepairVersion() >= currentRequest.timelineRepairVersion()
        }
    }

internal class TimelineMosaicPublishSignalBuffer {
    private val buffer =
        TimelineKeyedSignalBuffer<TimelineMosaicPublishSignalKey, PendingMosaicPublish>()

    fun offer(publish: PendingMosaicPublish) {
        buffer.offerAll(publish.fragments(), ::newerOrEqualPublish)
    }

    fun offerAll(publishes: Iterable<PendingMosaicPublish>) {
        buffer.offerAll(publishes.flatMap { it.fragments() }, ::newerOrEqualPublish)
    }

    private fun PendingMosaicPublish.fragments(): List<Pair<TimelineMosaicPublishSignalKey, PendingMosaicPublish>> {
        val fragments = mutableListOf<Pair<TimelineMosaicPublishSignalKey, PendingMosaicPublish>>()
        stateUpdates.forEach { (key, state) ->
            fragments.add(
                TimelineMosaicPublishSignalKey.State(key) to copy(
                    stateUpdates = mapOf(key to state),
                    geometryUpdates = emptyMap(),
                    repairRequests = emptyList()
                )
            )
        }
        geometryUpdates.forEach { (key, geometry) ->
            fragments.add(
                TimelineMosaicPublishSignalKey.Geometry(key) to copy(
                    stateUpdates = emptyMap(),
                    geometryUpdates = mapOf(key to geometry),
                    repairRequests = emptyList()
                )
            )
        }
        repairRequests.forEach { request ->
            fragments.add(
                TimelineMosaicPublishSignalKey.Repair(request.timeBucket) to copy(
                    stateUpdates = emptyMap(),
                    geometryUpdates = emptyMap(),
                    repairRequests = listOf(request)
                )
            )
        }
        return fragments
    }

    suspend fun receiveBatch(): List<PendingMosaicPublish>? =
        buffer.receiveBatch()

    fun close() {
        buffer.close()
    }

    fun pendingWakeupCountForTests(): Int =
        buffer.pendingWakeupCountForTests()
}

internal class TimelineMosaicRepairSignalBuffer {
    private val buffer = TimelineKeyedSignalBuffer<String, TimelineMosaicRepairRequest>()

    fun offer(request: TimelineMosaicRepairRequest) {
        buffer.offer(request.timeBucket, request) { _, current, candidate ->
            candidate.timelineRepairVersion() >= current.timelineRepairVersion()
        }
    }

    fun offerAll(requests: Iterable<TimelineMosaicRepairRequest>) {
        buffer.offerAll(requests.map { request -> request.timeBucket to request }) { _, current, candidate ->
            candidate.timelineRepairVersion() >= current.timelineRepairVersion()
        }
    }

    fun requeueAll(requests: Iterable<TimelineMosaicRepairRequest>) {
        buffer.offerAll(requests.map { request -> request.timeBucket to request }) { _, current, candidate ->
            candidate.timelineRepairVersion() > current.timelineRepairVersion()
        }
    }

    suspend fun receiveBatch(): List<TimelineMosaicRepairRequest>? =
        buffer.receiveBatch()

    fun drainAvailable(): List<TimelineMosaicRepairRequest> =
        buffer.drainAvailable()

    fun close() {
        buffer.close()
    }

    fun pendingWakeupCountForTests(): Int =
        buffer.pendingWakeupCountForTests()
}

internal data class ActiveMosaicConfig(
    val groupSize: TimelineGroupSize,
    val columnCount: Int,
    val families: Set<MosaicTemplateFamily>,
    val mosaicEnabled: Boolean = true,
    val cacheMosaicResults: Boolean = true
)

internal enum class TimelineMosaicRuntimeResetMode {
    PreservePublished,
    ClearPublished
}

internal fun timelineMosaicStatesAfterReset(
    states: Map<MosaicCacheKey, RuntimeMosaicState>,
    mode: TimelineMosaicRuntimeResetMode
): Map<MosaicCacheKey, RuntimeMosaicState> =
    when (mode) {
        TimelineMosaicRuntimeResetMode.PreservePublished ->
            states.filterValues { it !is RuntimeMosaicState.Partial }

        TimelineMosaicRuntimeResetMode.ClearPublished -> emptyMap()
    }

internal data class TimelineRuntimeMosaicSection(
    val sectionKey: String,
    val sectionLabel: String,
    val assets: List<Asset>
)

internal data class TimelineRuntimeMosaicResumeState(
    val sessionStamp: SessionOperationStamp,
    val checkpoint: MosaicAssignmentCheckpoint,
    val chunks: List<ProgressChunk>
)

internal data class DayGroupsCacheKey(
    val timeBucket: String,
    val assetRevision: Int
)

internal typealias RuntimeMosaicProgressChunk = ProgressChunk

internal data class PendingProgressiveMosaicPublish(
    val sessionStamp: SessionOperationStamp,
    val generationStampsByKey: Map<MosaicCacheKey, TimelineRuntimeGenerationStamp>,
    val clearOriginsByKey: Map<MosaicCacheKey, TimelineProgressiveMosaicClearOrigin>,
    val updatesByKey: Map<MosaicCacheKey, List<RuntimeMosaicProgressChunk>>
)

internal data class TimelineProgressiveMosaicClearOrigin(
    val globalClearEpoch: Long,
    val bucketClearEpoch: Long,
    val keyClearEpoch: Long
)

private data class StampedRuntimeMosaicProgressChunk(
    val sessionStamp: SessionOperationStamp,
    val generationStamp: TimelineRuntimeGenerationStamp,
    val chunk: RuntimeMosaicProgressChunk
)

internal class TimelineProgressiveMosaicBuffer {
    private val mutex = Mutex()
    private val chunksByKey =
        linkedMapOf<MosaicCacheKey, MutableList<StampedRuntimeMosaicProgressChunk>>()
    private var globalClearEpoch: Long = 0L
    private val bucketClearEpochs = mutableMapOf<String, Long>()
    private val keyClearEpochs = mutableMapOf<MosaicCacheKey, Long>()

    suspend fun add(
        key: MosaicCacheKey,
        chunk: RuntimeMosaicProgressChunk,
        sessionStamp: SessionOperationStamp,
        generationStamp: TimelineRuntimeGenerationStamp
    ) {
        add(
            key = key,
            chunk = chunk,
            sessionStamp = sessionStamp,
            generationStamp = generationStamp,
            replaceEqualRange = true
        )
    }

    suspend fun requeueIfCurrent(
        key: MosaicCacheKey,
        chunk: RuntimeMosaicProgressChunk,
        sessionStamp: SessionOperationStamp,
        generationStamp: TimelineRuntimeGenerationStamp,
        clearOrigin: TimelineProgressiveMosaicClearOrigin
    ): Boolean {
        return mutex.withLock {
            if (clearOriginForLocked(key) != clearOrigin) return false
            addLocked(
                key = key,
                chunk = chunk,
                sessionStamp = sessionStamp,
                generationStamp = generationStamp,
                replaceEqualRange = false
            )
            true
        }
    }

    private suspend fun add(
        key: MosaicCacheKey,
        chunk: RuntimeMosaicProgressChunk,
        sessionStamp: SessionOperationStamp,
        generationStamp: TimelineRuntimeGenerationStamp,
        replaceEqualRange: Boolean
    ) {
        mutex.withLock {
            addLocked(key, chunk, sessionStamp, generationStamp, replaceEqualRange)
        }
    }

    private fun addLocked(
        key: MosaicCacheKey,
        chunk: RuntimeMosaicProgressChunk,
        sessionStamp: SessionOperationStamp,
        generationStamp: TimelineRuntimeGenerationStamp,
        replaceEqualRange: Boolean
    ) {
        val chunks = chunksByKey.getOrPut(key) { mutableListOf() }
        val stampedChunk = StampedRuntimeMosaicProgressChunk(sessionStamp, generationStamp, chunk)
        val newestExisting = chunks.maxWithOrNull(::compareProgressChunkVersion)
        if (newestExisting != null) {
            val versionComparison = compareProgressChunkVersion(stampedChunk, newestExisting)
            if (versionComparison < 0) return
            if (versionComparison > 0) chunks.clear()
        }
        val existingIndex = chunks.indexOfFirst {
            it.chunk.sourceStartIndex == chunk.sourceStartIndex &&
                    it.chunk.sourceEndExclusive == chunk.sourceEndExclusive
        }
        if (existingIndex >= 0) {
            if (replaceEqualRange) {
                chunks[existingIndex] = stampedChunk
            }
        } else {
            chunks.add(stampedChunk)
            chunks.sortBy { it.chunk.sourceStartIndex }
        }
    }

    suspend fun drainEligible(timeBuckets: Set<String>): List<PendingProgressiveMosaicPublish> {
        if (timeBuckets.isEmpty()) return emptyList()
        return mutex.withLock {
            val eligibleKeys = chunksByKey.keys
                .filter { it.timeBucket in timeBuckets }
                .toList()
            drainEligibleKeysLocked(eligibleKeys)
        }
    }

    suspend fun drainEligibleKeys(keys: Set<MosaicCacheKey>): List<PendingProgressiveMosaicPublish> {
        if (keys.isEmpty()) return emptyList()
        return mutex.withLock {
            val eligibleKeys = chunksByKey.keys
                .filter { it in keys }
                .toList()
            drainEligibleKeysLocked(eligibleKeys)
        }
    }

    private fun drainEligibleKeysLocked(
        eligibleKeys: List<MosaicCacheKey>
    ): List<PendingProgressiveMosaicPublish> {
        val updatesByStamp = linkedMapOf<
            SessionOperationStamp,
            LinkedHashMap<MosaicCacheKey, MutableList<RuntimeMosaicProgressChunk>>
        >()
        val generationStampsBySession = linkedMapOf<
            SessionOperationStamp,
            LinkedHashMap<MosaicCacheKey, TimelineRuntimeGenerationStamp>
        >()
        val clearOriginsBySession = linkedMapOf<
            SessionOperationStamp,
            LinkedHashMap<MosaicCacheKey, TimelineProgressiveMosaicClearOrigin>
        >()
        eligibleKeys.forEach { key ->
            chunksByKey.remove(key).orEmpty().forEach { stampedChunk ->
                updatesByStamp
                    .getOrPut(stampedChunk.sessionStamp) { linkedMapOf() }
                    .getOrPut(key) { mutableListOf() }
                    .add(stampedChunk.chunk)
                generationStampsBySession
                    .getOrPut(stampedChunk.sessionStamp) { linkedMapOf() }[key] =
                    stampedChunk.generationStamp
                clearOriginsBySession
                    .getOrPut(stampedChunk.sessionStamp) { linkedMapOf() }[key] =
                    clearOriginForLocked(key)
            }
        }
        return updatesByStamp.map { (sessionStamp, updatesByKey) ->
            PendingProgressiveMosaicPublish(
                sessionStamp = sessionStamp,
                generationStampsByKey = generationStampsBySession[sessionStamp].orEmpty(),
                clearOriginsByKey = clearOriginsBySession[sessionStamp].orEmpty(),
                updatesByKey = updatesByKey.mapValues { (_, chunks) -> chunks.toList() }
            )
        }
    }

    suspend fun clearKeys(keys: Set<MosaicCacheKey>) {
        if (keys.isEmpty()) return
        mutex.withLock {
            keys.forEach { key ->
                keyClearEpochs[key] = (keyClearEpochs[key] ?: 0L) + 1L
            }
            keys.forEach(chunksByKey::remove)
        }
    }

    suspend fun clearBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        mutex.withLock {
            timeBuckets.forEach { timeBucket ->
                bucketClearEpochs[timeBucket] = (bucketClearEpochs[timeBucket] ?: 0L) + 1L
            }
            keyClearEpochs.keys.removeAll { it.timeBucket in timeBuckets }
            chunksByKey.keys.removeAll { it.timeBucket in timeBuckets }
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            globalClearEpoch++
            bucketClearEpochs.clear()
            keyClearEpochs.clear()
            chunksByKey.clear()
        }
    }

    suspend fun isCurrentOrigin(
        key: MosaicCacheKey,
        clearOrigin: TimelineProgressiveMosaicClearOrigin
    ): Boolean =
        mutex.withLock { clearOriginForLocked(key) == clearOrigin }

    suspend fun snapshotSize(): Int =
        mutex.withLock { chunksByKey.values.sumOf { it.size } }

    private fun clearOriginForLocked(key: MosaicCacheKey): TimelineProgressiveMosaicClearOrigin =
        TimelineProgressiveMosaicClearOrigin(
            globalClearEpoch = globalClearEpoch,
            bucketClearEpoch = bucketClearEpochs[key.timeBucket] ?: 0L,
            keyClearEpoch = keyClearEpochs[key] ?: 0L
        )
}

private fun compareProgressChunkVersion(
    left: StampedRuntimeMosaicProgressChunk,
    right: StampedRuntimeMosaicProgressChunk
): Int =
    compareValuesBy(
        left,
        right,
        { it.sessionStamp.generation },
        { it.sessionStamp.ownerEpoch },
        { it.generationStamp.globalGeneration },
        { it.generationStamp.bucketGeneration }
    )

internal suspend fun runTimelineMosaicSessionSink(
    sessionOperationRunner: SessionOperationRunner,
    sessionStamp: SessionOperationStamp,
    block: suspend () -> Unit
): Boolean = try {
    coroutineScope {
        async {
            sessionOperationRunner.runForStamp(sessionStamp, block)
        }.await()
    }
    true
} catch (_: StaleSessionOperationException) {
    false
} catch (_: SessionOperationRejectedException) {
    false
}

internal suspend fun runIsolatedTimelineRuntimeReset(
    block: suspend () -> Unit
): Result<Unit> = try {
    supervisorScope {
        async {
            block()
        }.await()
    }
    Result.success(Unit)
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(e)
}

internal fun RuntimeMosaicState?.cacheToken(): String =
    when (this) {
        null -> "missing"
        RuntimeMosaicState.Pending -> "pending"
        is RuntimeMosaicState.Partial -> "partial_${chunks.size}_${chunks.lastOrNull()?.sourceEndExclusive ?: 0}"
        is RuntimeMosaicState.Ready -> "ready_${assignments.size}_${assignments.hashCode()}_${displayRecords.size}_${displayRecords.hashCode()}"
        RuntimeMosaicState.Failed -> "failed"
    }

internal fun timelineAggregateGeometryPlaceholderHeight(
    aggregateGeometryHeight: Float?,
    sectionStates: List<RuntimeMosaicState?>
): Float? {
    val height = aggregateGeometryHeight?.takeIf { it > 0f } ?: return null
    return if (sectionStates.none { it is RuntimeMosaicState.Ready || it is RuntimeMosaicState.Partial }) {
        height
    } else {
        null
    }
}

internal fun timelineProgressiveMosaicStateUpdates(
    states: Map<MosaicCacheKey, RuntimeMosaicState>,
    updatesByKey: Map<MosaicCacheKey, List<RuntimeMosaicProgressChunk>>
): Map<MosaicCacheKey, RuntimeMosaicState> {
    var next = states
    updatesByKey.forEach { (key, runtimeChunks) ->
        next = when (val existing = next[key]) {
            is RuntimeMosaicState.Ready -> next
            is RuntimeMosaicState.Partial -> {
                val chunks = (existing.chunks + runtimeChunks)
                    .distinctBy { it.sourceStartIndex to it.sourceEndExclusive }
                    .sortedBy { it.sourceStartIndex }
                next + (key to RuntimeMosaicState.Partial(chunks))
            }

            else -> next + (key to RuntimeMosaicState.Partial(
                runtimeChunks
                    .distinctBy { it.sourceStartIndex to it.sourceEndExclusive }
                    .sortedBy { it.sourceStartIndex }
            ))
        }
    }
    return next
}

internal fun timelineMergeMosaicStateUpdatesPreservingReady(
    states: Map<MosaicCacheKey, RuntimeMosaicState>,
    updates: Map<MosaicCacheKey, RuntimeMosaicState>
): Map<MosaicCacheKey, RuntimeMosaicState> {
    if (updates.isEmpty()) return states
    var next = states
    updates.forEach { (key, update) ->
        val existing = next[key]
        if (existing is RuntimeMosaicState.Ready && update !is RuntimeMosaicState.Ready) {
            return@forEach
        }
        next = next + (key to update)
    }
    return next
}

internal fun mosaicFamiliesKey(families: Set<MosaicTemplateFamily>): String =
    families.map { it.persistedId }.sorted().joinToString("|")

internal fun monthMosaicSectionKey(timeBucket: String): String = "$timeBucket|month"

internal fun timelineBucketGeometrySectionKey(
    timeBucket: String,
    groupSize: TimelineGroupSize,
    geometryRequest: TimelineMosaicGeometryRequest
): String =
    "$timeBucket|bucket|${groupSize.apiValue}|${timelineBucketGeometryRequestKey(geometryRequest)}"

internal fun timelineBucketGeometryRequestKey(request: TimelineMosaicGeometryRequest): String =
    "w=${(request.availableWidth * TIMELINE_MOSAIC_WIDTH_QUANTUM).roundToInt()}|" +
            "h=${(request.maxRowHeight * 100f).roundToInt()}|" +
            "s=${(request.spacing * 100f).roundToInt()}"

internal fun dayMosaicSectionKey(
    timeBucket: String,
    dayLabel: String
): String =
    "$timeBucket|day|$dayLabel"

internal fun timelinePlaceholderGridKey(
    prefix: String,
    timeBucket: String,
    sectionKey: String,
    chunkIndex: Int
): String = "${prefix}_${timeBucket}_${sectionKey}_$chunkIndex"

internal fun timelineMosaicBandGridKey(
    timeBucket: String,
    sectionKey: String,
    tileAssetIds: List<String>
): String = "mosaic_${timeBucket}_${sectionKey}_${tileAssetIds.joinToString("_")}"

internal data class TimelineGeometryInputs(
    val sections: Map<MosaicCacheKey, TimelineMosaicSectionGeometry>,
    val buckets: Map<MosaicCacheKey, Float>,
    val skeleton: TimelineSkeletonState
)
