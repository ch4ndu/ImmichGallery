package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult

class PrecomputeTimelineMosaicAction(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>
    ): Result<TimelineMosaicPrecomputeResult> =
        repository.precomputeTimelineMosaic(timeBuckets, groupSize, columnCount, families)
}
