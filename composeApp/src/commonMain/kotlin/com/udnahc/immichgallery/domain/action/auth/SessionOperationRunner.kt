package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.data.repository.StaleSessionOperationException

interface SessionOperationRunner {
    suspend fun <T> run(block: suspend () -> T): T
    suspend fun <T> runStamped(block: suspend (generation: Long) -> T): T =
        run { block(generationSnapshot()) }
    suspend fun <T> runForOwner(ownerEpoch: Long, block: suspend () -> T): T = run(block)
    suspend fun <T> runStampedForOwner(
        ownerEpoch: Long,
        block: suspend (stamp: SessionOperationStamp) -> T
    ): T = runStamped { generation ->
        block(SessionOperationStamp(generation, ownerEpoch))
    }
    suspend fun <T> runForGeneration(generation: Long, block: suspend () -> T): T
    suspend fun <T> runForStamp(
        stamp: SessionOperationStamp,
        block: suspend () -> T
    ): T = runForGeneration(stamp.generation, block)
    fun generationSnapshot(): Long
    fun ownerEpochSnapshot(): Long = 0L
    suspend fun awaitAdmittedStamp(): SessionOperationStamp =
        SessionOperationStamp(generationSnapshot(), ownerEpochSnapshot())
    fun stampForOwner(ownerEpoch: Long): SessionOperationStamp =
        SessionOperationStamp(generationSnapshot(), ownerEpoch)
}

class RunSessionOperationAction(
    private val sessionStateRepository: SessionStateRepository
) : SessionOperationRunner {
    override suspend fun <T> run(block: suspend () -> T): T =
        sessionStateRepository.withOperation { block() }

    override suspend fun <T> runStamped(
        block: suspend (generation: Long) -> T
    ): T = sessionStateRepository.withOperation { token ->
        block(token.generation)
    }

    override suspend fun <T> runForOwner(
        ownerEpoch: Long,
        block: suspend () -> T
    ): T = sessionStateRepository.withOperationForOwner(ownerEpoch) { block() }

    override suspend fun <T> runStampedForOwner(
        ownerEpoch: Long,
        block: suspend (stamp: SessionOperationStamp) -> T
    ): T = sessionStateRepository.withOperationForOwner(ownerEpoch) { token ->
        block(SessionOperationStamp(token.generation, token.ownerEpoch))
    }

    override suspend fun <T> runForGeneration(
        generation: Long,
        block: suspend () -> T
    ): T = sessionStateRepository.withOperation { token ->
        if (token.generation != generation) throw StaleSessionOperationException()
        block()
    }

    override suspend fun <T> runForStamp(
        stamp: SessionOperationStamp,
        block: suspend () -> T
    ): T = sessionStateRepository.withOperationForOwner(stamp.ownerEpoch) { token ->
        if (token.generation != stamp.generation) throw StaleSessionOperationException()
        block()
    }

    override fun generationSnapshot(): Long = sessionStateRepository.generationSnapshot()
    override fun ownerEpochSnapshot(): Long = sessionStateRepository.ownerEpochSnapshot()
    override suspend fun awaitAdmittedStamp(): SessionOperationStamp =
        sessionStateRepository.awaitAdmittedStamp()
}

internal data object DirectSessionOperationRunner : SessionOperationRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
    override suspend fun <T> runStamped(block: suspend (generation: Long) -> T): T = block(0L)
    override suspend fun <T> runForOwner(ownerEpoch: Long, block: suspend () -> T): T {
        if (ownerEpoch != 0L) throw StaleSessionOperationException()
        return block()
    }
    override suspend fun <T> runStampedForOwner(
        ownerEpoch: Long,
        block: suspend (stamp: SessionOperationStamp) -> T
    ): T {
        if (ownerEpoch != 0L) throw StaleSessionOperationException()
        return block(SessionOperationStamp(0L, 0L))
    }
    override suspend fun <T> runForGeneration(
        generation: Long,
        block: suspend () -> T
    ): T {
        if (generation != 0L) throw StaleSessionOperationException()
        return block()
    }

    override fun generationSnapshot(): Long = 0L
    override fun ownerEpochSnapshot(): Long = 0L
}
