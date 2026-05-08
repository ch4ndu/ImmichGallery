package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class SyncActivityKey {
    abstract val id: String

    data object Timeline : SyncActivityKey() {
        override val id: String = "timeline"
    }

    data object Albums : SyncActivityKey() {
        override val id: String = "albums"
    }

    data object People : SyncActivityKey() {
        override val id: String = "people"
    }

    data class AlbumDetail(val albumId: String) : SyncActivityKey() {
        override val id: String = "album:$albumId"
    }

    data class PersonDetail(val personId: String) : SyncActivityKey() {
        override val id: String = "person:$personId"
    }
}

class SyncActivityTracker(
    private val notifier: PlatformSyncActivityNotifier
) {
    private val mutex = Mutex()
    private val activeCounts = mutableMapOf<SyncActivityKey, Int>()
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    suspend fun begin(key: SyncActivityKey) {
        val count = mutex.withLock {
            activeCounts[key] = (activeCounts[key] ?: 0) + 1
            val updatedCount = activeCounts.values.sum()
            _activeCount.value = updatedCount
            updatedCount
        }
        notifier.onActiveSyncCountChanged(count)
    }

    suspend fun end(key: SyncActivityKey) {
        val count = mutex.withLock {
            val current = activeCounts[key] ?: 0
            when {
                current <= 1 -> activeCounts.remove(key)
                else -> activeCounts[key] = current - 1
            }
            val updatedCount = activeCounts.values.sum()
            _activeCount.value = updatedCount
            updatedCount
        }
        notifier.onActiveSyncCountChanged(count)
    }

    suspend fun <T> track(key: SyncActivityKey, block: suspend () -> T): T {
        begin(key)
        try {
            return block()
        } finally {
            end(key)
        }
    }
}

interface PlatformSyncActivityNotifier {
    fun onActiveSyncCountChanged(activeCount: Int)
}
