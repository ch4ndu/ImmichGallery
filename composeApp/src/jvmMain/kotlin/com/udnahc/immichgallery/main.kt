package com.udnahc.immichgallery

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import com.udnahc.immichgallery.ui.util.JvmMpvBootstrap
import com.udnahc.immichgallery.ui.util.LocalViewerFullscreen
import com.udnahc.immichgallery.ui.util.ViewerFullscreenController
import com.udnahc.immichgallery.ui.util.ViewerWindowPlacement
import com.udnahc.immichgallery.ui.util.installJvmWakeInhibitionShutdownHook
import com.udnahc.immichgallery.ui.util.shutdownJvmWakeInhibition
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.app_title
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform

fun main() {
    disableApplicationLogging()
    JvmMpvBootstrap.initialize()
    installJvmWakeInhibitionShutdownHook()
    if (KoinPlatform.getKoinOrNull() == null) {
        startKoin {
            modules(sharedModule, platformModule())
        }
    }
    try {
        application {
            val windowState = rememberWindowState()
            Window(
                onCloseRequest = {
                    shutdownJvmWakeInhibition()
                    exitApplication()
                },
                title = stringResource(Res.string.app_title),
                state = windowState,
            ) {
                var windowVisible by remember(window) { mutableStateOf(window.isVisible) }
                val fullscreen = remember(window, windowState) {
                    ViewerFullscreenController(
                        readPlacement = {
                            when (windowState.placement) {
                                WindowPlacement.Floating -> ViewerWindowPlacement.Floating
                                WindowPlacement.Maximized -> ViewerWindowPlacement.Maximized
                                WindowPlacement.Fullscreen -> ViewerWindowPlacement.Fullscreen
                            }
                        },
                        writePlacement = { placement ->
                            windowState.placement = when (placement) {
                                ViewerWindowPlacement.Floating -> WindowPlacement.Floating
                                ViewerWindowPlacement.Maximized -> WindowPlacement.Maximized
                                ViewerWindowPlacement.Fullscreen -> WindowPlacement.Fullscreen
                            }
                        },
                        readWindowVisibility = { windowVisible && !windowState.isMinimized },
                    )
                }
                DisposableEffect(window, fullscreen) {
                    val listener = object : ComponentAdapter() {
                        override fun componentShown(event: ComponentEvent?) {
                            windowVisible = true
                        }

                        override fun componentHidden(event: ComponentEvent?) {
                            windowVisible = false
                        }
                    }
                    window.addComponentListener(listener)
                    windowVisible = window.isVisible
                    onDispose {
                        window.removeComponentListener(listener)
                        fullscreen.close()
                    }
                }
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
                CompositionLocalProvider(LocalViewerFullscreen provides fullscreen) {
                    App()
                }
            }
        }
    } finally {
        shutdownJvmWakeInhibition()
    }
}
