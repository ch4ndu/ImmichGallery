package com.udnahc.immichgallery.domain.usecase.timeline

import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineBucketSyncResult
import kotlinx.coroutines.flow.Flow

class GetTimelineBucketsUseCase(private val repository: TimelineRepository) {
    fun observe(): Flow<List<TimelineBucket>> = repository.observeBuckets()

    suspend fun sync(): Result<TimelineBucketSyncResult> = repository.syncBuckets()

    suspend fun getLastSyncedAt(): Long? = repository.getLastSyncedAt()

    suspend fun hasCachedBuckets(): Boolean = repository.hasCachedBuckets()

    suspend fun getLoadedBucketIds(): Set<String> = repository.getLoadedBucketIds()
}
