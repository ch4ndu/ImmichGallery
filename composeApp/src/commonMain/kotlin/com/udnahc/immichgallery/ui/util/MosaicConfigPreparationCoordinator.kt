package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class MosaicConfigPreparationCoordinator {
    private val callbacks = MutableStateFlow<Map<String, suspend () -> Unit>>(emptyMap())

    fun register(
        ownerKey: String,
        callback: suspend () -> Unit
    ) {
        callbacks.update { it + (ownerKey to callback) }
    }

    fun unregister(ownerKey: String) {
        callbacks.update { it - ownerKey }
    }

    suspend fun cancelObsoleteWork() {
        callbacks.value.values.forEach { callback ->
            callback()
        }
    }
}
