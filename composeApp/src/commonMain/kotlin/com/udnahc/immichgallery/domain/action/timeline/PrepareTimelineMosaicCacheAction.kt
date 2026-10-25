package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicLayoutSpec
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicSectionRequest
import com.udnahc.immichgallery.domain.model.MosaicSectionResult
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.SectionReady
import com.udnahc.immichgallery.domain.model.TIMELINE_MOSAIC_PRECOMPUTE_GROUP_SIZES
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineBucketSnapshot
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicArtifactBuilder
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketSkeleton
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import com.udnahc.immichgallery.domain.model.TimelineMosaicProgressChunk
import com.udnahc.immichgallery.domain.model.TimelineMosaicSection
import com.udnahc.immichgallery.domain.model.TimelineMosaicSectionPrecomputeRequest
import com.udnahc.immichgallery.domain.model.mosaicAssignmentLayoutSpec
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import com.udnahc.immichgallery.domain.model.orderedTimelineAssetsFingerprint
import com.udnahc.immichgallery.domain.model.shouldRejectUnsyncedEmptyTimelineBucket
import com.udnahc.immichgallery.domain.model.timelineMosaicFamiliesKey
import com.udnahc.immichgallery.domain.model.timelineMosaicSections
import com.udnahc.immichgallery.domain.model.toSkeleton
import com.udnahc.immichgallery.domain.model.toSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.lighthousegames.logging.logging

