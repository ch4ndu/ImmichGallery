package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class MosaicWorkPriority {
    ForegroundVisible,
    ForegroundPrefetch,
    Background
}

class MosaicWorkCancelledException(message: String) : CancellationException(message)

class MosaicWorkToken internal constructor(
    private val isActive: () -> Boolean
) {
    fun ensureActive() {
        if (!isActive()) throw MosaicWorkCancelledException("Mosaic work is no longer active")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MosaicWorkScheduler(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(DEFAULT_PARALLELISM),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
) {
    private val mutex = Mutex()
    private val pending = MosaicPriorityWorkQueue()
    private val running = mutableSetOf<MosaicWork<*>>()
    private val ownerVisibleRequests = mutableMapOf<String, OwnerVisibilitySnapshot>()
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
            ownerVisibleRequests.remove(ownerKey)
            val stale = pending.removeAll { it.ownerKey == ownerKey }
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
        visibleRequestKeys: Set<String>,
        visibilityRevision: Long? = null
    ) {
        mutex.withLock {
            if (visibilityRevision != null) {
                val currentRevision = ownerVisibleRequests[ownerKey]?.revision
                if (currentRevision != null && visibilityRevision < currentRevision) {
                    return
                }
                ownerVisibleRequests[ownerKey] = OwnerVisibilitySnapshot(
                    revision = visibilityRevision,
                    visibleRequestKeys = visibleRequestKeys
                )
            } else {
                val nextRevision = (ownerVisibleRequests[ownerKey]?.revision ?: 0L) + 1L
                ownerVisibleRequests[ownerKey] = OwnerVisibilitySnapshot(
                    revision = nextRevision,
                    visibleRequestKeys = visibleRequestKeys
                )
            }
            pending
                .filter { it.ownerKey == ownerKey && it.priority != MosaicWorkPriority.Background }
                .forEach { work ->
                    work.priority = if (work.requestKey in visibleRequestKeys) {
                        MosaicWorkPriority.ForegroundVisible
                    } else {
                        MosaicWorkPriority.ForegroundPrefetch
                    }
                }
            if (visibleRequestKeys.isNotEmpty()) {
                running
                    .filter { work ->
                        work.ownerKey == ownerKey &&
                                work.priority == MosaicWorkPriority.ForegroundPrefetch &&
                                work.requestKey !in visibleRequestKeys
                    }
                    .forEach { it.cancel() }
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
            if (work.priority != MosaicWorkPriority.Background) {
                ownerVisibleRequests[ownerKey]?.visibleRequestKeys?.let { visibleKeys ->
                    work.priority = if (requestKey in visibleKeys) {
                        MosaicWorkPriority.ForegroundVisible
                    } else {
                        MosaicWorkPriority.ForegroundPrefetch
                    }
                }
            }
            val stale = pending.removeAll { it.ownerKey == ownerKey && it.requestKey == requestKey }
            pending.add(work)
            startWorkerIfNeeded()
            stale
        }
        replaced.forEach { it.cancel() }
        try {
            return work.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    pending.remove(work)
                    work.cancel()
                }
                work.awaitExecutionFinished()
            }
            throw e
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
            val (work, executionJob) = mutex.withLock {
                val next = pending.popNext(activeForegroundOwner)
                if (next == null) {
                    workerJob = null
                    return
                }
                running.add(next)
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    next.execute()
                }
                next.attachExecution(job)
                next to job
            }
            executionJob.start()
            executionJob.join()
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
        private val executionFinished = CompletableDeferred<Unit>()
        private var executionJob: Job? = null

        fun attachExecution(job: Job) {
            check(executionJob == null) { "Mosaic work execution is already attached" }
            executionJob = job
            job.invokeOnCompletion {
                executionFinished.complete(Unit)
            }
        }

        suspend fun execute() {
            if (!result.isActive) return
            val currentJob = checkNotNull(currentCoroutineContext()[Job]) {
                "Mosaic work execution requires a coroutine Job"
            }
            val token = MosaicWorkToken { result.isActive && currentJob.isActive }
            try {
                result.complete(block(token))
            } catch (e: CancellationException) {
                result.cancel(e)
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }

        suspend fun await(): T = result.await()

        suspend fun awaitExecutionFinished() {
            executionFinished.await()
        }

        fun cancel() {
            val cancellation = MosaicWorkCancelledException("Mosaic work was cancelled")
            result.cancel(cancellation)
            val job = executionJob
            if (job == null) {
                executionFinished.complete(Unit)
            } else {
                job.cancel(cancellation)
            }
        }
    }

    private inner class MosaicPriorityWorkQueue {
        private val items = mutableListOf<MosaicWork<*>>()

        fun add(work: MosaicWork<*>) {
            items.add(work)
        }

        fun remove(work: MosaicWork<*>) {
            items.remove(work)
        }

        fun removeAll(predicate: (MosaicWork<*>) -> Boolean): List<MosaicWork<*>> {
            val removed = items.filter(predicate)
            items.removeAll(removed)
            return removed
        }

        fun filter(predicate: (MosaicWork<*>) -> Boolean): List<MosaicWork<*>> =
            items.filter(predicate)

        fun any(predicate: (MosaicWork<*>) -> Boolean): Boolean =
            items.any(predicate)

        fun popNext(activeOwner: String?): MosaicWork<*>? {
            val next = items.minWithOrNull(
                compareBy<MosaicWork<*>> { it.priorityRank(activeOwner) }.thenBy { it.sequence }
            ) ?: return null
            items.remove(next)
            return next
        }
    }

    private data class OwnerVisibilitySnapshot(
        val revision: Long,
        val visibleRequestKeys: Set<String>
    )

    companion object {
        private const val DEFAULT_PARALLELISM = 1
    }
}
