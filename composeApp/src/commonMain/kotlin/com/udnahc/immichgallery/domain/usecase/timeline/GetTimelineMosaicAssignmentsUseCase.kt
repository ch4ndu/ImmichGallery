package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicCacheStatus

class GetTimelineMosaicAssignmentsUseCase(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>
    ): Result<TimelineMosaicCacheStatus> =
        repository.getPersistedMosaicCacheStatus(timeBuckets, groupSize, columnCount, families)
}
