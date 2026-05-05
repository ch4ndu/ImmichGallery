package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import com.udnahc.immichgallery.domain.model.TimelineMosaicProgressChunk

class PrepareTimelineMosaicCacheAction(
    private val repository: TimelineMosaicCacheRepository
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
            onProgressChunk = onProgressChunk
        )
}
