package com.udnahc.immichgallery

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import com.udnahc.immichgallery.ui.component.LocalSlideshowWindowHost
import com.udnahc.immichgallery.ui.util.JvmMpvBootstrap
import com.udnahc.immichgallery.ui.util.installJvmWakeInhibitionShutdownHook
import com.udnahc.immichgallery.ui.util.shutdownJvmWakeInhibition
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.app_title
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
        startKoin { modules(sharedModule, platformModule()) }
    }
    try {
        application {
            val scope = rememberCoroutineScope()
            val host = remember(scope) {
                DesktopSlideshowWindows(scope, KoinPlatform.getKoin()) {
                    shutdownJvmWakeInhibition()
                    exitApplication()
                }
            }
            if (host.libraryVisible) {
                val windowState = rememberWindowState()
                val owner = remember { DesktopWindowOwner() }
                DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                Window(
                    onCloseRequest = host::closeLibrary,
                    title = stringResource(Res.string.app_title),
                    state = windowState
                ) {
                    DesktopLibraryMenu(host)
                    LaunchedEffect(host.libraryActivation) {
                        if (host.libraryActivation > 0) {
                            windowState.isMinimized = false
                            window.toFront()
                            window.requestFocus()
                        }
                    }
                    DisposableEffect(window) {
                        val listener = object : WindowFocusListener {
                            override fun windowGainedFocus(event: WindowEvent?) { AppLifecycle.setForeground(true) }
                            override fun windowLostFocus(event: WindowEvent?) { AppLifecycle.setForeground(false) }
                        }
                        window.addWindowFocusListener(listener)
                        AppLifecycle.setForeground(window.isFocused)
                        onDispose { window.removeWindowFocusListener(listener) }
                    }
                    DesktopWindowEnvironment(window, windowState) {
                        CompositionLocalProvider(
                            LocalViewModelStoreOwner provides owner,
                            LocalSlideshowWindowHost provides host::launch
                        ) { App() }
                    }
                }
            }
            DesktopSlideshowWindowHosts(host)
        }
    } finally {
        shutdownJvmWakeInhibition()
    }
}
