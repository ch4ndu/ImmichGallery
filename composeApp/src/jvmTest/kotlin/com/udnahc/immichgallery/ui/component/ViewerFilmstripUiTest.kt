package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.theme.Dimens
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerFilmstripUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val files = TemporaryFolder()

    private var currentPage by mutableStateOf(0)
    private var totalPages by mutableStateOf(1_000)
    private var materializedAssets by mutableStateOf<Map<Int, Asset>>(emptyMap())
    private var appActive by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private var visibleCallback by mutableStateOf<((List<Int>) -> Unit)?>(null)
    private val resolvedPages = mutableSetOf<Int>()
    private val selectedPages = mutableListOf<Int>()
    private val visibleDemand = mutableListOf<List<Int>>()

    @Test
    fun controlsExpandAndCollapseTheStripWhilePreservingOneTruthfulPosition() {
        var expanded by mutableStateOf(false)
        currentPage = 1
        totalPages = 4
        compose.mainClock.autoAdvance = true
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    ViewerFilmstripControls(
                        totalPages = totalPages,
                        currentPage = currentPage,
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    )
                    if (expanded) {
                        ViewerFilmstrip(
                            totalPages = totalPages,
                            currentPage = currentPage,
                            pageKey = { "slot-$it" },
                            assetForPage = { null },
                            onPageSelected = { currentPage = it },
                            modifier = Modifier.testTag(FILMSTRIP_TAG)
                        )
                    }
                }
            }
        }

        compose.onAllNodesWithText("Asset 2 of 4").assertCountEquals(1)
        compose.onNodeWithTag(FILMSTRIP_TAG).assertDoesNotExist()
        compose.onNodeWithText("Show thumbnails").performClick()
        compose.onNodeWithTag(FILMSTRIP_TAG).assertIsDisplayed()
        compose.onNodeWithText("Hide thumbnails").assertIsDisplayed()
        compose.onNodeWithContentDescription(placeholderDescription(1, 4)).assertIsSelected()
        compose.onAllNodesWithText("Asset 2 of 4").assertCountEquals(1)

        compose.onNodeWithText("Hide thumbnails").performClick()
        compose.onNodeWithTag(FILMSTRIP_TAG).assertDoesNotExist()
        compose.onAllNodesWithText("Asset 2 of 4").assertCountEquals(1)
        compose.onNodeWithText("Show thumbnails").assertIsDisplayed()
    }

    @Test
    fun memoriesCanSuppressExtraPositionTextWithoutLosingTheToggle() {
        var expanded by mutableStateOf(false)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ViewerFilmstripControls(
                        totalPages = 4,
                        currentPage = 1,
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        showPosition = false
                    )
                }
            }
        }

        compose.onNodeWithText("Asset 2 of 4").assertDoesNotExist()
        compose.onNodeWithText("Show thumbnails").performClick()
        compose.onNodeWithText("Hide thumbnails").assertIsDisplayed()
        compose.onNodeWithText("Asset 2 of 4").assertDoesNotExist()
        compose.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun placeholderClickSelectsItsPageAndPagerChangesRevealSelectionAfterSourceShrink() {
        showStrip()
        waitForVisiblePage(2)
        compose.onNodeWithContentDescription(placeholderDescription(0)).assertIsSelected()
        compose.onNodeWithContentDescription(placeholderDescription(2)).performClick()
        compose.onNodeWithContentDescription(placeholderDescription(2)).assertIsSelected()
        compose.onNodeWithContentDescription(placeholderDescription(0)).assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(listOf(2), selectedPages)
            assertEquals(2, currentPage)
            currentPage = 999
        }

        waitForVisiblePage(999)
        compose.onNodeWithContentDescription(placeholderDescription(999))
            .assertIsDisplayed().assertIsSelected()
        compose.runOnIdle { totalPages = 3 }
        waitForVisiblePage(2)
        compose.onNodeWithContentDescription(placeholderDescription(2, 3))
            .assertIsDisplayed().assertIsSelected()
        compose.runOnIdle {
            assertTrue(visibleDemand.last().all { it in 0..2 })
            totalPages = 0
        }
        compose.onNodeWithTag(FILMSTRIP_TAG).assertDoesNotExist()
        compose.runOnIdle { assertTrue(visibleDemand.last().isEmpty()) }
    }

    @Test
    fun thousandSlotStripResolvesAndDemandsOnlyItsVisibleViewport() {
        showStrip()
        waitForVisiblePage(0)
        compose.waitForIdle()
        val initialPages = compose.runOnIdle {
            assertTrue(resolvedPages.size in 1..6, "320dp viewport unexpectedly resolved $resolvedPages")
            assertTrue(visibleDemand.filter { it.isNotEmpty() }.all { it.size <= 6 })
            assertTrue(resolvedPages.all { page -> visibleDemand.any { page in it } })
            resolvedPages.toSet()
        }

        compose.onNodeWithTag(FILMSTRIP_TAG).performScrollToIndex(700)
        waitForVisiblePage(700)
        compose.waitForIdle()
        compose.runOnIdle {
            val finalVisible = visibleDemand.last().toSet()
            assertTrue(finalVisible.size in 1..6)
            assertTrue(700 in finalVisible)
            assertTrue(resolvedPages.all { it in initialPages || it in finalVisible })
            assertTrue(resolvedPages.size <= 12)
            assertEquals(0, currentPage, "Browsing the strip must not change the pager selection")
            assertTrue(selectedPages.isEmpty())
        }
    }

    @Test
    fun materializingASelectedSlotPreservesItsSelectionAndViewportDemand() {
        val fixture = syntheticImage()
        currentPage = 3
        showStrip()
        waitForVisiblePage(3)
        compose.onNodeWithContentDescription(placeholderDescription(3)).assertIsSelected()
        compose.runOnIdle { materializedAssets = mapOf(3 to fixture) }

        compose.onNodeWithContentDescription("Asset 4 of 1000").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription(placeholderDescription(3)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(3, currentPage)
            assertTrue(3 in visibleDemand.last())
            assertTrue(selectedPages.isEmpty())
        }
    }

    @Test
    fun backgroundAndDisposalReleaseDemandThroughTheLatestCallback() {
        val latestDemand = mutableListOf<List<Int>>()
        showStrip()
        waitForVisiblePage(0)
        compose.runOnIdle {
            visibleCallback = { latestDemand.add(it) }
            appActive = false
        }
        compose.waitUntil { latestDemand.isNotEmpty() }
        compose.runOnIdle {
            assertTrue(latestDemand.last().isEmpty())
            assertFalse(visibleDemand.last().isEmpty())
            appActive = true
        }
        compose.waitUntil { latestDemand.lastOrNull()?.isNotEmpty() == true }
        compose.runOnIdle { mounted = false }
        compose.onNodeWithTag(FILMSTRIP_TAG).assertDoesNotExist()
        compose.runOnIdle { assertTrue(latestDemand.last().isEmpty()) }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun overlayForwardsMeasuredFooterHeightToVideoPlayerAndClearsItWhenChromeHides() {
        val fixture = ViewerOverlayUiFixture()
        val assets = listOf(
            viewerFixtureAsset("v1", AssetType.VIDEO),
            viewerFixtureAsset("i2", AssetType.IMAGE),
        )
        try {
            compose.mainClock.autoAdvance = true
            compose.setContent {
                fixture.Content {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        StaticPhotoOverlay(
                            assets = assets,
                            initialIndex = 0,
                            apiKey = "",
                            getAssetDetail = { Result.success(viewerFixtureDetail(it)) },
                            getAssetStack = { Result.success(AssetStackLookup.NoStack) },
                            onPersonClick = { _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }

            compose.waitUntil {
                (fixture.presentations["v1"]?.bottomChromePadding ?: 0.dp) > 0.dp
            }
            val collapsedPadding = compose.runOnIdle {
                val padding = requireNotNull(fixture.presentations["v1"]).bottomChromePadding
                assertTrue(padding > 0.dp, "Expected positive collapsed footer padding, got $padding")
                padding
            }

            compose.onNodeWithText("Show thumbnails").performClick()
            compose.waitUntil {
                (fixture.presentations["v1"]?.bottomChromePadding ?: 0.dp) > collapsedPadding
            }
            compose.runOnIdle {
                val expandedPadding = requireNotNull(fixture.presentations["v1"]).bottomChromePadding
                assertEquals(
                    collapsedPadding + Dimens.viewerFilmstripHeight,
                    expandedPadding,
                )
            }

            compose.onNodeWithTag("media:v1:selected").performClick()
            compose.waitUntil {
                fixture.presentations["v1"]?.bottomChromePadding == 0.dp
            }
            compose.runOnIdle {
                assertEquals(0.dp, requireNotNull(fixture.presentations["v1"]).bottomChromePadding)
            }
        } finally {
            fixture.dispose()
        }
    }

    private fun showStrip() {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppActive provides appActive) {
                    Box(Modifier.width(320.dp).height(96.dp)) {
                        if (mounted) {
                            ViewerFilmstrip(
                                totalPages = totalPages,
                                currentPage = currentPage,
                                pageKey = { "slot-$it" },
                                assetForPage = { page ->
                                    resolvedPages.add(page)
                                    materializedAssets[page]
                                },
                                onPageSelected = { page ->
                                    selectedPages.add(page)
                                    currentPage = page
                                },
                                modifier = Modifier.testTag(FILMSTRIP_TAG),
                                onVisiblePagesChanged = visibleCallback ?: { pages ->
                                    visibleDemand.add(pages)
                                    Unit
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun waitForVisiblePage(page: Int) {
        compose.waitUntil { visibleDemand.lastOrNull()?.contains(page) == true }
    }

    private fun placeholderDescription(page: Int, count: Int = 1_000) =
        "Asset ${page + 1} of $count. Preview not loaded."

    private fun syntheticImage(): Asset {
        val file = files.newFile("filmstrip.png")
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFFF0000.toInt())
        image.setRGB(1, 0, 0xFF00FF00.toInt())
        image.setRGB(0, 1, 0xFF0000FF.toInt())
        image.setRGB(1, 1, 0xFFFFFFFF.toInt())
        assertTrue(ImageIO.write(image, "png", file))
        return Asset(
            id = "synthetic-slot-3",
            type = AssetType.IMAGE,
            fileName = "filmstrip.png",
            createdAt = "2024-06-15",
            thumbnailUrl = file.toURI().toString(),
            originalUrl = file.toURI().toString()
        )
    }

    private companion object {
        const val FILMSTRIP_TAG = "viewer-filmstrip"
    }
}
