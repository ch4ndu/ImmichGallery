package com.udnahc.immichgallery.ui.component

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StaticPhotoOverlayTest {
    @Test
    fun completeDotRowUsesMeasuredPixelWidthAtMdpi() {
        val density = Density(1f)

        assertTrue(allPageDotsFit(totalPages = 20, availableWidthPx = 320, density = density))
        assertTrue(allPageDotsFit(totalPages = 22, availableWidthPx = 320, density = density))
        assertFalse(allPageDotsFit(totalPages = 23, availableWidthPx = 320, density = density))
    }

    @Test
    fun completeDotRowRoundsEachElementAtFractionalDensity() {
        val density = Density(1.25f)

        assertTrue(allPageDotsFit(totalPages = 20, availableWidthPx = 400, density = density))
        assertFalse(allPageDotsFit(totalPages = 22, availableWidthPx = 400, density = density))
        assertFalse(allPageDotsFit(totalPages = 22, availableWidthPx = 410, density = density))
        assertTrue(allPageDotsFit(totalPages = 22, availableWidthPx = 411, density = density))
    }

    @Test
    fun dotWidthDecisionRejectsInvalidBounds() {
        val density = Density(1f)

        assertFalse(allPageDotsFit(totalPages = 0, availableWidthPx = 320, density = density))
        assertFalse(allPageDotsFit(totalPages = -1, availableWidthPx = 320, density = density))
        assertFalse(allPageDotsFit(totalPages = 20, availableWidthPx = 0, density = density))
        assertFalse(allPageDotsFit(totalPages = 20, availableWidthPx = -1, density = density))
    }

    @Test
    fun middlePageDragTransfersDotActivenessContinuously() {
        val forwardPosition = pageIndicatorPosition(
            currentPage = 9,
            totalPages = 20,
            currentPageOffsetFraction = 0.25f
        )
        assertEquals(9.25f, forwardPosition, absoluteTolerance = 0.0001f)
        assertEquals(0.75f, pageDotActiveness(9, 20, forwardPosition), absoluteTolerance = 0.0001f)
        assertEquals(0.25f, pageDotActiveness(10, 20, forwardPosition), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageDotActiveness(8, 20, forwardPosition), absoluteTolerance = 0.0001f)

        val crossedPosition = pageIndicatorPosition(
            currentPage = 10,
            totalPages = 20,
            currentPageOffsetFraction = -0.25f
        )
        assertEquals(9.75f, crossedPosition, absoluteTolerance = 0.0001f)
        assertEquals(0.25f, pageDotActiveness(9, 20, crossedPosition), absoluteTolerance = 0.0001f)
        assertEquals(0.75f, pageDotActiveness(10, 20, crossedPosition), absoluteTolerance = 0.0001f)
    }

    @Test
    fun segmentedProgressTracksFractionalMiddlePosition() {
        assertEquals(0.5125f, pageIndicatorProgress(20, 9.25f), absoluteTolerance = 0.0001f)
        assertEquals(0.5375f, pageIndicatorProgress(20, 9.75f), absoluteTolerance = 0.0001f)
        assertEquals(0.05f, pageIndicatorProgress(20, 0f), absoluteTolerance = 0.0001f)
        assertEquals(1f, pageIndicatorProgress(20, 19f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun fractionalIndicatorHelpersClampInvalidBounds() {
        assertEquals(0f, pageIndicatorPosition(-1, 20, 0f), absoluteTolerance = 0.0001f)
        assertEquals(19f, pageIndicatorPosition(20, 20, 0f), absoluteTolerance = 0.0001f)
        assertEquals(10f, pageIndicatorPosition(10, 20, Float.NaN), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageIndicatorPosition(10, 0, 0.25f), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageDotActiveness(-1, 20, 0f), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageDotActiveness(0, 0, 0f), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageDotActiveness(0, 20, Float.NaN), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageIndicatorProgress(0, 0f), absoluteTolerance = 0.0001f)
        assertEquals(0f, pageIndicatorProgress(20, Float.NaN), absoluteTolerance = 0.0001f)
        assertEquals(0.05f, pageIndicatorProgress(20, -1f), absoluteTolerance = 0.0001f)
        assertEquals(1f, pageIndicatorProgress(20, 20f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun staticOverlayInitialIndexDistinguishesPendingAbsentAndFound() {
        assertEquals(
            PhotoOverlayInitialIndexResolution.Pending,
            resolveStaticPhotoOverlayInitialIndex(
                source = StaticPhotoOverlaySourceIdentity(
                    ownerKey = "source",
                    assetIds = emptyList(),
                    isPending = true
                ),
                assetId = "a"
            )
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Absent,
            resolveStaticPhotoOverlayInitialIndex(
                source = StaticPhotoOverlaySourceIdentity(
                    ownerKey = "source",
                    assetIds = listOf("b"),
                    isPending = false
                ),
                assetId = "a"
            )
        )
        val found = resolveStaticPhotoOverlayInitialIndex(
            source = StaticPhotoOverlaySourceIdentity(
                ownerKey = "source",
                assetIds = listOf("a", "b"),
                isPending = true
            ),
            assetId = "b"
        )
        assertIs<PhotoOverlayInitialIndexResolution.Found>(found)
        assertEquals(1, found.index)
    }

    @Test
    fun viewerPrefetchTargetsOnlySettledImageNeighbors() {
        val assets = listOf(
            asset("left", AssetType.IMAGE),
            asset("center-video", AssetType.VIDEO),
            asset("right", AssetType.IMAGE),
            asset("outside", AssetType.IMAGE)
        )

        assertEquals(
            listOf(
                ViewerImagePrefetchTarget(
                    "left",
                    "https://example.test/left/original",
                    width = 1440,
                    height = 900
                ),
                ViewerImagePrefetchTarget(
                    "right",
                    "https://example.test/right/original",
                    width = 1440,
                    height = 900
                )
            ),
            viewerImagePrefetchTargets(
                assets = assets,
                settledPage = 1,
                viewportSize = IntSize(1440, 900)
            )
        )
        assertEquals(
            emptyList(),
            viewerImagePrefetchTargets(assets, settledPage = 1, viewportSize = IntSize.Zero)
        )
    }

    @Test
    fun viewerPrefetchControllerDeduplicatesAndCancelsDisplacedAndDisposedWork() {
        val enqueued = mutableListOf<ViewerImagePrefetchTarget>()
        val disposed = mutableListOf<ViewerImagePrefetchTarget>()
        val controller = ViewerImagePrefetchController { target ->
            enqueued += target
            ViewerImagePrefetchHandle { disposed += target }
        }
        val left = target("left")
        val center = target("center")
        val right = target("right")

        controller.update(listOf(left, center, center))
        controller.update(listOf(center, right))

        assertEquals(listOf(left, center, right), enqueued)
        assertEquals(listOf(left), disposed)
        assertEquals(setOf(center, right), controller.activeTargetsForTests())

        controller.dispose()

        assertEquals(listOf(left, center, right), disposed)
        assertEquals(emptySet(), controller.activeTargetsForTests())
    }

    private fun asset(id: String, type: AssetType): Asset = Asset(
        id = id,
        type = type,
        fileName = "$id.jpg",
        createdAt = "2026-08-09T12:00:00Z",
        thumbnailUrl = "https://example.test/$id/thumb",
        originalUrl = "https://example.test/$id/original",
        videoPlaybackUrl = "https://example.test/$id/video"
    )

    @Test
    fun slideshowUpcomingPrefetchAddsOnlyTheActualTargetAndDeduplicatesNeighbors() {
        val assets = (0..9).map { asset("$it", AssetType.IMAGE) }
        assertEquals(listOf("3", "4", "5", "9"), viewerImagePrefetchAssets(assets, 4, assets[9]).map { it.id })
        assertEquals(listOf("3", "4", "5"), viewerImagePrefetchAssets(assets, 4, assets[5]).map { it.id })
        assertEquals(4, viewerImagePrefetchTargets(assets, 4, IntSize(100, 200), assets[9]).size)
    }

    private fun target(id: String): ViewerImagePrefetchTarget = ViewerImagePrefetchTarget(
        assetId = id,
        url = "https://example.test/$id/original",
        width = 1440,
        height = 900
    )
}
