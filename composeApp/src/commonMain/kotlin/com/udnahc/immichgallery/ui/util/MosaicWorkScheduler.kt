package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MosaicWorkPriority {
    ForegroundVisible,
    ForegroundPrefetch,
    Background
}

class MosaicWorkToken internal constructor(
    private val isActive: () -> Boolean
) {
    fun ensureActive() {
        if (!isActive()) throw CancellationException("Mosaic work is no longer active")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MosaicWorkScheduler(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(DEFAULT_PARALLELISM),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<MosaicWork<*>>()
    private val running = mutableSetOf<MosaicWork<*>>()
    private var workerJob: Job? = null
    private var sequence = 0L
    private var activeForegroundOwner: String? = null

    suspend fun setActiveForegroundOwner(ownerKey: String?) {
        mutex.withLock {
            activeForegroundOwner = ownerKey
        }
    }

    suspend fun clearActiveForegroundOwner(ownerKey: String) {
        mutex.withLock {
            if (activeForegroundOwner == ownerKey) {
                activeForegroundOwner = null
            }
        }
    }

    fun clearActiveForegroundOwnerAsync(ownerKey: String) {
        scope.launch {
            clearActiveForegroundOwner(ownerKey)
        }
    }

    fun cancelOwnerAsync(ownerKey: String) {
        scope.launch {
            cancelOwner(ownerKey)
        }
    }

    suspend fun cancelOwner(ownerKey: String) {
        val removed = mutex.withLock {
            val stale = pending.filter { it.ownerKey == ownerKey }
            pending.removeAll(stale)
            running.filter { it.ownerKey == ownerKey }.forEach { it.cancel() }
            stale
        }
        removed.forEach { it.cancel() }
    }

    suspend fun hasActiveForegroundWork(): Boolean =
        mutex.withLock {
            pending.any { it.isActiveForegroundWork() } || running.any { it.isActiveForegroundWork() }
        }

    suspend fun reprioritizePending(
        ownerKey: String,
        visibleRequestKeys: Set<String>
    ) {
        mutex.withLock {
            pending
                .filter { it.ownerKey == ownerKey && it.priority != MosaicWorkPriority.Background }
                .forEach { work ->
                    work.priority = if (work.requestKey in visibleRequestKeys) {
                        MosaicWorkPriority.ForegroundVisible
                    } else {
                        MosaicWorkPriority.ForegroundPrefetch
                    }
                }
        }
    }

    suspend fun <T> run(
        ownerKey: String,
        requestKey: String,
        generation: Long,
        priority: MosaicWorkPriority,
        block: suspend (MosaicWorkToken) -> T
    ): T {
        val work = MosaicWork(
            ownerKey = ownerKey,
            requestKey = requestKey,
            generation = generation,
            priority = priority,
            sequence = nextSequence(),
            block = block
        )
        val replaced = mutex.withLock {
            val stale = pending.filter { it.ownerKey == ownerKey && it.requestKey == requestKey }
            pending.removeAll(stale)
            pending.add(work)
            startWorkerIfNeeded()
            stale
        }
        replaced.forEach { it.cancel() }
        try {
            return work.await()
        } finally {
            if (!work.isCompleted) {
                mutex.withLock {
                    pending.remove(work)
                    if (work in running) work.cancel()
                }
            }
        }
    }

    private suspend fun nextSequence(): Long =
        mutex.withLock {
            sequence++
        }

    private fun startWorkerIfNeeded() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            workerLoop()
        }
    }

    private suspend fun workerLoop() {
        while (true) {
            val work = mutex.withLock {
                val next = pending
                    .minWithOrNull(compareBy<MosaicWork<*>> { it.priorityRank(activeForegroundOwner) }.thenBy { it.sequence })
                if (next == null) {
                    workerJob = null
                    return
                }
                pending.remove(next)
                running.add(next)
                next
            }
            work.execute()
            mutex.withLock {
                running.remove(work)
            }
        }
    }

    private fun MosaicWork<*>.priorityRank(activeOwner: String?): Int =
        when {
            ownerKey == activeOwner && priority == MosaicWorkPriority.ForegroundVisible -> 0
            ownerKey == activeOwner && priority == MosaicWorkPriority.ForegroundPrefetch -> 1
            priority == MosaicWorkPriority.ForegroundVisible -> 2
            priority == MosaicWorkPriority.ForegroundPrefetch -> 3
            else -> 4
        }

    private fun MosaicWork<*>.isActiveForegroundWork(): Boolean =
        ownerKey == activeForegroundOwner &&
            (priority == MosaicWorkPriority.ForegroundVisible || priority == MosaicWorkPriority.ForegroundPrefetch)

    private class MosaicWork<T>(
        val ownerKey: String,
        val requestKey: String,
        val generation: Long,
        var priority: MosaicWorkPriority,
        val sequence: Long,
        private val block: suspend (MosaicWorkToken) -> T
    ) {
        private val result = CompletableDeferred<T>()
        private var active = true
        val isCompleted: Boolean get() = result.isCompleted

        suspend fun execute() {
            if (!active) {
                cancel()
                return
            }
            val token = MosaicWorkToken { active }
            try {
                result.complete(block(token))
            } catch (e: CancellationException) {
                result.cancel(e)
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }

        suspend fun await(): T = result.await()

        fun cancel() {
            active = false
            result.cancel(CancellationException("Mosaic work was cancelled"))
        }
    }

    companion object {
        private const val DEFAULT_PARALLELISM = 1
    }
}
