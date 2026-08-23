package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.runSuspendCatching

class ClearServerConfigAction internal constructor(
    private val mutate: suspend (suspend () -> Unit) -> Unit,
    private val rotateImageCache: suspend (suspend () -> Unit) -> Unit,
    private val clearServerState: suspend () -> Unit,
    private val clearConfiguration: () -> Unit
) {
    constructor(
        serverConfigRepository: ServerConfigRepository,
        sessionStateRepository: SessionStateRepository,
        serverScopedImageCache: ServerScopedImageCache,
        serverScopedStateClearer: ServerScopedStateClearer
    ) : this(
        mutate = sessionStateRepository::mutate,
        rotateImageCache = serverScopedImageCache::rotateAndClearAround,
        clearServerState = serverScopedStateClearer::clear,
        clearConfiguration = serverConfigRepository::clear
    )

    suspend operator fun invoke(): Result<Unit> = runSuspendCatching {
        // Keep the active server configuration until every server-scoped cache
        // has cleared. A failure can then be retried without exposing partial
        // data under a different login.
        mutate {
            rotateImageCache {
                clearServerState()
                clearConfiguration()
            }
        }
    }
}
