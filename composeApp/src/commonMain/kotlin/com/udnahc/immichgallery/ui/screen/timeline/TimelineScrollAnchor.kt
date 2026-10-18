package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex

internal data class TimelineVisibleScrollItem(
    val displayIndex: Int,
    val itemOffset: Int,
    // Measured lazy-list key; avoids resolving an index against a projection the layout has not caught up with.
    val key: String? = null
)

// Pixel conversion matching LazyColumn: each item rounds its own dp height, plus rounded spacing.
internal data class TimelineAnchorMetrics(
    val heightPx: (Float) -> Int,
    val spacingPx: Int,
    val viewportPx: Int
) {
    companion object {
        val Default = TimelineAnchorMetrics(heightPx = { kotlin.math.round(it).toInt() }, spacingPx = 0, viewportPx = Int.MAX_VALUE)
    }
}

internal data class TimelineScrollRestore(
    val displayIndex: Int?,
    val scrollOffset: Int
)

private data class TimelineAssetScrollAnchor(
    val assetId: String,
    val scrollOffset: Int,
    val fallbackBucketIndex: Int?,
    val fallbackItemKey: String?
)

private sealed interface TimelineViewportScrollAnchor {
    data class Keyed(val gridKey: String, val scrollOffset: Int) : TimelineViewportScrollAnchor

    // Estimated position inside a bucket's ordered assets; used only when no stable item is near.
    data class Ordinal(val bucketIdentity: String, val ordinal: Double) : TimelineViewportScrollAnchor
}

private data class TimelineBucketScrollAnchor(
    val bucketIndex: Int,
    val itemKey: String?,
    val scrollOffset: Int
)

internal class TimelineScrollAnchorReconciler {
    private var capturedProjection: TimelineDisplayIndex? = null
    private var assetAnchor: TimelineAssetScrollAnchor? = null
    private var viewportAnchor: TimelineViewportScrollAnchor? = null
    private var bucketAnchor: TimelineBucketScrollAnchor? = null
    private var restoringProjection: TimelineDisplayIndex? = null
    private var requestedAssetId: String? = null

    fun cancelRequestedAnchor() {
        capturedProjection = null
        assetAnchor = null
        viewportAnchor = null
        bucketAnchor = null
        restoringProjection = null
        requestedAssetId = null
    }

    fun requestAssetAnchor(
        projection: TimelineDisplayIndex,
        assetId: String,
        bucketIndex: Int,
        scrollOffset: Int
    ) {
        capturedProjection = projection
        assetAnchor = TimelineAssetScrollAnchor(assetId, scrollOffset, bucketIndex, null)
        viewportAnchor = null
        bucketAnchor = null
        restoringProjection = null
        requestedAssetId = assetId
    }

    fun requestBucketAnchor(
        projection: TimelineDisplayIndex,
        bucketIndex: Int,
        itemKey: String?,
        scrollOffset: Int = 0
    ) {
        capturedProjection = projection
        assetAnchor = null
        viewportAnchor = null
        bucketAnchor = TimelineBucketScrollAnchor(bucketIndex, itemKey, scrollOffset)
        restoringProjection = null
        requestedAssetId = null
    }

    fun observe(
        projection: TimelineDisplayIndex,
        visibleItems: List<TimelineVisibleScrollItem>,
        isScrollInProgress: Boolean,
        metrics: TimelineAnchorMetrics = TimelineAnchorMetrics.Default
    ): TimelineScrollRestore? {
        val captured = capturedProjection
        if (captured == null) {
            capturedProjection = projection
            captureCurrentAnchor(projection, visibleItems, metrics)
            return null
        }

        if (projection != captured) {
            if (isScrollInProgress || restoringProjection == projection) return null
            restoringProjection = projection
            return resolveRestore(projection, metrics)
        }

        if (restoringProjection != null) return null
        requestedAssetId?.let { assetId ->
            val requestedIndex = projection.assetDisplayIndexById[assetId]
            if (visibleItems.none { it.displayIndex == requestedIndex }) return null
            requestedAssetId = null
        }
        val pendingBucket = bucketAnchor
        if (pendingBucket != null) {
            val firstBucket = visibleItems.firstOrNull()?.displayIndex
                ?.let { projection.bucketByDisplayIndex.getOrNull(it) }
            if (firstBucket != pendingBucket.bucketIndex) return null
        }
        captureCurrentAnchor(projection, visibleItems, metrics)
        return null
    }

