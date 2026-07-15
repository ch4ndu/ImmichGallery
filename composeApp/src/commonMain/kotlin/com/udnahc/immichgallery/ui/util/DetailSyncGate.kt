package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes one detail owner's page refresh, pagination, and full sync. */
internal class DetailSyncGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
