package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository

class ClearTimelineMosaicCacheAction(
    private val repository: TimelineMosaicCacheRepository
) {
    suspend fun buckets(timeBuckets: Set<String>) {
        repository.clearBuckets(timeBuckets)
    }

    suspend fun all() {
        repository.clearAll()
    }
}
