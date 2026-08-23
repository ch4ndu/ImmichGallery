package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGridLayoutRunner(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(
        DEFAULT_PARALLELISM
    ),
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS
) {
    private val runnerState = MutableStateFlow(RunnerState())
    private var beforeLaunchStateCompare: () -> Unit = {}
    private var beforeCandidateStart: () -> Unit = {}

    internal constructor(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        debounceMillis: Long,
        beforeLaunchStateCompare: () -> Unit = {},
        beforeCandidateStart: () -> Unit = {}
    ) : this(scope, dispatcher, debounceMillis) {
        this.beforeLaunchStateCompare = beforeLaunchStateCompare
        this.beforeCandidateStart = beforeCandidateStart
    }

    fun launch(
        debounce: Boolean = false,
        block: suspend (generation: Long) -> Unit
    ) {
        while (true) {
            val current = runnerState.value
            val nextGeneration = current.generation + 1L
            val candidate = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                if (debounce) delay(debounceMillis)
                block(nextGeneration)
            }
            beforeLaunchStateCompare()
            if (runnerState.compareAndSet(current, RunnerState(nextGeneration, candidate))) {
                current.job?.cancel()
                beforeCandidateStart()
                candidate.start()
                return
            } else {
                candidate.cancel()
            }
        }
    }

    fun cancel() {
        while (true) {
            val current = runnerState.value
            if (runnerState.compareAndSet(current, RunnerState(current.generation + 1L))) {
                current.job?.cancel()
                return
            }
        }
    }

    fun isCurrent(generation: Long): Boolean =
        generation == runnerState.value.generation

    private data class RunnerState(
        val generation: Long = 0L,
        val job: Job? = null
    )

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 200L
        private const val DEFAULT_PARALLELISM = 1
    }
}
