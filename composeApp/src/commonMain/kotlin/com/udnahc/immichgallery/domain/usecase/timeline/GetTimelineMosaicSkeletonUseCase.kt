package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicSkeleton

class GetTimelineMosaicSkeletonUseCase(
    private val repository: TimelineMosaicCacheRepository
) {
    suspend operator fun invoke(
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest,
        timeBuckets: Set<String>? = null
    ): Result<TimelineMosaicSkeleton> =
        repository.getTimelineMosaicSkeleton(
            groupSize = groupSize,
            columnCount = columnCount,
            families = families,
            geometryRequest = geometryRequest,
            timeBuckets = timeBuckets
        )
}
