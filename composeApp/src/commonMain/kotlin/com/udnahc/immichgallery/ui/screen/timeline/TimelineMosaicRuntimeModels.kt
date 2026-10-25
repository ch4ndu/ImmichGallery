package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.data.repository.SessionOperationRejectedException
import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.data.repository.StaleSessionOperationException
import com.udnahc.immichgallery.domain.action.auth.SessionOperationRunner
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.photoGridDisplayItemHeightDp
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.AssetGroupDisplayLabel
import com.udnahc.immichgallery.domain.model.GeometryAlignedRange
import com.udnahc.immichgallery.domain.model.GeometryAlignedSection
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.TimelineDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketSkeleton
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import com.udnahc.immichgallery.domain.model.timelineDaySectionDisplayLabel
import com.udnahc.immichgallery.domain.model.timelineMosaicGroupingZoneId
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
    required: TimelineSkeletonIdentity?,
    requiredBuckets: Set<String> = emptySet()
): Boolean =
    required == null || (
        state.identity == required &&
            state.status == TimelineSkeletonLoadStatus.Loaded &&
            requiredBuckets.all { it in state.buckets }
    )

internal fun shouldRepairTimelineSkeletonAfterSync(
    skeletonIdentity: TimelineSkeletonIdentity?,
    skeletonState: TimelineSkeletonState,
    timelineGeometryReady: Boolean,
    requiredBuckets: Set<String>
): Boolean =
    skeletonIdentity != null && (
        !timelineGeometryReady ||
            !timelineSkeletonGateOpen(skeletonState, skeletonIdentity, requiredBuckets)
    )

internal data class PreparedTimelineBucketGeometry(
    val skeleton: TimelineMosaicBucketSkeleton,
    val assetRevision: Int
)

internal data class TimelineSkeletonReadOrigin(
    val identity: TimelineSkeletonIdentity,
    val siblingIdentity: TimelineSkeletonIdentity,
    val globalGeneration: Long,
    val bucketGenerations: Map<String, Long>,
    val assetRevisions: Map<String, Int>,
    val bucketEpochs: Map<String, Long>,
    val requestedBuckets: Set<String>? = null
) {
    fun acceptsBucket(
        timeBucket: String,
        currentGlobalGeneration: Long,
        currentBucketGenerations: Map<String, Long>,
        currentAssetRevisions: Map<String, Int>,
        currentBucketEpochs: Map<String, Long>
    ): Boolean {
        if (currentGlobalGeneration != globalGeneration) return false
        if (requestedBuckets != null && timeBucket !in requestedBuckets) return false
        return (currentBucketGenerations[timeBucket] ?: 0L) == (bucketGenerations[timeBucket] ?: 0L) &&
            (currentAssetRevisions[timeBucket] ?: 0) == (assetRevisions[timeBucket] ?: 0) &&
            (currentBucketEpochs[timeBucket] ?: 0L) == (bucketEpochs[timeBucket] ?: 0L)
    }
}

internal fun siblingTimelineSkeletonIdentity(
    identity: TimelineSkeletonIdentity,
    groupingZoneIdResolver: (TimelineGroupSize) -> String = ::timelineMosaicGroupingZoneId
): TimelineSkeletonIdentity {
    val siblingGroupSize = if (identity.groupSize == TimelineGroupSize.MONTH) {
        TimelineGroupSize.DAY
    } else {
        TimelineGroupSize.MONTH
    }
    return TimelineSkeletonIdentity(
        groupSize = siblingGroupSize,
        columnCount = identity.columnCount,
        familiesKey = identity.familiesKey,
        geometryRequest = identity.geometryRequest,
        groupingZoneId = groupingZoneIdResolver(siblingGroupSize)
    )
}

internal fun timelineMosaicSectionKeyMatchesGroup(
    timeBucket: String,
    sectionKey: String,
    groupSize: TimelineGroupSize
): Boolean = when (groupSize) {
    TimelineGroupSize.MONTH -> sectionKey == monthMosaicSectionKey(timeBucket)
    TimelineGroupSize.DAY -> sectionKey.startsWith("$timeBucket|day|")
}

