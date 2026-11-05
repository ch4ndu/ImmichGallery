package com.udnahc.immichgallery.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.action.settings.SetSlideshowConfigAction
import com.udnahc.immichgallery.domain.usecase.settings.GetSlideshowConfigUseCase
import com.udnahc.immichgallery.test.TestSettings
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class VideoMutePreferencesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun controlsAndGestureShareSavedMuteWithRetainedNewAndRestartedPlayers() {
        val settings = TestSettings()
        fun preferences(): ViewerPreferencesViewModel {
            val repository = ServerConfigRepository(settings)
            return ViewerPreferencesViewModel(GetSlideshowConfigUseCase(repository), SetSlideshowConfigAction(repository))
        }
        var preferences by mutableStateOf(preferences())
        var page by mutableStateOf(0)
        lateinit var current: NormalVideoPlaybackState
        lateinit var retained: NormalVideoPlaybackState
        compose.setContent {
            val first = rememberNormalVideoPlaybackState(page, preferences = preferences)
            val second = rememberNormalVideoPlaybackState("retained", preferences = preferences)
            SideEffect {
                current = first
                retained = second
                first.ready(first.retryAttempt)
            }
            MaterialTheme {
                NormalVideoControls(first, visible = true, onPlayPause = {},
                    onMuteToggle = { first.muted = !first.muted }, onSeek = {},
                    onInteraction = {}, onHoldChange = { _, _ -> })
            }
        }
        compose.runOnIdle { assertTrue(current.muted); assertTrue(retained.muted) }
        compose.onNodeWithContentDescription("Unmute video").performClick()
        compose.runOnIdle {
            assertFalse(current.muted)
            assertFalse(retained.muted)
            assertFalse(ServerConfigRepository(settings).getSlideshowConfig().muted)
        }
        val old = current
        compose.runOnIdle { page++ }
        compose.runOnIdle { assertNotSame(old, current); assertFalse(current.muted) }
        compose.onNodeWithContentDescription("Mute video").performClick()
        compose.runOnIdle { preferences = preferences() } // Recreate persistence and UI owners.
        compose.runOnIdle {
            assertTrue(current.muted)
            assertTrue(retained.muted)
            current.adjustVolume(0.4f, unmute = { current.muted = false }, setVolume = {})
        }
        compose.runOnIdle {
            assertFalse(retained.muted)
            assertEquals(0.4f, current.effectiveVolume)
            assertFalse(ServerConfigRepository(settings).getSlideshowConfig().muted)
            // A slideshow/options change updates already-created normal players too.
            preferences.setSlideshowConfig(preferences.slideshowConfig.value.copy(muted = true))
        }
        compose.runOnIdle { assertTrue(current.muted); assertTrue(retained.muted) }
    }
}
