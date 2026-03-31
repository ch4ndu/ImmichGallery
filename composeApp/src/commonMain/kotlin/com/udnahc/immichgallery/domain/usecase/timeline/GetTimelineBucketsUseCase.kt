package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.TimelineBucket

class GetTimelineBucketsUseCase(private val repository: TimelineRepository) {
    suspend operator fun invoke(): Result<List<TimelineBucket>> {
        return runCatching {
            repository.refreshBuckets()
        }
    }
}