internal class TimelinePreparedGeometryCoordinator(
    private val skeletonState: MutableStateFlow<TimelineSkeletonState>,
    private val bucketGeometryStates: MutableStateFlow<Map<MosaicCacheKey, Float>>,
    private val mosaicGeometryStates: MutableStateFlow<Map<MosaicCacheKey, TimelineMosaicSectionGeometry>>,
    private val timelineGeometryReady: MutableStateFlow<Boolean>,
    private val updateGeometryReadyBuckets: ((Set<MosaicCacheKey>) -> Set<MosaicCacheKey>) -> Unit,
    private val groupingZoneIdResolver: (TimelineGroupSize) -> String = ::timelineMosaicGroupingZoneId
) {
    private val preparedSkeletonsByIdentity =
        mutableMapOf<TimelineSkeletonIdentity, Map<String, PreparedTimelineBucketGeometry>>()

    fun currentPreparedSkeletonsForIdentity(
        identity: TimelineSkeletonIdentity,
        assetRevisions: Map<String, Int>
    ): Map<String, TimelineMosaicBucketSkeleton>? {
        val preparedByBucket = preparedSkeletonsByIdentity[identity] ?: return null
        val currentSkeletons = LinkedHashMap<String, TimelineMosaicBucketSkeleton>(preparedByBucket.size)
        for ((timeBucket, entry) in preparedByBucket) {
            if ((assetRevisions[timeBucket] ?: 0) == entry.assetRevision) {
                currentSkeletons[timeBucket] = entry.skeleton
            }
        }
        return currentSkeletons
    }

    fun hasPreparedCoverage(
        identity: TimelineSkeletonIdentity,
        requiredBuckets: Set<String>,
        isSourceReady: Boolean,
        assetRevisions: Map<String, Int>
    ): Boolean {
        val currentSkeletons = currentPreparedSkeletonsForIdentity(identity, assetRevisions) ?: return false
        if (currentSkeletons.isEmpty() && (!isSourceReady || requiredBuckets.isNotEmpty())) {
            return false
        }
        return requiredBuckets.all { it in currentSkeletons }
    }

    fun hasDualGroupPreparedCoverage(
        identity: TimelineSkeletonIdentity,
        requiredBuckets: Set<String>,
        isSourceReady: Boolean,
        assetRevisions: Map<String, Int>
    ): Boolean {
        val siblingIdentity = siblingTimelineSkeletonIdentity(identity, groupingZoneIdResolver)
        return hasPreparedCoverage(identity, requiredBuckets, isSourceReady, assetRevisions) &&
            hasPreparedCoverage(siblingIdentity, requiredBuckets, isSourceReady, assetRevisions)
    }

    fun restorePreparedForIdentity(
        identity: TimelineSkeletonIdentity?,
        requiredBuckets: Set<String>,
        isSourceReady: Boolean,
        assetRevisions: Map<String, Int>
    ): Boolean {
        if (identity == null) {
            timelineGeometryReady.value = true
            return true
        }
        if (!hasDualGroupPreparedCoverage(identity, requiredBuckets, isSourceReady, assetRevisions)) {
            if (skeletonState.value.identity == identity &&
                skeletonState.value.status == TimelineSkeletonLoadStatus.Loaded
            ) {
                skeletonState.value = skeletonState.value.copy(
                    status = TimelineSkeletonLoadStatus.Loading
                )
            }
            timelineGeometryReady.value = false
            return false
        }
        val siblingIdentity = siblingTimelineSkeletonIdentity(identity, groupingZoneIdResolver)
        val primarySkeletons = currentPreparedSkeletonsForIdentity(identity, assetRevisions) ?: return false
        val siblingSkeletons = currentPreparedSkeletonsForIdentity(siblingIdentity, assetRevisions) ?: return false
        hydrateRuntimeGeometryFromSkeletons(
            identity = identity,
            skeletons = primarySkeletons,
            assetRevisions = assetRevisions,
            updateActiveSkeleton = true
        )
        hydrateRuntimeGeometryFromSkeletons(
            identity = siblingIdentity,
            skeletons = siblingSkeletons,
            assetRevisions = assetRevisions,
            updateActiveSkeleton = false
        )
        timelineGeometryReady.value = true
        return true
    }

    fun invalidateBuckets(
        timeBuckets: Set<String>,
        requiredBuckets: Set<String> = skeletonState.value.buckets.keys + timeBuckets,
        isSourceReady: Boolean = true,
        assetRevisions: Map<String, Int> = emptyMap()
    ) {
        if (timeBuckets.isEmpty()) return
        if (preparedSkeletonsByIdentity.isNotEmpty()) {
            val iterator = preparedSkeletonsByIdentity.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val filtered = entry.value.filterKeys { it !in timeBuckets }
                if (filtered.isEmpty()) {
                    iterator.remove()
                } else if (filtered.size != entry.value.size) {
                    entry.setValue(filtered)
                }
            }
        }
        val currentSkeleton = skeletonState.value
        val bumped = currentSkeleton.withBumpedEpochs(timeBuckets)
        val nextBuckets = bumped.buckets - timeBuckets
        val activeIdentity = bumped.identity
        val stillComplete = activeIdentity != null &&
            requiredBuckets.all { it in nextBuckets } &&
            hasDualGroupPreparedCoverage(
                identity = activeIdentity,
                requiredBuckets = requiredBuckets,
                isSourceReady = isSourceReady,
                assetRevisions = assetRevisions
            )
        val nextStatus = if (!stillComplete && bumped.status == TimelineSkeletonLoadStatus.Loaded) {
            TimelineSkeletonLoadStatus.Loading
        } else {
            bumped.status
        }
        skeletonState.value = bumped.copy(
            status = nextStatus,
            buckets = nextBuckets
        )
        if (activeIdentity != null && !stillComplete) {
            timelineGeometryReady.value = false
        }
        bucketGeometryStates.value = bucketGeometryStates.value.filterKeys { it.timeBucket !in timeBuckets }
        mosaicGeometryStates.value = mosaicGeometryStates.value.filterKeys { it.timeBucket !in timeBuckets }
        updateGeometryReadyBuckets { keys -> keys.filterNot { it.timeBucket in timeBuckets }.toSet() }
    }

    fun beginFullLoad(
        identity: TimelineSkeletonIdentity,
        requiredBuckets: Set<String>,
        isSourceReady: Boolean,
        assetRevisions: Map<String, Int>,
        generationSnapshot: TimelineRuntimeGenerationSnapshot
    ): TimelineSkeletonReadOrigin? {
        if (restorePreparedForIdentity(identity, requiredBuckets, isSourceReady, assetRevisions)) {
            return null
        }
        val nextSkeleton = skeletonState.value
            .startingLoad(identity)
            .copy(status = TimelineSkeletonLoadStatus.Loading)
        skeletonState.value = nextSkeleton
        timelineGeometryReady.value = false
        return TimelineSkeletonReadOrigin(
            identity = identity,
            siblingIdentity = siblingTimelineSkeletonIdentity(identity, groupingZoneIdResolver),
            globalGeneration = generationSnapshot.globalGeneration,
            bucketGenerations = generationSnapshot.bucketGenerations,
            assetRevisions = assetRevisions,
            bucketEpochs = nextSkeleton.bucketEpochs,
            requestedBuckets = null
        )
    }

    fun beginBucketReload(
        identity: TimelineSkeletonIdentity,
        timeBuckets: Set<String>,
        assetRevisions: Map<String, Int>,
        generationSnapshot: TimelineRuntimeGenerationSnapshot
    ): TimelineSkeletonReadOrigin? {
        if (timeBuckets.isEmpty() || skeletonState.value.identity != identity) return null
        val nextSkeleton = skeletonState.value.withBumpedEpochs(timeBuckets)
        skeletonState.value = nextSkeleton
        return TimelineSkeletonReadOrigin(
            identity = identity,
            siblingIdentity = siblingTimelineSkeletonIdentity(identity, groupingZoneIdResolver),
            globalGeneration = generationSnapshot.globalGeneration,
            bucketGenerations = generationSnapshot.bucketGenerations,
            assetRevisions = assetRevisions,
            bucketEpochs = nextSkeleton.bucketEpochs,
            requestedBuckets = timeBuckets
        )
    }

    fun captureRepairOrigin(
        readOrigin: TimelineSkeletonReadOrigin,
        missingBuckets: Set<String>,
        activeIdentity: TimelineSkeletonIdentity?,
        assetRevisions: Map<String, Int>,
        generationSnapshot: TimelineRuntimeGenerationSnapshot
    ): TimelineSkeletonReadOrigin? {
        if (missingBuckets.isEmpty() ||
            activeIdentity != readOrigin.identity ||
            generationSnapshot.globalGeneration != readOrigin.globalGeneration
        ) {
            return null
        }
        return TimelineSkeletonReadOrigin(
            identity = readOrigin.identity,
            siblingIdentity = readOrigin.siblingIdentity,
            globalGeneration = generationSnapshot.globalGeneration,
            bucketGenerations = generationSnapshot.bucketGenerations,
            assetRevisions = assetRevisions,
            bucketEpochs = skeletonState.value.bucketEpochs,
            requestedBuckets = missingBuckets
        )
    }

    fun applyAuthoritativeSkeletonRead(
        targetIdentity: TimelineSkeletonIdentity,
        loadedSkeletons: Map<String, TimelineMosaicBucketSkeleton>,
        origin: TimelineSkeletonReadOrigin,
        currentGenerationSnapshot: TimelineRuntimeGenerationSnapshot,
        currentAssetRevisions: Map<String, Int>,
        knownBuckets: Set<String>,
        updateActiveSkeleton: Boolean
    ) {
        if (currentGenerationSnapshot.globalGeneration != origin.globalGeneration) return
        val currentEpochs = skeletonState.value.bucketEpochs
        fun isUnchanged(bucket: String): Boolean =
            origin.acceptsBucket(
                timeBucket = bucket,
                currentGlobalGeneration = currentGenerationSnapshot.globalGeneration,
                currentBucketGenerations = currentGenerationSnapshot.bucketGenerations,
                currentAssetRevisions = currentAssetRevisions,
                currentBucketEpochs = currentEpochs
            )

        val candidateBuckets = origin.requestedBuckets ?: buildSet {
            addAll(knownBuckets)
            addAll(loadedSkeletons.keys)
            preparedSkeletonsByIdentity[targetIdentity]?.keys?.let(::addAll)
            if (skeletonState.value.identity == targetIdentity) {
                addAll(skeletonState.value.buckets.keys)
            }
        }
        val evaluatedBuckets = candidateBuckets.filterTo(linkedSetOf(), ::isUnchanged)
        val acceptedSkeletons = LinkedHashMap<String, TimelineMosaicBucketSkeleton>()
        for (bucket in evaluatedBuckets) {
            loadedSkeletons[bucket]?.let { acceptedSkeletons[bucket] = it }
        }
        val rejectedBuckets = evaluatedBuckets - acceptedSkeletons.keys

        val updatedPrepared = LinkedHashMap<String, PreparedTimelineBucketGeometry>()
        preparedSkeletonsByIdentity[targetIdentity]?.forEach { (bucket, entry) ->
            if (bucket !in evaluatedBuckets && (currentAssetRevisions[bucket] ?: 0) == entry.assetRevision) {
                updatedPrepared[bucket] = entry
            }
        }
        acceptedSkeletons.forEach { (bucket, skeleton) ->
            updatedPrepared[bucket] = PreparedTimelineBucketGeometry(
                skeleton = skeleton,
                assetRevision = origin.assetRevisions[bucket] ?: 0
            )
        }
        preparedSkeletonsByIdentity[targetIdentity] = updatedPrepared

        if (rejectedBuckets.isNotEmpty()) {
            fun matchesBucketGeometryKey(key: MosaicCacheKey): Boolean =
                key.timeBucket in rejectedBuckets &&
                    key.columnCount == targetIdentity.columnCount &&
                    key.familiesKey == targetIdentity.familiesKey &&
                    key.sectionKey == timelineBucketGeometrySectionKey(
                        key.timeBucket,
                        targetIdentity.groupSize,
                        targetIdentity.geometryRequest
                    )

            bucketGeometryStates.value = bucketGeometryStates.value.filterKeys { !matchesBucketGeometryKey(it) }
            updateGeometryReadyBuckets { keys -> keys.filterNot(::matchesBucketGeometryKey).toSet() }
            mosaicGeometryStates.value = mosaicGeometryStates.value.filterKeys { key ->
                !(key.timeBucket in rejectedBuckets &&
                    key.columnCount == targetIdentity.columnCount &&
                    key.familiesKey == targetIdentity.familiesKey &&
                    timelineMosaicSectionKeyMatchesGroup(key.timeBucket, key.sectionKey, targetIdentity.groupSize))
            }
        }

        hydrateRuntimeGeometryFromSkeletons(
            identity = targetIdentity,
            skeletons = acceptedSkeletons,
            assetRevisions = origin.assetRevisions,
            updateActiveSkeleton = false
        )

        if (updateActiveSkeleton && skeletonState.value.identity == targetIdentity) {
            val currentActiveBuckets = skeletonState.value.buckets
            val nextActiveBuckets = (currentActiveBuckets - evaluatedBuckets) + acceptedSkeletons
            skeletonState.value = skeletonState.value.copy(buckets = nextActiveBuckets)
        }
    }

    fun completeFullLoad(
        readOrigin: TimelineSkeletonReadOrigin,
        primaryReadSkeletons: Map<String, TimelineMosaicBucketSkeleton>?,
        siblingReadSkeletons: Map<String, TimelineMosaicBucketSkeleton>?,
        repairOrigin: TimelineSkeletonReadOrigin? = null,
        repairedPrimarySkeletons: Map<String, TimelineMosaicBucketSkeleton>? = null,
        repairedSiblingSkeletons: Map<String, TimelineMosaicBucketSkeleton>? = null,
        activeIdentity: TimelineSkeletonIdentity?,
        requiredBuckets: Set<String>,
        isSourceReady: Boolean,
        currentGenerationSnapshot: TimelineRuntimeGenerationSnapshot,
        currentAssetRevisions: Map<String, Int>
    ): Boolean {
        if (activeIdentity != readOrigin.identity ||
            currentGenerationSnapshot.globalGeneration != readOrigin.globalGeneration
        ) {
            return false
        }
        if (primaryReadSkeletons != null) {
            applyAuthoritativeSkeletonRead(
                targetIdentity = readOrigin.identity,
                loadedSkeletons = primaryReadSkeletons,
                origin = readOrigin,
                currentGenerationSnapshot = currentGenerationSnapshot,
                currentAssetRevisions = currentAssetRevisions,
                knownBuckets = requiredBuckets,
                updateActiveSkeleton = true
            )
        }
        if (siblingReadSkeletons != null) {
            applyAuthoritativeSkeletonRead(
                targetIdentity = readOrigin.siblingIdentity,
                loadedSkeletons = siblingReadSkeletons,
                origin = readOrigin,
                currentGenerationSnapshot = currentGenerationSnapshot,
                currentAssetRevisions = currentAssetRevisions,
                knownBuckets = requiredBuckets,
                updateActiveSkeleton = false
            )
        }
        if (repairOrigin != null) {
            if (repairedPrimarySkeletons != null) {
                applyAuthoritativeSkeletonRead(
                    targetIdentity = repairOrigin.identity,
                    loadedSkeletons = repairedPrimarySkeletons,
                    origin = repairOrigin,
                    currentGenerationSnapshot = currentGenerationSnapshot,
                    currentAssetRevisions = currentAssetRevisions,
                    knownBuckets = requiredBuckets,
                    updateActiveSkeleton = true
                )
            }
            if (repairedSiblingSkeletons != null) {
                applyAuthoritativeSkeletonRead(
                    targetIdentity = repairOrigin.siblingIdentity,
                    loadedSkeletons = repairedSiblingSkeletons,
                    origin = repairOrigin,
                    currentGenerationSnapshot = currentGenerationSnapshot,
                    currentAssetRevisions = currentAssetRevisions,
                    knownBuckets = requiredBuckets,
                    updateActiveSkeleton = false
                )
            }
        }
        val dualGroupComplete = hasDualGroupPreparedCoverage(
            identity = readOrigin.identity,
            requiredBuckets = requiredBuckets,
            isSourceReady = isSourceReady,
            assetRevisions = currentAssetRevisions
        )
        val activePreparedBuckets =
            currentPreparedSkeletonsForIdentity(readOrigin.identity, currentAssetRevisions).orEmpty()
        val mergedActiveBuckets = (skeletonState.value.buckets.takeIf {
            skeletonState.value.identity == readOrigin.identity
        }.orEmpty() + activePreparedBuckets).filterKeys { bucket ->
            bucket in activePreparedBuckets
        }
        return if (dualGroupComplete && requiredBuckets.all { it in mergedActiveBuckets }) {
            skeletonState.value = skeletonState.value.copy(
                identity = readOrigin.identity,
                status = TimelineSkeletonLoadStatus.Loaded,
                buckets = mergedActiveBuckets
            )
            timelineGeometryReady.value = true
            true
        } else {
            skeletonState.value = skeletonState.value.copy(
                identity = readOrigin.identity,
                status = TimelineSkeletonLoadStatus.Failed,
                buckets = mergedActiveBuckets
            )
            timelineGeometryReady.value = false
            false
        }
    }

    fun failFullLoad(
        identity: TimelineSkeletonIdentity,
        expectedGlobalGeneration: Long? = null,
        currentGlobalGeneration: Long? = null
    ): Boolean {
        if (expectedGlobalGeneration != null &&
            currentGlobalGeneration != null &&
            expectedGlobalGeneration != currentGlobalGeneration
        ) {
            return false
        }
        if (skeletonState.value.identity != identity) return false
        if (skeletonState.value.status == TimelineSkeletonLoadStatus.Loaded && timelineGeometryReady.value) {
            return false
        }
        skeletonState.value = skeletonState.value.copy(
            identity = identity,
            status = TimelineSkeletonLoadStatus.Failed
        )
        timelineGeometryReady.value = false
        return true
    }

    fun completeBucketReload(
        reloadOrigin: TimelineSkeletonReadOrigin,
        primaryReloadSkeletons: Map<String, TimelineMosaicBucketSkeleton>?,
        siblingReloadSkeletons: Map<String, TimelineMosaicBucketSkeleton>?,
        activeIdentity: TimelineSkeletonIdentity?,
        requiredBuckets: Set<String>,
        isSourceReady: Boolean,
        currentGenerationSnapshot: TimelineRuntimeGenerationSnapshot,
        currentAssetRevisions: Map<String, Int>
    ): Boolean {
        if (activeIdentity != reloadOrigin.identity ||
            currentGenerationSnapshot.globalGeneration != reloadOrigin.globalGeneration
        ) {
            return false
        }
        if (primaryReloadSkeletons != null) {
            applyAuthoritativeSkeletonRead(
                targetIdentity = reloadOrigin.identity,
                loadedSkeletons = primaryReloadSkeletons,
                origin = reloadOrigin,
                currentGenerationSnapshot = currentGenerationSnapshot,
                currentAssetRevisions = currentAssetRevisions,
                knownBuckets = requiredBuckets,
                updateActiveSkeleton = true
            )
        }
        if (siblingReloadSkeletons != null) {
            applyAuthoritativeSkeletonRead(
                targetIdentity = reloadOrigin.siblingIdentity,
                loadedSkeletons = siblingReloadSkeletons,
                origin = reloadOrigin,
                currentGenerationSnapshot = currentGenerationSnapshot,
                currentAssetRevisions = currentAssetRevisions,
                knownBuckets = requiredBuckets,
                updateActiveSkeleton = false
            )
        }
        val dualGroupComplete = hasDualGroupPreparedCoverage(
            identity = reloadOrigin.identity,
            requiredBuckets = requiredBuckets,
            isSourceReady = isSourceReady,
            assetRevisions = currentAssetRevisions
        )
        val activePreparedBuckets =
            currentPreparedSkeletonsForIdentity(reloadOrigin.identity, currentAssetRevisions).orEmpty()
        val mergedActiveBuckets = (skeletonState.value.buckets.takeIf {
            skeletonState.value.identity == reloadOrigin.identity
        }.orEmpty() + activePreparedBuckets).filterKeys { bucket ->
            bucket in activePreparedBuckets
        }
        return if (dualGroupComplete && requiredBuckets.all { it in mergedActiveBuckets }) {
            skeletonState.value = skeletonState.value.copy(
                identity = reloadOrigin.identity,
                status = TimelineSkeletonLoadStatus.Loaded,
                buckets = mergedActiveBuckets
            )
            timelineGeometryReady.value = true
            true
        } else {
            skeletonState.value = skeletonState.value.copy(
                identity = reloadOrigin.identity,
                status = TimelineSkeletonLoadStatus.Loading,
                buckets = mergedActiveBuckets
            )
            timelineGeometryReady.value = false
            false
        }
    }

    fun publishPrecomputedResult(
        summariesByGroup: Map<TimelineGroupSize, List<TimelineBucketGeometrySummary>>,
        sectionSummariesByGroup: Map<TimelineGroupSize, List<TimelineMosaicGeometrySummary>>,
        skeletonsByGroup: Map<TimelineGroupSize, Map<String, TimelineMosaicBucketSkeleton>>,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest,
        assetRevisions: Map<String, Int>,
        activeIdentity: TimelineSkeletonIdentity?,
        requiredBuckets: Set<String>,
        isSourceReady: Boolean
    ): Set<String> {
        val publishedBuckets = buildSet {
            summariesByGroup.values.forEach { list -> list.forEach { add(it.timeBucket) } }
            sectionSummariesByGroup.values.forEach { list -> list.forEach { add(it.timeBucket) } }
            skeletonsByGroup.values.forEach { map -> addAll(map.keys) }
        }
        if (publishedBuckets.isNotEmpty()) {
            skeletonState.value = skeletonState.value.withBumpedEpochs(publishedBuckets)
        }

        val familiesKey = mosaicFamiliesKey(families)
        val groupSizes = (summariesByGroup.keys + sectionSummariesByGroup.keys + skeletonsByGroup.keys)
        var missingSkeletonBucketsForActive: Set<String> = emptySet()

        for (group in groupSizes) {
            val groupIdentity = TimelineSkeletonIdentity(
                groupSize = group,
                columnCount = columnCount,
                familiesKey = familiesKey,
                geometryRequest = geometryRequest,
                groupingZoneId = groupingZoneIdResolver(group)
            )
            val groupSkeletons = skeletonsByGroup[group].orEmpty()
            if (groupSkeletons.isNotEmpty() || (requiredBuckets.isEmpty() && isSourceReady)) {
                storePreparedSkeletons(
                    identity = groupIdentity,
                    skeletons = groupSkeletons,
                    assetRevisions = assetRevisions
                )
            }
            val matchesActive = activeIdentity != null &&
                activeIdentity.columnCount == columnCount &&
                activeIdentity.familiesKey == familiesKey &&
                activeIdentity.geometryRequest == geometryRequest
            if (!matchesActive) continue

            val groupSummaries = summariesByGroup[group].orEmpty()
            if (groupSummaries.isNotEmpty()) {
                val bucketUpdates = groupSummaries.associate { geometry ->
                    MosaicCacheKey(
                        timeBucket = geometry.timeBucket,
                        sectionKey = timelineBucketGeometrySectionKey(geometry.timeBucket, group, geometryRequest),
                        columnCount = columnCount,
                        assetRevision = assetRevisions[geometry.timeBucket] ?: 0,
                        familiesKey = familiesKey
                    ) to geometry.placeholderHeight
                }
                bucketGeometryStates.value = bucketGeometryStates.value + bucketUpdates
                val readyBuckets = bucketUpdates.keys
                updateGeometryReadyBuckets { existing -> existing + readyBuckets }
            }

            val groupSections = sectionSummariesByGroup[group].orEmpty()
            val sectionUpdates = LinkedHashMap<MosaicCacheKey, TimelineMosaicSectionGeometry>()
            groupSections.forEach { section ->
                sectionUpdates[
                    MosaicCacheKey(
                        timeBucket = section.timeBucket,
                        sectionKey = section.sectionKey,
                        columnCount = columnCount,
                        assetRevision = assetRevisions[section.timeBucket] ?: 0,
                        familiesKey = familiesKey
                    )
                ] = TimelineMosaicSectionGeometry(
                    placeholderHeight = section.placeholderHeight,
                    ranges = section.ranges
                )
            }
            groupSkeletons.values.forEach { skeleton ->
                skeleton.sections.forEach { section ->
                    val key = MosaicCacheKey(
                        timeBucket = skeleton.timeBucket,
                        sectionKey = section.sectionKey,
                        columnCount = columnCount,
                        assetRevision = assetRevisions[skeleton.timeBucket] ?: 0,
                        familiesKey = familiesKey
                    )
                    if (key !in sectionUpdates) {
                        sectionUpdates[key] = TimelineMosaicSectionGeometry(
                            placeholderHeight = section.placeholderHeight,
                            ranges = section.ranges
                        )
                    }
                }
            }
            if (sectionUpdates.isNotEmpty()) {
                mosaicGeometryStates.value = mosaicGeometryStates.value + sectionUpdates
            }

            if (groupIdentity == activeIdentity && groupSkeletons.isNotEmpty()) {
                val nextBuckets = if (skeletonState.value.identity == groupIdentity) {
                    skeletonState.value.buckets + groupSkeletons
                } else {
                    groupSkeletons
                }
                skeletonState.value = skeletonState.value.copy(
                    identity = groupIdentity,
                    buckets = nextBuckets
                )
            }
        }

        if (activeIdentity != null &&
            activeIdentity.columnCount == columnCount &&
            activeIdentity.familiesKey == familiesKey &&
            activeIdentity.geometryRequest == geometryRequest
        ) {
            val siblingIdentity = siblingTimelineSkeletonIdentity(activeIdentity, groupingZoneIdResolver)
            val activePrepared = currentPreparedSkeletonsForIdentity(activeIdentity, assetRevisions).orEmpty()
            val siblingPrepared = currentPreparedSkeletonsForIdentity(siblingIdentity, assetRevisions).orEmpty()
            missingSkeletonBucketsForActive = publishedBuckets
                .filter { it !in activePrepared || it !in siblingPrepared }
                .toSet()
            val dualGroupComplete = hasDualGroupPreparedCoverage(
                identity = activeIdentity,
                requiredBuckets = requiredBuckets,
                isSourceReady = isSourceReady,
                assetRevisions = assetRevisions
            )
            val currentBuckets = (skeletonState.value.buckets.takeIf {
                skeletonState.value.identity == activeIdentity
            }.orEmpty() + activePrepared).filterKeys { bucket ->
                bucket in activePrepared
            }
            if (dualGroupComplete && requiredBuckets.all { it in currentBuckets }) {
                skeletonState.value = skeletonState.value.copy(
                    identity = activeIdentity,
                    status = TimelineSkeletonLoadStatus.Loaded,
                    buckets = currentBuckets
                )
                timelineGeometryReady.value = true
            } else {
                skeletonState.value = skeletonState.value.copy(
                    identity = activeIdentity,
                    status = TimelineSkeletonLoadStatus.Loading,
                    buckets = currentBuckets
                )
                timelineGeometryReady.value = false
            }
        }

        return missingSkeletonBucketsForActive
    }

    private fun storePreparedSkeletons(
        identity: TimelineSkeletonIdentity,
        skeletons: Map<String, TimelineMosaicBucketSkeleton>,
        assetRevisions: Map<String, Int>
    ) {
        val updated = LinkedHashMap<String, PreparedTimelineBucketGeometry>()
        preparedSkeletonsByIdentity[identity]?.forEach { (bucket, entry) ->
            if ((assetRevisions[bucket] ?: 0) == entry.assetRevision) {
                updated[bucket] = entry
            }
        }
        skeletons.forEach { (bucket, skeleton) ->
            updated[bucket] = PreparedTimelineBucketGeometry(
                skeleton = skeleton,
                assetRevision = assetRevisions[bucket] ?: 0
            )
        }
        preparedSkeletonsByIdentity[identity] = updated
    }

    private fun hydrateRuntimeGeometryFromSkeletons(
        identity: TimelineSkeletonIdentity,
        skeletons: Map<String, TimelineMosaicBucketSkeleton>,
        assetRevisions: Map<String, Int>,
        updateActiveSkeleton: Boolean
    ) {
        if (skeletons.isNotEmpty()) {
            val bucketUpdates = LinkedHashMap<MosaicCacheKey, Float>(skeletons.size)
            val sectionUpdates = LinkedHashMap<MosaicCacheKey, TimelineMosaicSectionGeometry>()
            skeletons.forEach { (timeBucket, skeleton) ->
                val assetRevision = assetRevisions[timeBucket] ?: 0
                bucketUpdates[
                    MosaicCacheKey(
                        timeBucket = timeBucket,
                        sectionKey = timelineBucketGeometrySectionKey(
                            timeBucket,
                            identity.groupSize,
                            identity.geometryRequest
                        ),
                        columnCount = identity.columnCount,
                        assetRevision = assetRevision,
                        familiesKey = identity.familiesKey
                    )
                ] = skeleton.placeholderHeight
                skeleton.sections.forEach { section ->
                    sectionUpdates[
                        MosaicCacheKey(
                            timeBucket = timeBucket,
                            sectionKey = section.sectionKey,
                            columnCount = identity.columnCount,
                            assetRevision = assetRevision,
                            familiesKey = identity.familiesKey
                        )
                    ] = TimelineMosaicSectionGeometry(
                        placeholderHeight = section.placeholderHeight,
                        ranges = section.ranges
                    )
                }
            }
            bucketGeometryStates.value = bucketGeometryStates.value + bucketUpdates
            mosaicGeometryStates.value = mosaicGeometryStates.value + sectionUpdates
            val readyBuckets = bucketUpdates.keys
            updateGeometryReadyBuckets { existing -> existing + readyBuckets }
        }
        if (updateActiveSkeleton) {
            skeletonState.value = skeletonState.value.copy(
                identity = identity,
                status = TimelineSkeletonLoadStatus.Loaded,
                buckets = skeletons
            )
        }
    }
}

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
            is MosaicBandItem -> item.copy(gridKey = key, bandHeight = range.height)
            is RowItem -> item.copy(gridKey = key, rowHeight = range.height)
            else -> PlaceholderItem(
                gridKey = key,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                estimatedHeight = range.height,
                sourceStartIndex = range.sourceStartIndex,
                sourceCount = range.sourceCount
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

internal fun timelineBucketDisplayHeights(
    displayItems: List<TimelineDisplayItem>,
    bucketCount: Int
): List<Float> {
    val heights = FloatArray(bucketCount)
    displayItems.forEach { item ->
        if (item.bucketIndex in 0 until bucketCount) {
            heights[item.bucketIndex] += photoGridDisplayItemHeightDp(item) + GRID_SPACING_DP
        }
    }
    return heights.toList()
}

// Walks outward from the settled viewport, nearest first, until each direction covers the screen budget or bucket cap.
internal fun selectCacheOffWarmupBucketIndexes(
    settledIndexes: List<Int>,
    bucketHeights: List<Float>,
    viewportHeight: Float,
    screensPerDirection: Float,
    maxBucketsPerDirection: Int,
    isComputed: (Int) -> Boolean
): List<Int> {
    if (settledIndexes.isEmpty() || bucketHeights.isEmpty() || viewportHeight <= 0f) return emptyList()
    val budget = viewportHeight * screensPerDirection
    fun direction(start: Int, step: Int): List<Int> = buildList {
        var index = start
        var covered = 0f
        var visited = 0
        while (index in bucketHeights.indices && covered < budget && visited < maxBucketsPerDirection) {
            if (index !in settledIndexes && !isComputed(index)) add(index)
            covered += bucketHeights[index]
            visited++
            index += step
        }
    }
    val below = direction(settledIndexes.max() + 1, 1)
    val above = direction(settledIndexes.min() - 1, -1)
    return buildList {
        for (i in 0 until maxOf(below.size, above.size)) {
            below.getOrNull(i)?.let(::add)
            above.getOrNull(i)?.let(::add)
        }
    }
}

// Splits a bucket-ordinal range across estimate pieces in proportion to their heights.
internal fun List<PlaceholderItem>.withBucketOrdinals(base: Int, count: Int): List<PlaceholderItem> {
    if (isEmpty() || count <= 0) return this
    val total = sumOf { it.estimatedHeight.toDouble() }
    if (total <= 0.0) return this
    var cumulative = 0.0
    return mapIndexed { index, item ->
        val start = (cumulative / total * count).toInt()
        cumulative += item.estimatedHeight
        val end = if (index == lastIndex) count else (cumulative / total * count).toInt()
        item.copy(
            sourceStartIndex = base + start,
            sourceCount = (end - start).coerceAtLeast(0),
            sourceIsBucketOrdinal = true
        )
    }
}
