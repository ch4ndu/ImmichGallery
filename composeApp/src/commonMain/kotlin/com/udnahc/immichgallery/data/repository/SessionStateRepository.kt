package com.udnahc.immichgallery.data.repository

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SessionOperationStamp(
    val generation: Long,
    val ownerEpoch: Long
)

class SessionOperationToken internal constructor(
    internal val generation: Long,
    internal val ownerEpoch: Long
)

class SessionOperationRejectedException internal constructor() :
    CancellationException("Session mutation is in progress")

class StaleSessionOperationException internal constructor() :
    CancellationException("Session operation is no longer current")

class SessionStateRepository {
    private val mutationMutex = Mutex()
    private val commitMutex = Mutex()
    private val stateMutex = Mutex()

    private var generation = 0L
    private var ownerEpoch = 0L
    private val publishedState = MutableStateFlow(
        SessionOperationStamp(generation = generation, ownerEpoch = ownerEpoch)
    )
    private var admissionOpen = true
    private val registeredJobs = mutableMapOf<Long, MutableMap<Job, Int>>()

    suspend fun <T> withOperation(
        block: suspend (SessionOperationToken) -> T
    ): T = withOperationForOwner(expectedOwnerEpoch = null, block)

    internal suspend fun <T> withOperationForOwner(
        expectedOwnerEpoch: Long?,
        block: suspend (SessionOperationToken) -> T
    ): T {
        val coroutineContext = currentCoroutineContext()
        val job = checkNotNull(coroutineContext[Job]) {
            "A session operation requires an owning coroutine Job"
        }
        val inherited = coroutineContext[SessionOperationContext]?.token
        val token = register(job, inherited, expectedOwnerEpoch)
        return try {
            if (inherited == null) {
                withContext(SessionOperationContext(token)) { block(token) }
            } else {
                block(token)
            }
        } finally {
            withContext(NonCancellable) {
                unregister(job, token)
            }
        }
    }

    internal suspend fun <T> withOperationForStamp(
        expectedStamp: SessionOperationStamp,
        block: suspend (SessionOperationToken) -> T
    ): T = withOperationForOwner(expectedStamp.ownerEpoch) { token ->
        if (token.generation != expectedStamp.generation) {
            throw StaleSessionOperationException()
        }
        block(token)
    }

    suspend fun <T> withCommit(
        token: SessionOperationToken,
        block: suspend () -> T
    ): T {
        check(currentCoroutineContext()[SessionCommitContext.Key] == null) {
            "Nested session commit entry is not allowed"
        }
        return commitMutex.withLock {
            stateMutex.withLock {
                if (!admissionOpen ||
                    token.generation != generation ||
                    token.ownerEpoch != ownerEpoch
                ) {
                    throw StaleSessionOperationException()
                }
            }
            withContext(SessionCommitContext) { block() }
        }
    }

    suspend fun <T> mutate(block: suspend () -> T): T =
        mutationMutex.withLock {
            val callerJob = currentCoroutineContext()[Job]
            val jobsToCancel = commitMutex.withLock {
                stateMutex.withLock {
                    admissionOpen = false
                    val oldGeneration = generation
                    generation += 1
                    registeredJobs[oldGeneration]
                        .orEmpty()
                        .keys
                        .filterNot { it === callerJob }
                }
            }
            var mutationSucceeded = false
            try {
                jobsToCancel.forEach { job ->
                    job.cancel(StaleSessionOperationException())
                }
                withContext(NonCancellable) {
                    jobsToCancel.joinAll()
                }
                val result = commitMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    block()
                }
                mutationSucceeded = true
                result
            } finally {
                withContext(NonCancellable) {
                    stateMutex.withLock {
                        if (mutationSucceeded) ownerEpoch += 1
                        admissionOpen = true
                        publishedState.value = SessionOperationStamp(
                            generation = generation,
                            ownerEpoch = ownerEpoch
                        )
                        registeredJobs.keys.removeAll { it < generation }
                    }
                }
            }
        }

    internal suspend fun currentGeneration(): Long = stateMutex.withLock { generation }

    internal fun generationSnapshot(): Long = publishedState.value.generation

    internal fun ownerEpochSnapshot(): Long = publishedState.value.ownerEpoch

    internal suspend fun awaitAdmittedStamp(): SessionOperationStamp {
        val (admittedStamp, publishedBeforeWait) = stateMutex.withLock {
            if (admissionOpen) {
                SessionOperationStamp(generation, ownerEpoch) to null
            } else {
                null to publishedState.value
            }
        }
        if (admittedStamp != null) return admittedStamp
        return publishedState.first { published -> published != publishedBeforeWait }
    }

    internal suspend fun registeredJobCount(): Int = stateMutex.withLock {
        registeredJobs.values.sumOf { it.size }
    }

    private suspend fun register(
        job: Job,
        inherited: SessionOperationToken?,
        expectedOwnerEpoch: Long?
    ): SessionOperationToken = stateMutex.withLock {
        if (!admissionOpen) throw SessionOperationRejectedException()
        if (expectedOwnerEpoch != null && expectedOwnerEpoch != ownerEpoch) {
            throw StaleSessionOperationException()
        }
        val token = inherited ?: SessionOperationToken(generation, ownerEpoch)
        if (token.generation != generation || token.ownerEpoch != ownerEpoch) {
            throw StaleSessionOperationException()
        }
        val jobs = registeredJobs.getOrPut(token.generation) { mutableMapOf() }
        jobs[job] = jobs.getOrElse(job) { 0 } + 1
        token
    }

    private suspend fun unregister(
        job: Job,
        token: SessionOperationToken
    ) = stateMutex.withLock {
        val jobs = registeredJobs[token.generation] ?: return@withLock
        val remaining = jobs.getOrElse(job) { 0 } - 1
        if (remaining > 0) jobs[job] = remaining else jobs.remove(job)
        if (jobs.isEmpty()) registeredJobs.remove(token.generation)
    }
}

private class SessionOperationContext(
    val token: SessionOperationToken
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionOperationContext>
}

private data object SessionCommitContext : AbstractCoroutineContextElement(Key) {
    data object Key : CoroutineContext.Key<SessionCommitContext>
}
