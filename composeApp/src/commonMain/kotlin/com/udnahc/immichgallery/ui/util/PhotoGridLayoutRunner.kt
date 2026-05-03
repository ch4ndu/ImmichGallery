package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGridLayoutRunner(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(DEFAULT_PARALLELISM),
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS
) {
    private var generation = 0L
    private var job: Job? = null

    fun launch(
        debounce: Boolean = false,
        block: suspend (generation: Long) -> Unit
    ) {
        val nextGeneration = ++generation
        job?.cancel()
        job = scope.launch(dispatcher) {
            if (debounce) delay(debounceMillis)
            block(nextGeneration)
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun isCurrent(generation: Long): Boolean =
        generation == this.generation

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 200L
        private const val DEFAULT_PARALLELISM = 1
    }
}
