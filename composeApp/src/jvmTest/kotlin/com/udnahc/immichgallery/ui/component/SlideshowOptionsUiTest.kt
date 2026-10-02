package com.udnahc.immichgallery.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.model.SlideshowMotion
import com.udnahc.immichgallery.domain.model.SlideshowOrder
import com.udnahc.immichgallery.domain.model.SlideshowTransition
import com.udnahc.immichgallery.domain.model.SlideshowVideoPolicy
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlideshowOptionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(SlideshowOptionsState(SlideshowConfig()))
    private var open by mutableStateOf(true)
    private var changeCount = 0
    private var dismissCount = 0
    private val started = mutableListOf<SlideshowConfig>()

    @Test
    fun invalidDurationsShowAnErrorAndDisableStartWhileBothBoundsRemainUsable() {
        show()
        listOf("", "0", "-1", "not a number", "121").forEach { value ->
            replaceField(DURATION_LABEL, value)
            compose.onNodeWithText("Enter a duration from 1 to 120 seconds.")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Start").assertIsNotEnabled()
            compose.runOnIdle { assertTrue(started.isEmpty()) }
        }

        listOf("1", "120").forEach { value ->
            replaceField(DURATION_LABEL, value)
            compose.onNodeWithText("Enter a duration from 1 to 120 seconds.").assertDoesNotExist()
            compose.onNodeWithText("Start").assertIsEnabled()
        }
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle {
            assertEquals(1, started.size)
            assertEquals(120, started.single().durationSeconds)
        }
    }

    @Test
    fun independentChoicesAndVideoOptionsEmitTheSelectedConfigurationExactlyOnce() {
        show()
        select("Random")
        replaceField(DURATION_LABEL, "8")
        select("Still")
        select("Cut")
        compose.onNodeWithText("Still").assertIsSelected()
        select("Pan and zoom")
        compose.onNodeWithText("Cut").assertIsSelected()
        select("Fade")
        compose.onNodeWithText("Pan and zoom").assertIsSelected()
        select("Still")
        compose.onNodeWithText("Fade").assertIsSelected()
        compose.onNodeWithText("Random").assertIsSelected()

        select("Skip videos")
        compose.onNodeWithText(VIDEO_LIMIT_LABEL).assertDoesNotExist()
        compose.onNodeWithText("Mute videos").assertDoesNotExist()
        select("Show thumbnail")
        compose.onNodeWithText(VIDEO_LIMIT_LABEL).assertDoesNotExist()
        select("Play video")
        compose.onNodeWithText("Mute videos").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Mute videos").assertIsOff()
        replaceField(VIDEO_LIMIT_LABEL, "17")
        compose.onNodeWithText(CAPTIONS_LABEL).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(CAPTIONS_LABEL).assertIsOn()

        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        compose.onNodeWithText("Start").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    SlideshowConfig(
                        order = SlideshowOrder.RANDOM,
                        durationSeconds = 8,
                        motion = SlideshowMotion.STILL,
                        transition = SlideshowTransition.FADE,
                        videoPolicy = SlideshowVideoPolicy.PLAY,
                        muted = false,
                        videoLimitSeconds = 17,
                        captions = true
                    )
                ),
                started
            )
            assertEquals(0, dismissCount)
        }
    }

    @Test
    fun playVideoRequiresAPositiveCapOrBlankForCompletion() {
        show(SlideshowConfig(videoPolicy = SlideshowVideoPolicy.PLAY))
        listOf("0", "-5", "invalid").forEach { value ->
            replaceField(VIDEO_LIMIT_LABEL, value)
            compose.onNodeWithText("Enter a positive number or leave blank.")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Start").assertIsNotEnabled()
        }
        replaceField(VIDEO_LIMIT_LABEL, "1")
        compose.onNodeWithText("Start").assertIsEnabled()
        replaceField(VIDEO_LIMIT_LABEL, "")
        compose.onNodeWithText("Enter a positive number or leave blank.").assertDoesNotExist()
        compose.onNodeWithText("Leave blank to play to completion.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(SlideshowConfig(videoPolicy = SlideshowVideoPolicy.PLAY)), started)
        }
    }

    @Test
    fun anInvalidHiddenVideoCapCannotBlockThumbnailOrSkipPlayback() {
        show(SlideshowConfig(videoPolicy = SlideshowVideoPolicy.PLAY, videoLimitSeconds = 30))
        replaceField(VIDEO_LIMIT_LABEL, "invalid")
        compose.onNodeWithText("Start").assertIsNotEnabled()

        select("Show thumbnail")
        compose.onNodeWithText(VIDEO_LIMIT_LABEL).assertDoesNotExist()
        compose.onNodeWithText("Enter a positive number or leave blank.").assertDoesNotExist()
        compose.onNodeWithText("Start").assertIsEnabled()
        select("Skip videos")
        compose.onNodeWithText("Start").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(1, started.size)
            assertEquals(SlideshowVideoPolicy.SKIP, started.single().videoPolicy)
            assertEquals(30, started.single().videoLimitSeconds)
            assertTrue(started.single().isValid)
        }
    }

    @Test
    fun cancelAfterEditingDismissesWithoutStarting() {
        show()
        select("Random")
        replaceField(DURATION_LABEL, "12")
        compose.onNodeWithText(CAPTIONS_LABEL).performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Slideshow").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(started.isEmpty())
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun initialConfigurationRepopulatesEveryAvailableControlWhenReopened() {
        val initial = SlideshowConfig(
            order = SlideshowOrder.RANDOM,
            durationSeconds = 37,
            motion = SlideshowMotion.STILL,
            transition = SlideshowTransition.CUT,
            videoPolicy = SlideshowVideoPolicy.PLAY,
            muted = false,
            videoLimitSeconds = 90,
            captions = true
        )
        show(initial)
        assertInitialConfiguration()
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle {
            assertEquals(listOf(initial), started)
            state = SlideshowOptionsState(started.single())
            open = true
        }
        assertInitialConfiguration()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(listOf(initial), started)
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun eachChoiceAndToggleExposesOneActionWithNoClickableChildControl() {
        show()
        listOf("Random", "Still", "Fade", "Skip videos", "Play video", CAPTIONS_LABEL).forEach {
            assertSingleAction(it)
        }
        var before = compose.runOnIdle { changeCount }
        select("Random")
        compose.onNodeWithText("Sequential").assertIsNotSelected()
        compose.runOnIdle { assertEquals(before + 1, changeCount) }

        select("Play video")
        assertSingleAction("Mute videos")
        before = compose.runOnIdle { changeCount }
        compose.onNodeWithText("Mute videos").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(before + 1, changeCount) }
        before = compose.runOnIdle { changeCount }
        compose.onNodeWithText(CAPTIONS_LABEL).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(before + 1, changeCount) }
    }

    @Test
    fun unsupportedFullscreenHidesTheActionEvenWhenTheSavedPreferenceIsEnabled() {
        val initial = SlideshowConfig(fullscreen = true)
        show(initial, supportsFullscreen = false)
        compose.onNodeWithText(FULLSCREEN_LABEL).assertDoesNotExist()
        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(initial), started)
            assertEquals(0, changeCount)
        }
    }

    @Test
    fun supportedFullscreenDisplaysTheSavedPreferenceAndEmitsOneToggleChange() {
        val initial = SlideshowConfig(fullscreen = true)
        show(initial, supportsFullscreen = true)
        compose.onNodeWithText(FULLSCREEN_LABEL).performScrollTo().assertIsOn()
        assertSingleAction(FULLSCREEN_LABEL)
        compose.onNodeWithText(FULLSCREEN_LABEL).performClick()
        compose.onNodeWithText(FULLSCREEN_LABEL).assertIsOff()
        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(initial.copy(fullscreen = false)), started)
            assertEquals(1, changeCount)
            assertEquals(0, dismissCount)
        }
    }

    private fun show(
        initial: SlideshowConfig = SlideshowConfig(),
        supportsFullscreen: Boolean = false
    ) {
        state = SlideshowOptionsState(initial)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            MaterialTheme {
                if (open) {
                    SlideshowOptionsContent(
                        state = state,
                        onChange = {
                            changeCount++
                            state = it
                        },
                        onConfirm = {
                            started.add(checkNotNull(state.validated))
                            open = false
                        },
                        onDismiss = {
                            dismissCount++
                            open = false
                        },
                        supportsFullscreen = supportsFullscreen
                    )
                }
            }
        }
    }

    private fun select(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithText(label).assertIsSelected()
    }

    private fun replaceField(label: String, value: String) {
        compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(value)
    }

    private fun assertFieldValue(label: String, value: String) {
        compose.onNode(hasSetTextAction() and hasText(label))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }

    private fun assertInitialConfiguration() {
        compose.onNodeWithText("Random").performScrollTo().assertIsSelected()
        assertFieldValue(DURATION_LABEL, "37")
        compose.onNodeWithText("Still").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Cut").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Play video").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Mute videos").performScrollTo().assertIsOff()
        assertFieldValue(VIDEO_LIMIT_LABEL, "90")
        compose.onNodeWithText(CAPTIONS_LABEL).performScrollTo().assertIsOn()
    }

    private fun assertSingleAction(label: String) {
        val control = hasClickAction() and (hasText(label) or hasAnyDescendant(hasText(label)))
        compose.onAllNodes(control, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(control), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private companion object {
        const val DURATION_LABEL = "Duration (seconds)"
        const val VIDEO_LIMIT_LABEL = "Video limit (seconds)"
        const val CAPTIONS_LABEL = "Show date, location and description"
        const val FULLSCREEN_LABEL = "Enter fullscreen automatically"
    }
}
