package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.ui.component.PhotoOverlayInitialIndexResolution
import kotlin.math.abs

internal sealed interface TimelinePagerPageKey {
    data class MaterializedAsset(val assetId: String) : TimelinePagerPageKey

    data class UnresolvedBucketOffset(
        val timeBucket: String,
        val localOffset: Int
    ) : TimelinePagerPageKey
}

internal sealed interface TimelinePagerAnchor {
    val timeBucket: String
    val localOffset: Int
    val pageIndex: Int

    data class Materialized(
        val assetId: String,
        override val timeBucket: String,
        override val localOffset: Int,
        override val pageIndex: Int
    ) : TimelinePagerAnchor

    data class Unresolved(
        override val timeBucket: String,
        override val localOffset: Int,
        override val pageIndex: Int
    ) : TimelinePagerAnchor
}

internal sealed interface TimelinePagerAnchorResolution {
    data class Found(
        val pageIndex: Int,
        val anchor: TimelinePagerAnchor.Materialized
    ) : TimelinePagerAnchorResolution

    data class Pending(
        val anchor: TimelinePagerAnchor.Unresolved
    ) : TimelinePagerAnchorResolution {
        val timeBucket: String get() = anchor.timeBucket
        val pageIndex: Int get() = anchor.pageIndex
    }

    data object Empty : TimelinePagerAnchorResolution
}

internal data class TimelinePagerProjectionKey(
    val bucketIds: List<String>,
    val pageIndex: com.udnahc.immichgallery.domain.model.TimelinePageIndex
)

internal data class TimelineOverlayInitialIndexKey(
    val projectionKey: TimelinePagerProjectionKey,
    val isSourceReady: Boolean,
    val isLoading: Boolean
)

internal fun timelineOverlayInitialIndexResolution(
    index: Int?,
    sourcePending: Boolean
): PhotoOverlayInitialIndexResolution = when {
    index != null -> PhotoOverlayInitialIndexResolution.Found(index)
    sourcePending -> PhotoOverlayInitialIndexResolution.Pending
    else -> PhotoOverlayInitialIndexResolution.Absent
}

internal fun timelineOverlaySourcePending(
    isSourceReady: Boolean,
    isLoading: Boolean,
    hasBuckets: Boolean,
    totalPages: Int
): Boolean = !isSourceReady || isLoading || (hasBuckets && totalPages == 0)

internal fun timelinePagerProjectionKey(
    state: TimelineOverlayState
): TimelinePagerProjectionKey = TimelinePagerProjectionKey(
    bucketIds = state.buckets.map { it.timeBucket },
    pageIndex = state.pageIndex
)

internal fun timelineOverlayInitialIndexKey(state: TimelineState): TimelineOverlayInitialIndexKey =
    TimelineOverlayInitialIndexKey(
        projectionKey = timelinePagerProjectionKey(
            TimelineOverlayState(pageIndex = state.pageIndex, buckets = state.buckets)
        ),
        isSourceReady = state.isSourceReady,
        isLoading = state.isLoading
    )

internal fun timelinePagerPageKey(
    page: Int,
    state: TimelineOverlayState,
    assetProvider: (String) -> List<Asset>?
): TimelinePagerPageKey {
    val location = timelinePagerPageLocation(page, state)
        ?: return TimelinePagerPageKey.UnresolvedBucketOffset("missing", page)
    val asset = assetProvider(location.timeBucket)?.getOrNull(location.localOffset)
    return if (asset != null) {
        TimelinePagerPageKey.MaterializedAsset(asset.id)
    } else {
        TimelinePagerPageKey.UnresolvedBucketOffset(
            timeBucket = location.timeBucket,
            localOffset = location.localOffset
        )
    }
}

internal fun captureTimelinePagerAnchor(
    page: Int,
    state: TimelineOverlayState,
    assetProvider: (String) -> List<Asset>?
): TimelinePagerAnchor? {
    val location = timelinePagerPageLocation(page, state) ?: return null
    val asset = assetProvider(location.timeBucket)?.getOrNull(location.localOffset)
    return if (asset != null) {
        TimelinePagerAnchor.Materialized(
            assetId = asset.id,
            timeBucket = location.timeBucket,
            localOffset = location.localOffset,
            pageIndex = page
        )
    } else {
        TimelinePagerAnchor.Unresolved(
            timeBucket = location.timeBucket,
            localOffset = location.localOffset,
            pageIndex = page
        )
    }
}

