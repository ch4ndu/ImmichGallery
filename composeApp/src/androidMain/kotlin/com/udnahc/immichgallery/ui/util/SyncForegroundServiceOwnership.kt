package com.udnahc.immichgallery.ui.util

/**
 * Main-thread ownership state for [SyncForegroundService]. The shared tracker
 * decides foreground lifetime; manual start IDs only keep the immediate
 * bootstrap alive until their coroutine has entered or left tracked work.
 */
internal class SyncForegroundServiceOwnership {
    private val activeManualStartIds = linkedSetOf<Int>()
    private var latestStartId: Int? = null
    private var terminated = false

    fun onServiceStart(startId: Int): Boolean {
        if (terminated) return false
        latestStartId = startId
        return true
    }

    fun onManualJobStarted(startId: Int) {
        check(!terminated) { "A terminated service instance cannot own work" }
        activeManualStartIds += startId
    }

    fun onManualJobFinished(startId: Int) {
        activeManualStartIds -= startId
    }

    fun stopStartId(trackedActiveCount: Int): Int? {
        require(trackedActiveCount >= 0) { "Tracked active count cannot be negative" }
        return latestStartId.takeIf {
            !terminated && trackedActiveCount == 0 && activeManualStartIds.isEmpty()
        }
    }

    fun onStopped(startId: Int) {
        if (latestStartId == startId) {
            latestStartId = null
        }
    }

    fun onTimeout(): Set<Int> = terminate()

    fun onDestroy(): Set<Int> = terminate()

    private fun terminate(): Set<Int> {
        val startIdsToCancel = activeManualStartIds.toSet()
        activeManualStartIds.clear()
        latestStartId = null
        terminated = true
        return startIdsToCancel
    }

    internal fun activeManualStartIds(): Set<Int> = activeManualStartIds.toSet()

    internal fun latestStartId(): Int? = latestStartId
}
