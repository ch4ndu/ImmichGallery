package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex

internal data class TimelineVisibleScrollItem(
    val displayIndex: Int,
    val itemOffset: Int
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

private data class TimelineBucketScrollAnchor(
    val bucketIndex: Int,
    val itemKey: String?,
    val scrollOffset: Int
)

internal class TimelineScrollAnchorReconciler {
    private var capturedProjection: TimelineDisplayIndex? = null
    private var assetAnchor: TimelineAssetScrollAnchor? = null
    private var bucketAnchor: TimelineBucketScrollAnchor? = null
    private var restoringProjection: TimelineDisplayIndex? = null

    fun requestBucketAnchor(
        projection: TimelineDisplayIndex,
        bucketIndex: Int,
        itemKey: String?,
        scrollOffset: Int = 0
    ) {
        capturedProjection = projection
        assetAnchor = null
        bucketAnchor = TimelineBucketScrollAnchor(bucketIndex, itemKey, scrollOffset)
        restoringProjection = null
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
        if (visibleAsset != null) {
            assetAnchor = visibleAsset
            bucketAnchor = null
        }
    }
}