    fun commitRestore(
        projection: TimelineDisplayIndex,
        visibleItems: List<TimelineVisibleScrollItem>,
        metrics: TimelineAnchorMetrics = TimelineAnchorMetrics.Default
    ) {
        if (restoringProjection != projection) return
        capturedProjection = projection
        restoringProjection = null
        captureCurrentAnchor(projection, visibleItems, metrics)
    }

    internal fun anchoredAssetIdForTests(): String? = assetAnchor?.assetId

    internal fun isRestoringForTests(): Boolean = restoringProjection != null

    private fun resolveRestore(
        projection: TimelineDisplayIndex,
        metrics: TimelineAnchorMetrics
    ): TimelineScrollRestore {
        val navigationPending = requestedAssetId != null || bucketAnchor != null
        val viewport = viewportAnchor.takeIf { !navigationPending }
        if (viewport is TimelineViewportScrollAnchor.Keyed) {
            projection.displayIndexByGridKey[viewport.gridKey]?.let { index ->
                return TimelineScrollRestore(index, viewport.scrollOffset)
            }
        }
        if (viewport is TimelineViewportScrollAnchor.Ordinal) {
            resolveOrdinalRestore(projection, viewport, metrics)?.let { return it }
        }
        assetAnchor?.let { anchor ->
            val exactIndex = projection.assetDisplayIndexById[anchor.assetId]
            if (exactIndex != null) {
                return TimelineScrollRestore(exactIndex, anchor.scrollOffset)
            }
            val keyedFallback = anchor.fallbackItemKey
                ?.let(projection.displayIndexByGridKey::get)
            val bucketFallback = anchor.fallbackBucketIndex
                ?.let { projection.displayIndexesByBucket[it]?.firstOrNull() }
            return TimelineScrollRestore(
                displayIndex = keyedFallback ?: bucketFallback,
                scrollOffset = anchor.scrollOffset
            )
        }

        bucketAnchor?.let { anchor ->
            return TimelineScrollRestore(
                displayIndex = anchor.itemKey?.let(projection.displayIndexByGridKey::get)
                    ?: projection.displayIndexesByBucket[anchor.bucketIndex]?.firstOrNull(),
                scrollOffset = anchor.scrollOffset
            )
        }
        return TimelineScrollRestore(displayIndex = null, scrollOffset = 0)
    }

    private fun resolveOrdinalRestore(
        projection: TimelineDisplayIndex,
        anchor: TimelineViewportScrollAnchor.Ordinal,
        metrics: TimelineAnchorMetrics
    ): TimelineScrollRestore? {
        val bucketIndex = projection.bucketIdentityByIndex.indexOf(anchor.bucketIdentity).takeIf { it >= 0 }
            ?: return null
        val target = projection.displayIndexesByBucket[bucketIndex]?.firstOrNull { index ->
            val start = projection.bucketOrdinalStartByDisplayIndex.getOrNull(index) ?: -1
            val count = projection.bucketOrdinalCountByDisplayIndex.getOrNull(index) ?: 0
            start >= 0 && count > 0 && anchor.ordinal >= start && anchor.ordinal < start + count
        } ?: return null
        val start = projection.bucketOrdinalStartByDisplayIndex[target]
        val count = projection.bucketOrdinalCountByDisplayIndex[target]
        val heightPx = metrics.heightPx(projection.heightDpByDisplayIndex.getOrElse(target) { 0f })
        val offset = (((anchor.ordinal - start) / count) * heightPx).toInt().coerceIn(0, heightPx.coerceAtLeast(0))
        return TimelineScrollRestore(target, offset)
    }

