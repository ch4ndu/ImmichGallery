package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicCacheStatus
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest

class GetTimelineMosaicCacheStatusUseCase(
    private val repository: TimelineMosaicCacheRepository
) {
    suspend operator fun invoke(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest? = null,
        includeDisplayCache: Boolean = true
    ): Result<TimelineMosaicCacheStatus> =
        repository.getPersistedMosaicCacheStatus(
            timeBuckets = timeBuckets,
            groupSize = groupSize,
            columnCount = columnCount,
            families = families,
            geometryRequest = geometryRequest,
            includeDisplayCache = includeDisplayCache
        )
}
