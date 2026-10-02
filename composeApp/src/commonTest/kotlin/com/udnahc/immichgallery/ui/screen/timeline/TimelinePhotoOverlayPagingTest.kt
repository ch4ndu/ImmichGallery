package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelinePageIndex
import com.udnahc.immichgallery.domain.model.timelineBucketDisplayLabel
import com.udnahc.immichgallery.ui.component.PhotoOverlayInitialIndexResolution
import com.udnahc.immichgallery.ui.component.SlideshowCandidateStatus
import com.udnahc.immichgallery.domain.model.SlideshowSession
import com.udnahc.immichgallery.domain.model.SlideshowOrder
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TimelinePhotoOverlayPagingTest {
    @Test
    fun initialSelectionDistinguishesFoundPendingAndAbsent() {
        assertEquals(
            PhotoOverlayInitialIndexResolution.Found(4),
            timelineOverlayInitialIndexResolution(index = 4, sourcePending = true)
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Pending,
            timelineOverlayInitialIndexResolution(index = null, sourcePending = true)
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Absent,
            timelineOverlayInitialIndexResolution(index = null, sourcePending = false)
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Pending,
            timelineOverlayInitialIndexResolution(
                index = null,
                sourcePending = timelineOverlaySourcePending(
                    isSourceReady = false,
                    isLoading = false,
                    hasBuckets = false,
                    totalPages = 0
                )
            )
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Absent,
            timelineOverlayInitialIndexResolution(
                index = null,
                sourcePending = timelineOverlaySourcePending(
                    isSourceReady = true,
                    isLoading = false,
                    hasBuckets = false,
                    totalPages = 0
                )
            )
        )
        val freshKey = timelineOverlayInitialIndexKey(TimelineState())
        val hydratedEmptyKey = timelineOverlayInitialIndexKey(
            TimelineState(isSourceReady = true)
        )
        assertEquals(false, freshKey.isSourceReady)
        assertEquals(true, hydratedEmptyKey.isSourceReady)
    }

    @Test
    fun insertedAssetBeforeSettledAssetReanchorsByAssetId() {
        val before = projection(bucket("month", listOf(asset("a"), asset("b"))))
        val anchor = captureTimelinePagerAnchor(1, before.state, before::assetsFor)
        val after = projection(bucket("month", listOf(asset("x"), asset("a"), asset("b"))))

        val resolution = reconcileTimelinePagerAnchor(
            anchor = requireNotNull(anchor),
            state = after.state,
            assetProvider = after::assetsFor
        )

        val found = assertIs<TimelinePagerAnchorResolution.Found>(resolution)
        assertEquals(2, found.pageIndex)
        assertEquals("b", found.anchor.assetId)
    }

    @Test
    fun removedAssetBeforeSettledAssetReanchorsByAssetId() {
        val before = projection(bucket("month", listOf(asset("a"), asset("b"), asset("c"))))
        val anchor = captureTimelinePagerAnchor(2, before.state, before::assetsFor)
        val after = projection(bucket("month", listOf(asset("b"), asset("c"))))

        val found = assertIs<TimelinePagerAnchorResolution.Found>(
            reconcileTimelinePagerAnchor(
                anchor = requireNotNull(anchor),
                state = after.state,
                assetProvider = after::assetsFor
            )
        )

        assertEquals(1, found.pageIndex)
        assertEquals("c", found.anchor.assetId)
    }

    @Test
    fun unresolvedPageIdentitySurvivesBucketInsertion() {
        val before = projection(bucket("pending", assets = null, count = 2))
        val after = projection(
            bucket("inserted", listOf(asset("x"))),
            bucket("pending", assets = null, count = 2)
        )

        assertEquals(
            timelinePagerPageKey(1, before.state, before::assetsFor),
            timelinePagerPageKey(2, after.state, after::assetsFor)
        )
        assertEquals(
            TimelinePagerPageKey.UnresolvedBucketOffset("pending", 1),
            timelinePagerPageKey(2, after.state, after::assetsFor)
        )
    }

    @Test
    fun materializedPageIdentityChangesFromBucketOffsetToAssetId() {
        val unresolved = projection(bucket("month", assets = null, count = 1))
        val materialized = projection(bucket("month", listOf(asset("a"))))

        assertEquals(
            TimelinePagerPageKey.UnresolvedBucketOffset("month", 0),
            timelinePagerPageKey(0, unresolved.state, unresolved::assetsFor)
        )
        assertEquals(
            TimelinePagerPageKey.MaterializedAsset("a"),
            timelinePagerPageKey(0, materialized.state, materialized::assetsFor)
        )

        val unresolvedAnchor = assertIs<TimelinePagerAnchor.Unresolved>(
            captureTimelinePagerAnchor(0, unresolved.state, unresolved::assetsFor)
        )
        val resolution = assertIs<TimelinePagerAnchorResolution.Found>(
            reconcileTimelinePagerAnchor(
                anchor = unresolvedAnchor,
                state = materialized.state,
                assetProvider = materialized::assetsFor
            )
        )
        assertEquals("a", resolution.anchor.assetId)
        assertEquals(0, resolution.pageIndex)
    }

    @Test
    fun pagerKeysUseStableBundleSaveableStringsWithoutIdentityCollisions() {
        assertEquals(
            "asset:a",
            TimelinePagerPageKey.MaterializedAsset("a").toSaveablePagerKey()
        )
        assertEquals(
            "bucket:5:month:0",
            TimelinePagerPageKey.UnresolvedBucketOffset("month", 0).toSaveablePagerKey()
        )
        assertEquals(
            "bucket:7:month:0:1",
            TimelinePagerPageKey.UnresolvedBucketOffset("month:0", 1).toSaveablePagerKey()
        )
    }

    @Test
    fun deletedCurrentAssetUsesSuccessorThenPredecessorAtBucketEnd() {
        val before = projection(bucket("month", listOf(asset("a"), asset("b"), asset("c"))))
        val middleAnchor = requireNotNull(
            captureTimelinePagerAnchor(1, before.state, before::assetsFor)
        )
        val endAnchor = requireNotNull(
            captureTimelinePagerAnchor(2, before.state, before::assetsFor)
        )
        val withoutMiddle = projection(bucket("month", listOf(asset("a"), asset("c"))))
        val withoutEnd = projection(bucket("month", listOf(asset("a"), asset("b"))))

        val middleReplacement = assertIs<TimelinePagerAnchorResolution.Found>(
            reconcileTimelinePagerAnchor(middleAnchor, withoutMiddle.state, withoutMiddle::assetsFor)
        )
        val endReplacement = assertIs<TimelinePagerAnchorResolution.Found>(
            reconcileTimelinePagerAnchor(endAnchor, withoutEnd.state, withoutEnd::assetsFor)
        )

        assertEquals("c", middleReplacement.anchor.assetId)
        assertEquals(1, middleReplacement.pageIndex)
        assertEquals("b", endReplacement.anchor.assetId)
        assertEquals(1, endReplacement.pageIndex)
    }

    @Test
    fun unresolvedOriginalBucketRequestsMaterializationWithoutPageZeroFallback() {
        val before = projection(bucket("month", listOf(asset("a"), asset("b"))))
        val anchor = requireNotNull(
            captureTimelinePagerAnchor(1, before.state, before::assetsFor)
        )
        val unresolved = projection(bucket("month", assets = null, count = 2))

        assertEquals(
            TimelinePagerAnchorResolution.Pending(
                TimelinePagerAnchor.Unresolved(
                    timeBucket = "month",
                    localOffset = 1,
                    pageIndex = 1
                )
            ),
            reconcileTimelinePagerAnchor(anchor, unresolved.state, unresolved::assetsFor)
        )
    }

    @Test
    fun shiftedPendingAnchorUsesUpdatedPageWhenItsBucketIsLaterRemoved() {
        val before = projection(
            bucket("pending", assets = null, count = 1),
            bucket("right", listOf(asset("r")))
        )
        val original = assertIs<TimelinePagerAnchor.Unresolved>(
            captureTimelinePagerAnchor(0, before.state, before::assetsFor)
        )
        val shifted = projection(
            bucket("inserted", listOf(asset("x"), asset("y"))),
            bucket("pending", assets = null, count = 1),
            bucket("right", listOf(asset("r")))
        )
        val pending = assertIs<TimelinePagerAnchorResolution.Pending>(
            reconcileTimelinePagerAnchor(original, shifted.state, shifted::assetsFor)
        )

        assertEquals(2, pending.anchor.pageIndex)
        assertEquals("pending", pending.anchor.timeBucket)

        val removed = projection(
            bucket("inserted", listOf(asset("x"), asset("y"))),
            bucket("right", listOf(asset("r")))
        )
        val nearest = assertIs<TimelinePagerAnchorResolution.Found>(
            reconcileTimelinePagerAnchor(pending.anchor, removed.state, removed::assetsFor)
        )

        assertEquals("r", nearest.anchor.assetId)
        assertEquals(2, nearest.pageIndex)
    }

    @Test
    fun slideshowMaterializationKeepsStableSlotsAndReservedRandomTarget() {
        val before = projection(bucket("first", listOf(asset("a"))), bucket("lazy", null, 2))
        val reads = mutableListOf<String>()
        val source = timelineSlideshowSource(before.state) { reads += it; before.assetsFor(it) }
        assertEquals(listOf("first", "lazy"), reads)
        val session = SlideshowSession(source.map { it.candidate }, order = SlideshowOrder.RANDOM)
        val target = requireNotNull(session.upcoming())
        val after = projection(bucket("first", listOf(asset("a"))), bucket("lazy", listOf(asset("b"), asset("c"))))
        val materialized = timelineSlideshowSource(after.state, after::assetsFor)
        assertEquals(source.map { it.slot }, materialized.map { it.slot })
        assertEquals((0..2).map { timelineFilmstripPageKey(it, before.state) }, (0..2).map { timelineFilmstripPageKey(it, after.state) })
        assertEquals(false, session.reconcileSource(materialized.map { it.candidate }))
        assertTrue(session.isCurrent(target))
        assertTrue(session.candidate(target)?.assetId in listOf("b", "c"))
    }

    @Test
    fun slideshowMissingCacheIsImmediatelyRecoverableOnlyByManualRefresh() {
        assertEquals(SlideshowCandidateStatus.CACHE_UNAVAILABLE,
            timelineSlideshowBucketStatus(false, false, false, true, true))
        assertEquals(SlideshowCandidateStatus.UNRESOLVED,
            timelineSlideshowBucketStatus(false, false, false, true, false))
        assertEquals(SlideshowCandidateStatus.UNRESOLVED,
            timelineSlideshowBucketStatus(false, false, true, true, true))
        assertEquals(SlideshowCandidateStatus.UNRESOLVED,
            timelineSlideshowBucketStatus(false, false, false, false, true))
        assertEquals(SlideshowCandidateStatus.LOADING,
            timelineSlideshowBucketStatus(true, true, false, true, true))
        assertEquals(SlideshowCandidateStatus.FAILED,
            timelineSlideshowBucketStatus(false, true, false, false, true))
    }

    @Test
    fun slideshowSourceKeepsPerBucketFailureAndMaterializedAssetPrecedence() {
        val source = projection(bucket("ready", listOf(asset("a"))), bucket("failed", null, 2))
        val projected = timelineSlideshowSource(source.state.copy(bucketStatuses = mapOf(
            "ready" to SlideshowCandidateStatus.FAILED, "failed" to SlideshowCandidateStatus.FAILED
        )), source::assetsFor)
        assertEquals(listOf(SlideshowCandidateStatus.MATERIALIZED, SlideshowCandidateStatus.FAILED, SlideshowCandidateStatus.FAILED), projected.map { it.status })
        assertEquals(listOf("a", null, null), projected.map { it.asset?.id })
    }

    private data class BucketFixture(
        val timeBucket: String,
        val assets: List<Asset>?,
        val count: Int
    )

    private data class Projection(
        val state: TimelineOverlayState,
        private val assetsByBucket: Map<String, List<Asset>?>
    ) {
        fun assetsFor(timeBucket: String): List<Asset>? = assetsByBucket[timeBucket]
    }

    private fun projection(vararg fixtures: BucketFixture): Projection {
        var start = 0
        val starts = mutableListOf<Int>()
        val counts = mutableListOf<Int>()
        val indexes = mutableMapOf<String, Int>()
        fixtures.forEach { fixture ->
            starts += start
            counts += fixture.count
            fixture.assets?.forEachIndexed { localIndex, asset ->
                indexes[asset.id] = start + localIndex
            }
            start += fixture.count
        }
        return Projection(
            state = TimelineOverlayState(
                pageIndex = TimelinePageIndex(
                    bucketStartPages = starts,
                    bucketPageCounts = counts,
                    totalPages = start,
                    loadedAssetPageIndexes = indexes
                ),
                buckets = fixtures.map { fixture ->
                    TimelineBucket(
                        displayLabel = timelineBucketDisplayLabel(fixture.timeBucket),
                        timeBucket = fixture.timeBucket,
                        count = fixture.count
                    )
                }
            ),
            assetsByBucket = fixtures.associate { it.timeBucket to it.assets }
        )
    }

    private fun bucket(
        timeBucket: String,
        assets: List<Asset>?,
        count: Int = assets?.size ?: 0
    ): BucketFixture = BucketFixture(timeBucket, assets, count)

    private fun asset(id: String): Asset = Asset(
        id = id,
        type = AssetType.IMAGE,
        fileName = "$id.jpg",
        createdAt = "2026-01-01T00:00:00Z",
        thumbnailUrl = "https://example.test/$id/thumbnail",
        originalUrl = "https://example.test/$id/original"
    )
}
