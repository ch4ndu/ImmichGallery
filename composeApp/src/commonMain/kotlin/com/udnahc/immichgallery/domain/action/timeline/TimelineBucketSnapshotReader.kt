package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.TimelineBucketSnapshot

class TimelineBucketSnapshotReader(
    private val repository: TimelineRepository
) {
    suspend operator fun invoke(timeBucket: String): TimelineBucketSnapshot =
        repository.getBucketSnapshot(timeBucket)
}