    private fun captureViewportAnchor(
        projection: TimelineDisplayIndex,
        visibleItems: List<TimelineVisibleScrollItem>,
        metrics: TimelineAnchorMetrics
    ): TimelineViewportScrollAnchor? {
        val resolved = visibleItems.mapNotNull { visible ->
            val index = visible.key?.let(projection.displayIndexByGridKey::get) ?: visible.displayIndex
            projection.gridKeyByDisplayIndex.getOrNull(index)?.let { key -> Triple(visible, index, key) }
        }
        val topmostStable = resolved.firstOrNull { (_, index, _) -> projection.isStableAnchor(index) }
        if (topmostStable != null) {
            val (visible, _, key) = topmostStable
            return TimelineViewportScrollAnchor.Keyed(key, -visible.itemOffset)
        }
        val (first, firstIndex, _) = resolved.firstOrNull() ?: return null
        val scrollIn = -first.itemOffset
        val bucket = projection.bucketByDisplayIndex.getOrNull(firstIndex)
        var distance = scrollIn
        var index = firstIndex - 1
        var stableIndex: Int? = null
        while (index >= 0 && projection.bucketByDisplayIndex.getOrNull(index) == bucket) {
            distance += metrics.heightPx(projection.heightDpByDisplayIndex.getOrElse(index) { 0f }) + metrics.spacingPx
            if (projection.isStableAnchor(index)) {
                stableIndex = index
                break
            }
            index--
        }
        val withinDistanceCap = metrics.viewportPx == Int.MAX_VALUE ||
                distance.toLong() <= metrics.viewportPx.toLong() * TIMELINE_ANCHOR_DISTANCE_CAP_SCREENS
        if (stableIndex != null && withinDistanceCap) {
            return TimelineViewportScrollAnchor.Keyed(projection.gridKeyByDisplayIndex[stableIndex], distance)
        }
        val ordinalStart = projection.bucketOrdinalStartByDisplayIndex.getOrNull(firstIndex) ?: -1
        val ordinalCount = projection.bucketOrdinalCountByDisplayIndex.getOrNull(firstIndex) ?: 0
        val identity = bucket?.let(projection.bucketIdentityByIndex::getOrNull)
        val firstHeightPx = metrics.heightPx(projection.heightDpByDisplayIndex.getOrElse(firstIndex) { 0f })
        if (ordinalStart >= 0 && ordinalCount > 0 && identity != null && firstHeightPx > 0) {
            val fraction = scrollIn.coerceIn(0, firstHeightPx).toDouble() / firstHeightPx
            return TimelineViewportScrollAnchor.Ordinal(identity, ordinalStart + fraction * ordinalCount)
        }
        return stableIndex?.let { TimelineViewportScrollAnchor.Keyed(projection.gridKeyByDisplayIndex[it], distance) }
    }

    private fun TimelineDisplayIndex.isStableAnchor(index: Int): Boolean =
        stableAnchorByDisplayIndex.getOrNull(index) ?: true

    private fun captureCurrentAnchor(
        projection: TimelineDisplayIndex,
        visibleItems: List<TimelineVisibleScrollItem>,
        metrics: TimelineAnchorMetrics
    ) {
        if (visibleItems.isEmpty()) return
        viewportAnchor = captureViewportAnchor(projection, visibleItems, metrics)
        val visibleAsset = visibleItems.firstNotNullOfOrNull { visible ->
            projection.firstAssetIdByDisplayIndex.getOrNull(visible.displayIndex)
                ?.let { assetId ->
                    TimelineAssetScrollAnchor(
                        assetId = assetId,
                        scrollOffset = -visible.itemOffset,
                        fallbackBucketIndex = projection.bucketByDisplayIndex
                            .getOrNull(visible.displayIndex),
                        fallbackItemKey = projection.bucketByDisplayIndex
                            .getOrNull(visible.displayIndex)
                            ?.let(projection.displayIndexesByBucket::get)
                            ?.firstOrNull()
                            ?.let(projection.gridKeyByDisplayIndex::getOrNull)
                    )
                }
        }
        // A placeholder-only viewport must not keep an asset anchor captured further up the list.
        assetAnchor = visibleAsset
        if (visibleAsset != null) {
            bucketAnchor = null
        }
    }
}

private const val TIMELINE_ANCHOR_DISTANCE_CAP_SCREENS = 3
