package com.udnahc.immichgallery.ui.component

import androidx.lifecycle.ViewModel
import com.udnahc.immichgallery.domain.action.settings.SetSlideshowConfigAction
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.usecase.settings.GetSlideshowConfigUseCase
import kotlinx.coroutines.flow.StateFlow

class ViewerPreferencesViewModel(
    getSlideshowConfigUseCase: GetSlideshowConfigUseCase,
    private val setSlideshowConfigAction: SetSlideshowConfigAction
) : ViewModel() {
    val slideshowConfig: StateFlow<SlideshowConfig> = getSlideshowConfigUseCase.observe()

    fun setSlideshowConfig(config: SlideshowConfig) {
        if (config.normalized == slideshowConfig.value) return
        setSlideshowConfigAction(config)
    }
}
