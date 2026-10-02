package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.domain.action.auth.ClearServerConfigAction
import com.udnahc.immichgallery.domain.action.settings.SetSlideshowConfigAction
import com.udnahc.immichgallery.domain.model.DEFAULT_GRID_COLUMN_COUNT
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.model.SlideshowMotion
import com.udnahc.immichgallery.domain.model.SlideshowOrder
import com.udnahc.immichgallery.domain.model.SlideshowTransition
import com.udnahc.immichgallery.domain.model.SlideshowVideoPolicy
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.usecase.settings.GetSlideshowConfigUseCase
import com.udnahc.immichgallery.test.TestSettings
import com.udnahc.immichgallery.ui.component.ViewerPreferencesViewModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlideshowPreferencesTest {
    private val saved = SlideshowConfig(
        order = SlideshowOrder.RANDOM,
        durationSeconds = 12,
        motion = SlideshowMotion.STILL,
        transition = SlideshowTransition.FADE,
        videoPolicy = SlideshowVideoPolicy.PLAY,
        muted = false,
        videoLimitSeconds = 42,
        captions = true,
        fullscreen = true
    )

    @Test
    fun defaultsRetainExistingPresentationAndAllPreferencesRoundTrip() {
        val settings = TestSettings()
        val repository = ServerConfigRepository(settings)
        assertEquals(SlideshowConfig(), repository.getSlideshowConfig())
        repository.setSlideshowConfig(saved)
        assertEquals(saved, repository.observeSlideshowConfig().value)
        assertEquals(saved, ServerConfigRepository(settings).getSlideshowConfig())
        repository.setSlideshowConfig(saved.copy(videoLimitSeconds = null, transition = SlideshowTransition.CUT))
        assertEquals(saved.copy(videoLimitSeconds = null, transition = SlideshowTransition.CUT),
            ServerConfigRepository(settings).getSlideshowConfig())
        assertFalse(settings.hasKey(SLIDESHOW_VIDEO_LIMIT_KEY))
    }

    @Test
    fun unknownEnumsAndInvalidStoredNumbersUseSupportedValues() {
        val settings = TestSettings().apply {
            putString(SLIDESHOW_ORDER_KEY, "future-order")
            putString(SLIDESHOW_MOTION_KEY, "future-motion")
            putString(SLIDESHOW_TRANSITION_KEY, "future-transition")
            putString(SLIDESHOW_VIDEO_POLICY_KEY, "future-video-policy")
            putInt(SLIDESHOW_DURATION_KEY, 500)
            putInt(SLIDESHOW_VIDEO_LIMIT_KEY, -5)
        }
        assertEquals(SlideshowConfig(durationSeconds = 120), ServerConfigRepository(settings).getSlideshowConfig())
        settings.putInt(SLIDESHOW_DURATION_KEY, -5)
        assertEquals(1, ServerConfigRepository(settings).getSlideshowConfig().durationSeconds)
    }

    @Test
    fun validationKeepsInvalidInputDistinctFromNormalizedPersistence() {
        assertTrue(SlideshowConfig(durationSeconds = 1).isValid)
        assertTrue(SlideshowConfig(durationSeconds = 120, videoLimitSeconds = 1).isValid)
        assertFalse(SlideshowConfig(durationSeconds = 0).isValid)
        assertFalse(SlideshowConfig(durationSeconds = 121).isValid)
        assertFalse(SlideshowConfig(videoLimitSeconds = 0).isValid)
        assertFalse(SlideshowConfig(videoLimitSeconds = -1).isValid)
        val repository = ServerConfigRepository(TestSettings())
        repository.setSlideshowConfig(saved.copy(durationSeconds = 0, videoLimitSeconds = -1))
        assertEquals(saved.copy(durationSeconds = 1, videoLimitSeconds = null), repository.getSlideshowConfig())
    }

    @Test
    fun logoutKeepsDeviceSlideshowPreferencesWhileClearingAllOtherSettings() = runTest {
        val settings = TestSettings()
        val repository = ServerConfigRepository(settings)
        repository.replaceSession("https://example.test", "secret")
        repository.setGridColumns(9)
        repository.setTimelineGroupSize("DAY")
        repository.setViewConfig(ViewConfig(mosaicEnabled = false))
        RowHeightScope.entries.forEach { repository.setTargetRowHeight(it, 123f) }
        settings.putFloat(LEGACY_TIMELINE_TARGET_ROW_HEIGHT_KEY, 99f)
        settings.putString("unrecognized_old_setting", "old-server-value")
        repository.setSlideshowConfig(saved)
        val action = ClearServerConfigAction(
            mutate = SessionStateRepository()::mutate,
            rotateImageCache = { block -> block() },
            clearServerState = {},
            clearConfiguration = repository::clear
        )

        assertTrue(action().isSuccess)

        assertFalse(repository.isLoggedIn())
        assertEquals("", repository.getApiKey())
        assertEquals("", repository.getServerUrl())
        assertEquals(DEFAULT_GRID_COLUMN_COUNT, repository.getGridColumns())
        assertEquals("MONTH", repository.observeTimelineGroupSize().value)
        assertEquals(ViewConfig(), repository.observeViewConfig().value)
        RowHeightScope.entries.forEach { assertEquals(DEFAULT_TARGET_ROW_HEIGHT, repository.getTargetRowHeight(it)) }
        assertFalse(settings.hasKey("unrecognized_old_setting"))
        assertEquals(saved, repository.observeSlideshowConfig().value)
        assertEquals(saved, ServerConfigRepository(settings).getSlideshowConfig())
        assertEquals(SLIDESHOW_SETTING_DESCRIPTORS.map { it.key }.toSet(), settings.keys)
        repository.replaceSession("https://other.example.test", "new-secret")
        assertEquals(saved, repository.getSlideshowConfig())
    }

    @Test
    fun viewerPreferencesReadAndWriteThroughUseCaseAndAction() {
        val repository = ServerConfigRepository(TestSettings())
        val read = GetSlideshowConfigUseCase(repository)
        val first = ViewerPreferencesViewModel(read, SetSlideshowConfigAction(repository))
        val second = ViewerPreferencesViewModel(read, SetSlideshowConfigAction(repository))
        first.setSlideshowConfig(saved)
        assertEquals(saved, read())
        assertEquals(saved, second.slideshowConfig.value)
        second.setSlideshowConfig(saved.copy(videoPolicy = SlideshowVideoPolicy.SKIP, videoLimitSeconds = null))
        assertEquals(SlideshowVideoPolicy.SKIP, first.slideshowConfig.value.videoPolicy)
        assertNull(first.slideshowConfig.value.videoLimitSeconds)
    }
}