class PrepareTimelineMosaicCacheAction(
    private val repository: TimelineMosaicCacheRepository,
    private val snapshotReader: TimelineBucketSnapshotReader,
    private val artifactBuilder: TimelineMosaicArtifactBuilder = TimelineMosaicArtifactBuilder(),
    private val mosaicRenderEngine: MosaicRenderEngine = MosaicRenderEngine(),
    private val sessionStateRepository: SessionStateRepository = SessionStateRepository()
) {
    private val log = logging("PrepareTimelineMosaicCacheAction")
    @OptIn(ExperimentalCoroutinesApi::class)
    private val computeDispatcher =
        Dispatchers.Default.limitedParallelism(TIMELINE_MOSAIC_PRECOMPUTE_CPU_PARALLELISM)

    suspend operator fun invoke(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest? = null,
        cacheDisplayResults: Boolean = true,
        groupSizes: Set<TimelineGroupSize> = TIMELINE_MOSAIC_PRECOMPUTE_GROUP_SIZES,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ): Result<TimelineMosaicPrecomputeResult> = sessionStateRepository.withOperation {
        prepare(
            timeBuckets = timeBuckets,
            groupSize = groupSize,
            groupSizes = groupSizes,
            columnCount = columnCount,
            families = families,
            geometryRequest = geometryRequest,
            cacheDisplayResults = cacheDisplayResults,
            onProgressChunk = onProgressChunk
        )
    }

    private suspend fun prepare(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        groupSizes: Set<TimelineGroupSize>,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest?,
        cacheDisplayResults: Boolean,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit
    ): Result<TimelineMosaicPrecomputeResult> {
        if (timeBuckets.isEmpty()) {
            return Result.success(TimelineMosaicPrecomputeResult(emptySet(), emptySet()))
        }
        val orderedGroupSizes = (listOf(groupSize).filter { it in groupSizes } + (groupSizes - groupSize)).distinct()
        if (orderedGroupSizes.isEmpty()) {
            return Result.success(TimelineMosaicPrecomputeResult(emptySet(), timeBuckets))
        }
        val failedByAllGroups = orderedGroupSizes.associateWith { timeBuckets }
        val assignmentLayoutSpec = mosaicAssignmentLayoutSpec(columnCount)
            ?: return Result.success(
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = emptySet(),
                    failedBucketIds = timeBuckets,
                    failedBucketIdsByGroup = failedByAllGroups
                )
            )
        val request = geometryRequest
            ?: return Result.success(
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = emptySet(),
                    failedBucketIds = timeBuckets,
                    failedBucketIdsByGroup = failedByAllGroups
                )
            )
        val displayLayoutSpec = mosaicLayoutSpecForColumnCount(request.availableWidth, columnCount)
            ?: return Result.success(
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = emptySet(),
                    failedBucketIds = timeBuckets,
                    failedBucketIdsByGroup = failedByAllGroups
                )
            )
        return try {
            val normalizedFamilies = families.normalizedMosaicFamilies()
            val familiesKey = timelineMosaicFamiliesKey(normalizedFamilies)
            log.d {
                "Timeline Mosaic precompute started buckets=<redacted> " +
                        "group=<redacted> columns=<redacted> families=<redacted> geometry=true"
            }
            val semaphore = Semaphore(TIMELINE_MOSAIC_PRECOMPUTE_PARALLELISM)
            val progressMutex = Mutex()
            var completed = 0
            var successful = 0
            var failed = 0
            val results = coroutineScope {
                timeBuckets.map { timeBucket ->
                    async(Dispatchers.IO) {
                        val bucketOutcome = semaphore.withPermit {
                            try {
                                precomputeBucketMosaic(
                                    timeBucket = timeBucket,
                                    primaryGroupSize = groupSize,
                                    orderedGroupSizes = orderedGroupSizes,
                                    columnCount = columnCount,
                                    families = normalizedFamilies,
                                    familiesKey = familiesKey,
                                    assignmentLayoutSpec = assignmentLayoutSpec,
                                    displayLayoutSpec = displayLayoutSpec,
                                    geometryRequest = request,
                                    cacheDisplayResults = cacheDisplayResults,
                                    onProgressChunk = onProgressChunk
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.e { "Failed to precompute Timeline Mosaic for <redacted>" }
                                BucketPrecomputeOutcome(
                                    outputsByGroup = emptyMap(),
                                    failedGroupSizes = orderedGroupSizes.toSet()
                                )
                            }
                        }
                        val isBucketSuccess = bucketOutcome.isCompleteFor(orderedGroupSizes)
                        progressMutex.withLock {
                            completed++
                            if (isBucketSuccess) successful++ else failed++
                            log.d {
                                "Timeline Mosaic precompute progress <redacted>/<redacted>: " +
                                        "bucket=<redacted> success=<redacted> " +
                                        "successful=<redacted> failed=<redacted>"
                            }
                        }
                        timeBucket to bucketOutcome
                    }
                }.awaitAll()
            }
            val successfulOutcomes = results.filter { it.second.isCompleteFor(orderedGroupSizes) }
            val failedOutcomes = results.filterNot { it.second.isCompleteFor(orderedGroupSizes) }
            val bucketGeometrySummariesByGroup = orderedGroupSizes.associateWith { targetGroupSize ->
                successfulOutcomes.mapNotNull { (_, outcome) ->
                    outcome.outputsByGroup[targetGroupSize]?.bucketSummary
                }
            }
            val sectionGeometrySummariesByGroup = orderedGroupSizes.associateWith { targetGroupSize ->
                successfulOutcomes.flatMap { (_, outcome) ->
                    outcome.outputsByGroup[targetGroupSize]?.sectionSummaries.orEmpty()
                }
            }
            val bucketSkeletonsByGroup = orderedGroupSizes.associateWith { targetGroupSize ->
                successfulOutcomes.mapNotNull { (timeBucket, outcome) ->
                    outcome.outputsByGroup[targetGroupSize]?.bucketSkeleton?.let { timeBucket to it }
                }.toMap()
            }
            val failedBucketIdsByGroup = orderedGroupSizes.associateWith { targetGroupSize ->
                results
                    .filter { (_, outcome) -> targetGroupSize in outcome.failedGroupSizes }
                    .map { (timeBucket, _) -> timeBucket }
                    .toSet()
            }.filterValues { it.isNotEmpty() }
            val primaryGroupSize = orderedGroupSizes.first()
            Result.success(
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = successfulOutcomes.map { it.first }.toSet(),
                    failedBucketIds = failedOutcomes.map { it.first }.toSet(),
                    bucketGeometrySummaries = bucketGeometrySummariesByGroup[primaryGroupSize].orEmpty(),
                    bucketGeometrySummariesByGroup = bucketGeometrySummariesByGroup,
                    sectionGeometrySummaries = sectionGeometrySummariesByGroup[primaryGroupSize].orEmpty(),
                    sectionGeometrySummariesByGroup = sectionGeometrySummariesByGroup,
                    bucketSkeletons = bucketSkeletonsByGroup[primaryGroupSize].orEmpty(),
                    bucketSkeletonsByGroup = bucketSkeletonsByGroup,
                    failedBucketIdsByGroup = failedBucketIdsByGroup
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun precomputeBucketMosaic(
        timeBucket: String,
        primaryGroupSize: TimelineGroupSize,
        orderedGroupSizes: List<TimelineGroupSize>,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        familiesKey: String,
        assignmentLayoutSpec: MosaicLayoutSpec,
        displayLayoutSpec: MosaicLayoutSpec,
        geometryRequest: TimelineMosaicGeometryRequest,
        cacheDisplayResults: Boolean,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit
    ): BucketPrecomputeOutcome {
        val snapshot = snapshotReader(timeBucket)
        if (shouldRejectUnsyncedEmptyTimelineBucket(snapshot.assets.size, snapshot.expectedCount)) {
            throw IllegalStateException(
                "Timeline Mosaic precompute requires synchronized bucket assets"
            )
        }
        val now = currentEpochMillis()
        val fingerprint = orderedTimelineAssetsFingerprint(snapshot.assets)
        val outputsByGroup = linkedMapOf<TimelineGroupSize, BucketGroupPrecomputeOutput>()
        val failedGroups = linkedSetOf<TimelineGroupSize>()
        for (targetGroupSize in orderedGroupSizes) {
            try {
                val targetGroupMode = targetGroupSize.apiValue
                val sections = timelineMosaicSections(timeBucket, targetGroupSize, snapshot.assets)
                val readySections = computeReadySections(
                    snapshot = snapshot,
                    sections = sections,
                    columnCount = columnCount,
                    familiesKey = familiesKey,
                    fingerprint = fingerprint,
                    generation = now,
                    assignmentLayoutSpec = assignmentLayoutSpec,
                    displayLayoutSpec = displayLayoutSpec,
                    geometryRequest = geometryRequest,
                    families = families,
                    onProgressChunk = if (targetGroupSize == primaryGroupSize) {
                        onProgressChunk
                    } else {
                        { _ -> }
                    }
                )
                val artifacts = withContext(computeDispatcher) {
                    artifactBuilder.build(
                        snapshot = snapshot,
                        groupSize = targetGroupSize,
                        groupMode = targetGroupMode,
                        columnCount = columnCount,
                        familiesKey = familiesKey,
                        geometryRequest = geometryRequest,
                        sections = sections,
                        readySections = readySections,
                        cacheDisplayResults = cacheDisplayResults,
                        updatedAt = now
                    )
                } ?: throw IllegalStateException("Timeline Mosaic artifact build failed")
                val skeleton = artifacts.toSkeleton(
                    groupSize = targetGroupSize,
                    assetCount = snapshot.assets.size
                ) ?: throw IllegalStateException("Timeline Mosaic skeleton validation failed")
                val bucketSummary =
                    repository.replaceTimelineMosaicArtifactsForBucketConfig(artifacts).getOrThrow()
                outputsByGroup[targetGroupSize] = BucketGroupPrecomputeOutput(
                    bucketSummary = bucketSummary,
                    sectionSummaries = artifacts.sectionGeometry.map { it.toSummary() },
                    bucketSkeleton = skeleton
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e { "Failed to precompute Timeline Mosaic group for <redacted>" }
                failedGroups.add(targetGroupSize)
            }
        }
        return BucketPrecomputeOutcome(
            outputsByGroup = outputsByGroup,
            failedGroupSizes = failedGroups
        )
    }

    private suspend fun computeReadySections(
        snapshot: TimelineBucketSnapshot,
        sections: List<TimelineMosaicSection>,
        columnCount: Int,
        familiesKey: String,
        fingerprint: String,
        generation: Long,
        assignmentLayoutSpec: MosaicLayoutSpec,
        displayLayoutSpec: MosaicLayoutSpec,
        geometryRequest: TimelineMosaicGeometryRequest,
        families: Set<MosaicTemplateFamily>,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit
    ): List<SectionReady> =
        withContext(computeDispatcher) {
            sections.map { section ->
                computeSection(
                    request = TimelineMosaicSectionPrecomputeRequest(
                        timeBucket = snapshot.timeBucket,
                        sectionKey = section.sectionKey,
                        sectionLabel = section.label,
                        assets = section.assets,
                        bucketIndex = 0,
                        columnCount = columnCount,
                        familiesKey = familiesKey,
                        contentFingerprint = fingerprint,
                        generation = generation,
                        assignmentLayoutSpec = assignmentLayoutSpec,
                        displayLayoutSpec = displayLayoutSpec,
                        spacing = geometryRequest.spacing,
                        maxRowHeight = geometryRequest.maxRowHeight,
                        enabledFamilies = families
                    ),
                    onProgressChunk = onProgressChunk
                )
            }
        }

    private suspend fun computeSection(
        request: TimelineMosaicSectionPrecomputeRequest,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit
    ): SectionReady {
        val sectionRequest = MosaicSectionRequest(
            keyScope = MosaicKeyScope(
                owner = MosaicOwnerKey(MosaicOwnerScope.TIMELINE_BUCKET, request.timeBucket),
                sectionKey = request.sectionKey,
                columnCount = request.columnCount,
                familiesKey = request.familiesKey,
                contentFingerprint = request.contentFingerprint,
                generation = request.generation
            ),
            assets = request.assets,
            bucketIndex = request.bucketIndex,
            sectionLabel = request.sectionLabel,
            assignmentLayoutSpec = request.assignmentLayoutSpec,
            displayLayoutSpec = request.displayLayoutSpec,
            spacing = request.spacing,
            maxRowHeight = request.maxRowHeight,
            enabledFamilies = request.enabledFamilies
        )
        return when (val result = mosaicRenderEngine.computeSection(
            request = sectionRequest,
            onProgressChunk = { chunk ->
                onProgressChunk(
                    TimelineMosaicProgressChunk(
                        timeBucket = request.timeBucket,
                        sectionKey = request.sectionKey,
                        sectionLabel = request.sectionLabel,
                        sourceStartIndex = chunk.sourceStartIndex,
                        sourceEndExclusive = chunk.sourceEndExclusive,
                        assignments = chunk.assignments
                    )
                )
            }
        )) {
            is MosaicSectionResult.Ready -> result.value
            is MosaicSectionResult.Failed -> throw result.value.cause
        }
    }
}

private data class BucketGroupPrecomputeOutput(
    val bucketSummary: TimelineBucketGeometrySummary,
    val sectionSummaries: List<TimelineMosaicGeometrySummary>,
    val bucketSkeleton: TimelineMosaicBucketSkeleton
)

private data class BucketPrecomputeOutcome(
    val outputsByGroup: Map<TimelineGroupSize, BucketGroupPrecomputeOutput>,
    val failedGroupSizes: Set<TimelineGroupSize>
) {
    fun isCompleteFor(requiredGroups: List<TimelineGroupSize>): Boolean =
        failedGroupSizes.isEmpty() && requiredGroups.all { it in outputsByGroup }
}

private fun currentEpochMillis(): Long =
    kotlin.time.Clock.System.now().toEpochMilliseconds()

private const val TIMELINE_MOSAIC_PRECOMPUTE_PARALLELISM = 4
private const val TIMELINE_MOSAIC_PRECOMPUTE_CPU_PARALLELISM = 2
