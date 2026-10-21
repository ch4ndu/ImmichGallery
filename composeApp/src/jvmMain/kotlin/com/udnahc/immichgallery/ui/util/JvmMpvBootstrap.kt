package com.udnahc.immichgallery.ui.util

import kotlin.coroutines.EmptyCoroutineContext
import org.openani.mediamp.mpv.MpvMediampPlayer

/**
 * Fail-closed availability boundary for the packaged host mpv runtime. The runtime jar's loader
 * extracts and validates its declared natives; there is no installed or system fallback.
 */
internal object JvmMpvBootstrap {
    @Volatile
    private var availability: Boolean? = null

    /** Prepares the classpath runtime once, before any player initializes native code. */
    fun initialize(): Boolean {
        availability?.let { return it }
        return synchronized(this) {
            availability ?: (nativeBoundary { MpvMediampPlayer.prepareLibraries() } != null).also {
                availability = it
            }
        }
    }

    /** Null when the runtime is unavailable or native construction fails. Call on the UI thread. */
    fun createPlayer(): MpvMediampPlayer? =
        if (initialize()) nativeBoundary { MpvMediampPlayer(Unit, EmptyCoroutineContext) } else null

    // Extraction, validation and native construction are synchronous, so no cancellation is masked.
    private inline fun <T : Any> nativeBoundary(block: () -> T): T? =
        try {
            block()
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
}
