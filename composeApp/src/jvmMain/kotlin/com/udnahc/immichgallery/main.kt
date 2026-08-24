package com.udnahc.immichgallery

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import com.udnahc.immichgallery.ui.util.JvmVlcBootstrap
import com.udnahc.immichgallery.ui.util.installJvmWakeInhibitionShutdownHook
import com.udnahc.immichgallery.ui.util.shutdownJvmWakeInhibition
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform

fun main() {
    disableApplicationLogging()
    JvmVlcBootstrap.initialize()
    installJvmWakeInhibitionShutdownHook()
    if (KoinPlatform.getKoinOrNull() == null) {
        startKoin {
            modules(sharedModule, platformModule())
        }
    }
    try {
        application {
            Window(
                onCloseRequest = {
                    shutdownJvmWakeInhibition()
                    exitApplication()
                },
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
    } finally {
        shutdownJvmWakeInhibition()
    }
}
