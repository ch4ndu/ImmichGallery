package com.udnahc.immichgallery

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

fun main() = application {
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
