package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.toDomain

class GetTimelineBucketsUseCase(private val repository: TimelineRepository) {
    suspend operator fun invoke(): Result<List<TimelineBucket>> {
        return runCatching {
            repository.getTimelineBuckets().map { it.toDomain() }
        }
    }
}
