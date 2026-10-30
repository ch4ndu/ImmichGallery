package com.udnahc.immichgallery.ui.util

import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import org.openani.mediamp.mpv.MpvMediampPlayer

/**
 * Fail-closed availability boundary for the packaged host mpv runtime. The runtime jar's loader
 * extracts and validates its declared natives; there is no installed or system fallback.
 */
internal object JvmMpvBootstrap {
    private val preparation = JvmMpvPreparation { retrying ->
        if (!retrying) {
            MpvMediampPlayer.prepareLibraries()
        } else {
            // MediaMP skips extraction when its wrapper file already exists. A failed copy
            // may have left that wrapper behind, so retry only in a new owned directory.
            val directory = Files.createTempDirectory("immichgallery-mpv-retry").toFile()
            try {
                MpvMediampPlayer.prepareLibraries(directory.absolutePath)
            } catch (failure: IOException) {
                // IOException occurs before native load; these partial files are safe to remove.
                runCatching { directory.deleteRecursively() }
                throw failure
            } finally {
                // Successful (or partially native-loaded) files stay in place until process exit.
                // Register parents first because deleteOnExit removes entries in reverse order.
                directory.walkTopDown().forEach { it.deleteOnExit() }
            }
        }
    }

    /** Successful preparation is once-only; only an explicit Retry may repeat failed extraction. */
    fun initialize(retryPreparation: Boolean = false): JvmMpvAvailability =
        preparation.initialize(retryPreparation)

    /** Null when the runtime is unavailable or native construction fails. Call on the UI thread. */
    fun createPlayer(): MpvMediampPlayer? =
        if (initialize() == JvmMpvAvailability.Ready) {
            nativeBoundary { MpvMediampPlayer(Unit, EmptyCoroutineContext) }
        } else null

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

internal enum class JvmMpvAvailability { Ready, RetryableFailure, Unavailable }

/** Injected preparation boundary keeps retry policy testable without loading native libraries. */
internal class JvmMpvPreparation(private val prepareLibraries: (retrying: Boolean) -> Unit) {
    private var availability: JvmMpvAvailability? = null

    @Synchronized
    fun initialize(retryPreparation: Boolean = false): JvmMpvAvailability {
        val previous = availability
        if (previous != null && !(retryPreparation && previous == JvmMpvAvailability.RetryableFailure)) {
            return previous
        }
        // In MediaMP 0.5.1, IO failures precede System.load. Missing/invalid classpath
        // content, security failures and linkage errors must never reconfigure a native runtime.
        return try {
            prepareLibraries(previous == JvmMpvAvailability.RetryableFailure)
            JvmMpvAvailability.Ready
        } catch (_: IOException) {
            JvmMpvAvailability.RetryableFailure
        } catch (_: Exception) {
            JvmMpvAvailability.Unavailable
        } catch (_: LinkageError) {
            JvmMpvAvailability.Unavailable
        }.also { availability = it }
    }
}
