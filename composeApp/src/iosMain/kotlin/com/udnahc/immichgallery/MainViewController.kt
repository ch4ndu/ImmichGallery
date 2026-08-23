package com.udnahc.immichgallery

import androidx.compose.ui.window.ComposeUIViewController
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform

fun MainViewController() = run {
    disableApplicationLogging()
    if (KoinPlatform.getKoinOrNull() == null) {
        startKoin {
            modules(sharedModule, platformModule())
        }
    }
    ComposeUIViewController { App() }
}
