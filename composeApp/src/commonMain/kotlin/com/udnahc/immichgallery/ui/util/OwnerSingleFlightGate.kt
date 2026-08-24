package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class OwnerSingleFlightGate {
    private val claimed = MutableStateFlow(false)

    fun launch(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        request: suspend () -> Unit
    ): Job? {
        if (!claimed.compareAndSet(expect = false, update = true)) return null
        return scope.launch(dispatcher) {
            try {
                request()
            } finally {
                claimed.value = false
            }
        }
    }
}