internal fun reconcileTimelinePagerAnchor(
    anchor: TimelinePagerAnchor,
    state: TimelineOverlayState,
    assetProvider: (String) -> List<Asset>?
): TimelinePagerAnchorResolution {
    if (state.pageIndex.totalPages <= 0) return TimelinePagerAnchorResolution.Empty

    if (anchor is TimelinePagerAnchor.Materialized) {
        state.pageIndex.loadedAssetPageIndexes[anchor.assetId]?.let { page ->
            return when (val captured = captureTimelinePagerAnchor(page, state, assetProvider)) {
                is TimelinePagerAnchor.Materialized ->
                    TimelinePagerAnchorResolution.Found(page, captured)

                is TimelinePagerAnchor.Unresolved ->
                    pendingTimelinePagerResolution(page, state)

                null -> TimelinePagerAnchorResolution.Empty
            }
        }
    }

    val originalBucketIndex = state.buckets.indexOfFirst { it.timeBucket == anchor.timeBucket }
    if (originalBucketIndex >= 0) {
        val originalBucket = state.buckets[originalBucketIndex]
        val assets = assetProvider(originalBucket.timeBucket)
            ?: return pendingTimelinePagerResolution(
                page = state.pageIndex.bucketStartPages
                    .getOrElse(originalBucketIndex) { anchor.pageIndex }
                    .plus(anchor.localOffset)
                    .coerceIn(0, state.pageIndex.totalPages - 1),
                state = state
            )
        if (assets.isNotEmpty()) {
            // Prefer the successor that shifted into the removed asset's local
            // slot; at the end of a bucket this naturally falls back to the
            // preceding asset.
            val localOffset = anchor.localOffset.coerceAtMost(assets.lastIndex)
            val page = state.pageIndex.bucketStartPages.getOrElse(originalBucketIndex) { 0 } +
                    localOffset
            val replacement = assets[localOffset]
            return TimelinePagerAnchorResolution.Found(
                pageIndex = page,
                anchor = TimelinePagerAnchor.Materialized(
                    assetId = replacement.id,
                    timeBucket = originalBucket.timeBucket,
                    localOffset = localOffset,
                    pageIndex = page
                )
            )
        }
    }

    val nearestLoaded = state.pageIndex.loadedAssetPageIndexes
        .entries
        .minWithOrNull(
            compareBy<Map.Entry<String, Int>>(
                { abs(it.value - anchor.pageIndex) },
                { if (it.value >= anchor.pageIndex) 0 else 1 },
                { it.value }
            )
        )
    if (nearestLoaded != null) {
        val replacement = captureTimelinePagerAnchor(nearestLoaded.value, state, assetProvider)
        if (replacement is TimelinePagerAnchor.Materialized) {
            return TimelinePagerAnchorResolution.Found(nearestLoaded.value, replacement)
        }
    }

    val pendingPage = anchor.pageIndex.coerceIn(0, state.pageIndex.totalPages - 1)
    return pendingTimelinePagerResolution(pendingPage, state)
}

internal fun TimelinePagerAnchor.materializedAssetId(): String? =
    (this as? TimelinePagerAnchor.Materialized)?.assetId

private data class TimelinePagerPageLocation(
    val timeBucket: String,
    val localOffset: Int
)

private fun timelinePagerPageLocation(
    page: Int,
    state: TimelineOverlayState
): TimelinePagerPageLocation? {
    val bucketIndex = state.pageIndex.pageToBucketIndex(page) ?: return null
    val bucket = state.buckets.getOrNull(bucketIndex) ?: return null
    val bucketStart = state.pageIndex.bucketStartPages.getOrNull(bucketIndex) ?: return null
    return TimelinePagerPageLocation(
        timeBucket = bucket.timeBucket,
        localOffset = page - bucketStart
    )
}

private fun pendingTimelinePagerResolution(
    page: Int,
    state: TimelineOverlayState
): TimelinePagerAnchorResolution {
    val location = timelinePagerPageLocation(page, state)
        ?: return TimelinePagerAnchorResolution.Empty
    return TimelinePagerAnchorResolution.Pending(
        TimelinePagerAnchor.Unresolved(
            timeBucket = location.timeBucket,
            localOffset = location.localOffset,
            pageIndex = page
        )
    )
}
