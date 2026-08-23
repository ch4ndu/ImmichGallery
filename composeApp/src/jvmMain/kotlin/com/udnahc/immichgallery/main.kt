package com.udnahc.immichgallery

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform

fun main() {
    disableApplicationLogging()
    if (KoinPlatform.getKoinOrNull() == null) {
        startKoin {
            modules(sharedModule, platformModule())
        }
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "ImmichGallery",
        ) {
            DisposableEffect(window) {
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(event: WindowEvent?) {
                        AppLifecycle.setForeground(true)
                    }

                    override fun windowLostFocus(event: WindowEvent?) {
                        AppLifecycle.setForeground(false)
                    }
                }
                window.addWindowFocusListener(listener)
                AppLifecycle.setForeground(window.isFocused)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            App()
        }
    }
}
