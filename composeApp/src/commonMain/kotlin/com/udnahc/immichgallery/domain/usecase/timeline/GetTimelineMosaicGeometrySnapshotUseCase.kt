package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySnapshot

class GetTimelineMosaicGeometrySnapshotUseCase(
    private val repository: TimelineMosaicCacheRepository
) {
    suspend operator fun invoke(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ): Result<TimelineMosaicGeometrySnapshot> =
        repository.getPersistedGeometrySnapshot(
            timeBuckets,
            groupSize,
            columnCount,
            families,
            geometryRequest
        )
}
