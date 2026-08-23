package com.udnahc.immichgallery.di

import coil3.ImageLoader
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
            shutdown(oldLoader)
            deleteClosedCache(oldLoader)
            val replacement = oldRegistration.createReplacement()
            var blockFailure: Throwable? = null
            try {
                block()
            } catch (error: Throwable) {
                blockFailure = error
            }
            try {
                install(replacement)
                registration.value = oldRegistration.copy(current = replacement)
            } catch (installFailure: Throwable) {
                shutdown(replacement)
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
