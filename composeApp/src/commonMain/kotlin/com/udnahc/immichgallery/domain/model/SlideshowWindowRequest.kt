package com.udnahc.immichgallery.domain.model

import com.udnahc.immichgallery.data.repository.SessionOperationStamp

/** Ephemeral metadata only. Sources never retain screen callbacks or ViewModels. */
sealed interface SlideshowWindowSource {
    data class Album(val id: String) : SlideshowWindowSource
    data class Person(val id: String) : SlideshowWindowSource
    data class Search(val endpoint: SearchEndpoint, val criteria: SearchCriteria) : SlideshowWindowSource
    data object Favorites : SlideshowWindowSource
    data class Snapshot(val assets: List<Asset>) : SlideshowWindowSource
    data class Timeline(val buckets: List<TimelineBucket>, val openingBucket: String) : SlideshowWindowSource
}

data class SlideshowWindowRequest(
    val source: SlideshowWindowSource,
    val initialAssetId: String,
    val config: SlideshowConfig,
    val stamp: SessionOperationStamp
)
