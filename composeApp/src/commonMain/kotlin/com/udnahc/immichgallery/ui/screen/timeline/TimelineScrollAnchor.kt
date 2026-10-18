package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex

internal data class TimelineVisibleScrollItem(
    val displayIndex: Int,
    val itemOffset: Int,
    // Measured lazy-list key; avoids resolving an index against a projection the layout has not caught up with.
    val key: String? = null
)

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

private data class TimelineViewportScrollAnchor(
    val gridKey: String,
    val scrollOffset: Int
)

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
        isScrollInProgress: Boolean
    ): TimelineScrollRestore? {
        val captured = capturedProjection
        if (captured == null) {
            capturedProjection = projection
            captureCurrentAnchor(projection, visibleItems)
            return null
        }

        if (projection != captured) {
            if (isScrollInProgress || restoringProjection == projection) return null
            restoringProjection = projection
            return resolveRestore(projection)
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
        captureCurrentAnchor(projection, visibleItems)
        return null
    }

    fun commitRestore(
        projection: TimelineDisplayIndex,
        visibleItems: List<TimelineVisibleScrollItem>
    ) {
        if (restoringProjection != projection) return
        capturedProjection = projection
        restoringProjection = null
        captureCurrentAnchor(projection, visibleItems)
    }

    internal fun anchoredAssetIdForTests(): String? = assetAnchor?.assetId

    internal fun isRestoringForTests(): Boolean = restoringProjection != null

    private fun resolveRestore(projection: TimelineDisplayIndex): TimelineScrollRestore {
        val navigationPending = requestedAssetId != null || bucketAnchor != null
        if (!navigationPending) {
            viewportAnchor?.let { anchor ->
                projection.displayIndexByGridKey[anchor.gridKey]?.let { index ->
                    return TimelineScrollRestore(index, anchor.scrollOffset)
                }
            }
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

    private fun captureCurrentAnchor(
        projection: TimelineDisplayIndex,
        visibleItems: List<TimelineVisibleScrollItem>
    ) {
        val firstVisible = visibleItems.firstOrNull() ?: return
        viewportAnchor = (firstVisible.key ?: projection.gridKeyByDisplayIndex.getOrNull(firstVisible.displayIndex))
            ?.let { gridKey -> TimelineViewportScrollAnchor(gridKey, -firstVisible.itemOffset) }
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
