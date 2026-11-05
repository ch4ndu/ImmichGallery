package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.model.SlideshowMotion
import com.udnahc.immichgallery.domain.model.SlideshowTransition
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(DelicateCoilApi::class)
class SlideshowImageHandoffUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun outgoingPoseSurvivesNavigationAndIncomingPoseSurvivesSettling() {
        val directory = File("build/reports/slideshow-image-handoff").apply { mkdirs() }
        val file = File(directory, "source.png")
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        for (y in 0 until 10) for (x in 0 until 10) {
            graphics.color = if ((x + y) % 2 == 0) Color.CYAN else Color.MAGENTA
            graphics.fillRect(x * 20, y * 20, 20, 20)
        }
        graphics.dispose()
        ImageIO.write(image, "png", file)
        val previousLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        SingletonImageLoader.setUnsafe(loader)
        try {
            val assets = listOf("a", "b").map { viewerFixtureAsset(it).copy(originalUrl = file.absolutePath) }
            var now = 0L
            val controller = SlideshowPlaybackController({ now }, { _, _ -> })
            val config = SlideshowConfig(durationSeconds = 5, motion = SlideshowMotion.PAN_AND_ZOOM,
                transition = SlideshowTransition.SLIDE)
            controller.updateSource(staticSlideshowSource(assets))
            controller.start(config, 0)
            val initialRequest = requireNotNull(controller.state.value.request)
            controller.navigationFinished(controller.state.value.navigationId)
            controller.mediaEvent(initialRequest, SlideshowMediaEvent.ImageReady)
            now = 2_000L
            controller.tick()
            var playback by mutableStateOf(controller.state.value)
            var settled by mutableStateOf(0)
            val ready = CopyOnWriteArrayList<String>()
            compose.setContent {
                Row {
                    assets.forEachIndexed { index, asset ->
                        Box(Modifier.size(200.dp).testTag(asset.id)) {
                            SlideshowAssetPage(asset, index == settled, config, {}, "", playback,
                                { playback.motionProgress }, { request, event ->
                                    if (event == SlideshowMediaEvent.ImageReady) ready.add(requireNotNull(request.assetId))
                                })
                        }
                    }
                }
            }
            compose.waitUntil { "a" in ready }
            compose.mainClock.advanceTimeBy(300)
            val outgoingBefore = snapshot("a", directory, "outgoing-before")
            compose.runOnIdle {
                controller.next()
                playback = controller.state.value
            }
            compose.waitUntil { "b" in ready }
            compose.mainClock.advanceTimeBy(300)
            val outgoingAfter = snapshot("a", directory, "outgoing-during-slide")
            assertPixelsEqual(outgoingBefore, outgoingAfter)
            val incomingBefore = snapshot("b", directory, "incoming-before-settle")
            compose.runOnIdle {
                settled = 1
                controller.navigationFinished(controller.state.value.navigationId)
                playback = controller.state.value
            }
            val incomingAfter = snapshot("b", directory, "incoming-after-settle")
            assertPixelsEqual(incomingBefore, incomingAfter)
            assertTrue(ready.containsAll(listOf("a", "b")))
        } finally {
            SingletonImageLoader.setUnsafe(previousLoader)
            loader.shutdown()
        }
    }

    private fun snapshot(tag: String, directory: File, name: String): BufferedImage {
        // Coil's crossfade uses its own clock; wait for full opacity before
        // comparing geometry rather than comparing two different fade frames.
        compose.waitUntil {
            val frame = compose.onNodeWithTag(tag).captureToImage().toAwtImage()
            frame.getRGB(frame.width / 2, frame.height / 2).ushr(24) == 255
        }
        return compose.onNodeWithTag(tag).captureToImage().toAwtImage().also {
            ImageIO.write(it, "png", File(directory, "$name.png"))
        }
    }

    private fun assertPixelsEqual(expected: BufferedImage, actual: BufferedImage) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val before = expected.getRGB(0, 0, expected.width, expected.height, null, 0, expected.width)
        val after = actual.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width)
        assertTrue(before.contentEquals(after), "The still image changed pose during the navigation handoff")
    }
}
