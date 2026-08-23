package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.SectionGeometry
import com.udnahc.immichgallery.domain.model.SectionReady
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketArtifacts
import com.udnahc.immichgallery.domain.model.TimelineMosaicArtifactBuilder
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicRepairSectionInput
import com.udnahc.immichgallery.domain.model.displayRecordsCoverOrderedAssets
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import com.udnahc.immichgallery.domain.model.orderedTimelineAssetsFingerprint
import com.udnahc.immichgallery.domain.model.timelineMosaicFamiliesKey
import com.udnahc.immichgallery.domain.model.timelineMosaicSections
import com.udnahc.immichgallery.domain.model.toPhotoGridDisplayItems
import com.udnahc.immichgallery.domain.model.validateMosaicAssignments
import com.udnahc.immichgallery.domain.model.validateMosaicSectionGeometry
import kotlinx.coroutines.CancellationException

class RepairTimelineMosaicCacheAction(
    private val repository: TimelineMosaicCacheRepository,
    private val snapshotReader: TimelineBucketSnapshotReader,
    private val artifactBuilder: TimelineMosaicArtifactBuilder,
    private val mosaicRenderEngine: MosaicRenderEngine = MosaicRenderEngine(),
    private val sessionStateRepository: SessionStateRepository = SessionStateRepository()
) {
    suspend operator fun invoke(
        timeBucket: String,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest,
        sectionsByKey: Map<String, TimelineMosaicRepairSectionInput>
    ): Result<Unit> = sessionStateRepository.withOperation {
        prepareRepair(timeBucket, groupSize, columnCount, families, geometryRequest, sectionsByKey)
            .fold(
                onSuccess = { artifacts ->
                    repository.replaceTimelineMosaicArtifactsForBucketConfig(artifacts).map { Unit }
                },
                onFailure = { Result.failure(it) }
            )
    }

    suspend fun prepare(
        timeBucket: String,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest,
        sectionsByKey: Map<String, TimelineMosaicRepairSectionInput>
    ): Result<TimelineMosaicBucketArtifacts> = sessionStateRepository.withOperation {
        prepareRepair(timeBucket, groupSize, columnCount, families, geometryRequest, sectionsByKey)
    }

    suspend fun commit(
        artifacts: TimelineMosaicBucketArtifacts
    ): Result<Unit> = sessionStateRepository.withOperation {
        repository.replaceTimelineMosaicArtifactsForBucketConfig(artifacts).map { Unit }
    }

    private suspend fun prepareRepair(
        timeBucket: String,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest,
        sectionsByKey: Map<String, TimelineMosaicRepairSectionInput>
    ): Result<TimelineMosaicBucketArtifacts> = try {
        val snapshot = snapshotReader(timeBucket)
        val sections = timelineMosaicSections(timeBucket, groupSize, snapshot.assets)
        val normalizedFamilies = families.normalizedMosaicFamilies()
        val familiesKey = timelineMosaicFamiliesKey(normalizedFamilies)
        val layoutSpec = mosaicLayoutSpecForColumnCount(geometryRequest.availableWidth, columnCount)
            ?: return Result.failure(IllegalArgumentException("Invalid Timeline Mosaic repair layout"))
        val fingerprint = orderedTimelineAssetsFingerprint(snapshot.assets)
        val readySections = sections.map { section ->
            val input = sectionsByKey[section.sectionKey]
                ?: return Result.failure(IllegalArgumentException("Missing Timeline Mosaic repair section"))
            if (!validateMosaicAssignments(input.assignments, section.assets, normalizedFamilies) ||
                !validateMosaicSectionGeometry(
                    input.geometry.ranges,
                    section.assets.size,
                    input.geometry.placeholderHeight,
                    geometryRequest.spacing
                )
            ) {
                return Result.failure(IllegalArgumentException("Invalid Timeline Mosaic repair section"))
            }
            val displayItems = if (input.displayRecords.displayRecordsCoverOrderedAssets(section.assets)) {
                input.displayRecords.toPhotoGridDisplayItems(section.assets, 0, section.sectionKey)
            } else {
                mosaicRenderEngine.projectReadySection(
                    assets = section.assets,
                    assignments = input.assignments,
                    bucketIndex = 0,
                    sectionLabel = section.sectionKey,
                    layoutSpec = layoutSpec,
                    spacing = geometryRequest.spacing,
                    maxRowHeight = geometryRequest.maxRowHeight
                )
            }
            val keyScope = MosaicKeyScope(
                owner = MosaicOwnerKey(MosaicOwnerScope.TIMELINE_BUCKET, timeBucket),
                sectionKey = section.sectionKey,
                columnCount = columnCount,
                familiesKey = familiesKey,
                contentFingerprint = fingerprint
            )
            if (kotlin.math.abs(
                    estimatePhotoGridDisplayItemsHeight(displayItems, geometryRequest.spacing) -
                            input.geometry.placeholderHeight
                ) > 0.5f
            ) {
                return Result.failure(IllegalArgumentException("Timeline Mosaic repair height mismatch"))
            }
            SectionReady(
                keyScope = keyScope,
                assignments = input.assignments,
                displayItems = displayItems,
                geometry = SectionGeometry(
                    keyScope = keyScope,
                    placeholderHeight = input.geometry.placeholderHeight,
                    displayItemCount = input.geometry.displayItemCount,
                    ranges = input.geometry.ranges
                )
            )
        }
        val artifacts = artifactBuilder.build(
            snapshot = snapshot,
            groupSize = groupSize,
            groupMode = groupSize.apiValue,
            columnCount = columnCount,
            familiesKey = familiesKey,
            geometryRequest = geometryRequest,
            sections = sections,
            readySections = readySections,
            cacheDisplayResults = true,
            updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        ) ?: return Result.failure(IllegalStateException("Timeline Mosaic repair artifact build failed"))
        Result.success(artifacts)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
