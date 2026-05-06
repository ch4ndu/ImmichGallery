package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicSectionRequest
import com.udnahc.immichgallery.domain.model.MosaicSectionResult
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.SectionReady
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import com.udnahc.immichgallery.domain.model.TimelineMosaicProgressChunk
import com.udnahc.immichgallery.domain.model.TimelineMosaicSectionPrecomputeRequest

class PrepareTimelineMosaicCacheAction(
    private val repository: TimelineMosaicCacheRepository,
    private val mosaicRenderEngine: MosaicRenderEngine = MosaicRenderEngine()
) {
    suspend operator fun invoke(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest? = null,
        cacheDisplayResults: Boolean = true,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ): Result<TimelineMosaicPrecomputeResult> =
        repository.precomputeTimelineMosaic(
            timeBuckets = timeBuckets,
            groupSize = groupSize,
            columnCount = columnCount,
            families = families,
            geometryRequest = geometryRequest,
            cacheDisplayResults = cacheDisplayResults,
            computeSection = { request ->
                computeSection(
                    request = request,
                    onProgressChunk = onProgressChunk
                )
            }
        )

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
