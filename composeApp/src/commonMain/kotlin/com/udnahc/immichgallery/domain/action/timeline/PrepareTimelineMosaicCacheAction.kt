package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicLayoutSpec
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicSectionRequest
import com.udnahc.immichgallery.domain.model.MosaicSectionResult
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.SectionReady
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineBucketSnapshot
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicArtifactBuilder
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val mosaicRenderEngine: MosaicRenderEngine = MosaicRenderEngine()
) {
    private val log = logging("PrepareTimelineMosaicCacheAction")

    suspend operator fun invoke(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest? = null,
        cacheDisplayResults: Boolean = true,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ): Result<TimelineMosaicPrecomputeResult> {
        if (timeBuckets.isEmpty()) {
            return Result.success(TimelineMosaicPrecomputeResult(emptySet(), emptySet()))
        }
        val assignmentLayoutSpec = mosaicAssignmentLayoutSpec(columnCount)
            ?: return Result.success(TimelineMosaicPrecomputeResult(emptySet(), timeBuckets))
        val request = geometryRequest
            ?: return Result.success(TimelineMosaicPrecomputeResult(emptySet(), timeBuckets))
        val displayLayoutSpec = mosaicLayoutSpecForColumnCount(request.availableWidth, columnCount)
            ?: return Result.success(TimelineMosaicPrecomputeResult(emptySet(), timeBuckets))
        return try {
            val normalizedFamilies = families.normalizedMosaicFamilies()
            val familiesKey = timelineMosaicFamiliesKey(normalizedFamilies)
            val groupMode = groupSize.apiValue
            log.d {
                "Timeline Mosaic precompute started buckets=${timeBuckets.size} " +
                        "group=$groupSize columns=$columnCount families=$normalizedFamilies geometry=true"
            }
            val semaphore = Semaphore(TIMELINE_MOSAIC_PRECOMPUTE_PARALLELISM)
            val progressMutex = Mutex()
            var completed = 0
            var successful = 0
            var failed = 0
            val results = coroutineScope {
                timeBuckets.map { timeBucket ->
                    async(Dispatchers.IO) {
                        val bucketGeometry = semaphore.withPermit {
                            try {
                                precomputeBucketMosaic(
                                    timeBucket = timeBucket,
                                    groupSize = groupSize,
                                    groupMode = groupMode,
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
                                log.e(e) { "Failed to precompute Timeline Mosaic for $timeBucket" }
                                null
                            }
                        }
                        progressMutex.withLock {
                            completed++
                            if (bucketGeometry != null) successful++ else failed++
                            log.d {
                                "Timeline Mosaic precompute progress $completed/${timeBuckets.size}: " +
                                        "bucket=$timeBucket success=${bucketGeometry != null} " +
                                        "successful=$successful failed=$failed"
                            }
                        }
                        timeBucket to bucketGeometry
                    }
                }.awaitAll()
            }
            Result.success(
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = results.filter { it.second != null }.map { it.first }
                        .toSet(),
                    failedBucketIds = results.filter { it.second == null }.map { it.first }.toSet(),
                    bucketGeometrySummaries = results.mapNotNull { it.second }
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
        groupSize: TimelineGroupSize,
        groupMode: String,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        familiesKey: String,
        assignmentLayoutSpec: MosaicLayoutSpec,
        displayLayoutSpec: MosaicLayoutSpec,
        geometryRequest: TimelineMosaicGeometryRequest,
        cacheDisplayResults: Boolean,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit
    ): TimelineBucketGeometrySummary {
        val snapshot = snapshotReader(timeBucket)
        if (shouldRejectUnsyncedEmptyTimelineBucket(snapshot.assets.size, snapshot.expectedCount)) {
            throw IllegalStateException(
                "Refusing Timeline Mosaic precompute for unsynced bucket=$timeBucket " +
                        "expectedCount=${snapshot.expectedCount} actualAssets=${snapshot.assets.size}"
            )
        }
        val sections = timelineMosaicSections(timeBucket, groupSize, snapshot.assets)
        val now = currentEpochMillis()
        val fingerprint = orderedTimelineAssetsFingerprint(snapshot.assets)
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
            onProgressChunk = onProgressChunk
        )
        val artifacts = withContext(Dispatchers.Default) {
            artifactBuilder.build(
                snapshot = snapshot,
                groupSize = groupSize,
                groupMode = groupMode,
                columnCount = columnCount,
                familiesKey = familiesKey,
                geometryRequest = geometryRequest,
                sections = sections,
                readySections = readySections,
                cacheDisplayResults = cacheDisplayResults,
                updatedAt = now
            )
        } ?: throw IllegalStateException("Timeline Mosaic artifact build failed bucket=$timeBucket")
        return repository.replaceTimelineMosaicArtifactsForBucketConfig(artifacts).getOrThrow()
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
        withContext(Dispatchers.Default) {
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

private fun currentEpochMillis(): Long =
    kotlin.time.Clock.System.now().toEpochMilliseconds()

private const val TIMELINE_MOSAIC_PRECOMPUTE_PARALLELISM = 4
