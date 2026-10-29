package com.udnahc.immichgallery.di

import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.udnahc.immichgallery.domain.action.auth.ServerScopedImageCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CoilImageCacheController<T : Any> internal constructor(
    private val shutdown: (T) -> Unit,
    private val deleteClosedCache: (T) -> Unit,
    private val install: (T) -> Unit
) : ServerScopedImageCache {
    private val lifecycleMutex = Mutex()
    private val registration = MutableStateFlow<Registration<T>?>(null)

    fun registerInitial(
        createInitial: () -> T,
        createReplacement: () -> T
    ): T {
        registration.value?.let { return it.current }
        val candidate = createInitial()
        val candidateRegistration = Registration(candidate, createReplacement)
        if (registration.compareAndSet(null, candidateRegistration)) {
            try {
                install(candidate)
            } catch (error: Throwable) {
                registration.compareAndSet(candidateRegistration, null)
                shutdown(candidate)
                throw error
            }
        } else {
            shutdown(candidate)
        }
        return currentImageLoader()
    }

    fun currentImageLoader(): T = checkNotNull(registration.value?.current) {
        "The application ImageLoader has not been registered"
    }

    override suspend fun rotateAndClearAround(block: suspend () -> Unit) {
        lifecycleMutex.withLock {
            val oldRegistration = checkNotNull(registration.value)
            val oldLoader = oldRegistration.current
            // A failed shutdown cannot establish that the disk cache is closed.
            // Keep configuration untouched and do not open a competing cache.
            shutdown(oldLoader)
            var replacement: T? = null
            var blockFailure: Throwable? = null
            try {
                deleteClosedCache(oldLoader)
                replacement = oldRegistration.createReplacement()
                block()
            } catch (error: Throwable) {
                blockFailure = error
            }
            // Deletion or initial creation can fail before the session block.
            // Recover only after shutdown and without mutating configuration;
            // failure to create the recovery loader remains explicitly failed.
            val nextLoader = replacement ?: try {
                oldRegistration.createReplacement()
            } catch (recoveryFailure: Throwable) {
                val failure = blockFailure ?: throw recoveryFailure
                failure.addSuppressed(recoveryFailure)
                throw failure
            }
            try {
                install(nextLoader)
                registration.value = oldRegistration.copy(current = nextLoader)
            } catch (installFailure: Throwable) {
                try {
                    shutdown(nextLoader)
                } catch (shutdownFailure: Throwable) {
                    installFailure.addSuppressed(shutdownFailure)
                }
                blockFailure?.let { failure ->
                    failure.addSuppressed(installFailure)
                    throw failure
                }
                throw installFailure
            }
            blockFailure?.let { throw it }
        }
    }

    suspend fun close() {
        lifecycleMutex.withLock {
            val currentRegistration = registration.value ?: return@withLock
            registration.value = null
            shutdown(currentRegistration.current)
        }
    }

    private data class Registration<T : Any>(
        val current: T,
        val createReplacement: () -> T
    )
}

@OptIn(DelicateCoilApi::class)
fun createCoilImageCacheController(): CoilImageCacheController<ManagedImageLoader> =
    CoilImageCacheController(
        shutdown = ManagedImageLoader::shutdown,
        deleteClosedCache = { managedLoader ->
            managedLoader.imageLoader.diskCache?.let { diskCache ->
                diskCache.fileSystem.deleteRecursively(diskCache.directory, mustExist = false)
            }
        },
        install = { managedLoader -> SingletonImageLoader.setUnsafe(managedLoader.imageLoader) }
    )
