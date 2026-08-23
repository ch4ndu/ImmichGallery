package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.runSuspendCatching

class ReplaceServerSessionAction internal constructor(
    private val currentConfiguration: () -> ServerConfiguration,
    private val mutate: suspend (suspend () -> Unit) -> Unit,
    private val rotateImageCache: suspend (suspend () -> Unit) -> Unit,
    private val clearServerState: suspend () -> Unit,
    private val replaceConfiguration: (ServerConfiguration) -> Unit
) {
    constructor(
        serverConfigRepository: ServerConfigRepository,
        sessionStateRepository: SessionStateRepository,
        serverScopedImageCache: ServerScopedImageCache,
        serverScopedStateClearer: ServerScopedStateClearer
    ) : this(
        currentConfiguration = {
            val session = serverConfigRepository.currentSession()
            ServerConfiguration(
                serverUrl = session.serverUrl,
                apiKey = session.apiKey
            )
        },
        mutate = sessionStateRepository::mutate,
        rotateImageCache = serverScopedImageCache::rotateAndClearAround,
        clearServerState = serverScopedStateClearer::clear,
        replaceConfiguration = { configuration ->
            serverConfigRepository.replaceSession(
                configuration.serverUrl,
                configuration.apiKey
            )
        }
    )

    suspend operator fun invoke(
        serverUrl: String,
        apiKey: String
    ): Result<Unit> = runSuspendCatching {
        val replacement = ServerConfiguration(serverUrl.trimEnd('/'), apiKey)
        val current = currentConfiguration().normalized()
        if (replacement == current) return@runSuspendCatching
        mutate {
            rotateImageCache {
                clearServerState()
                replaceConfiguration(replacement)
            }
        }
    }
}

internal data class ServerConfiguration(
    val serverUrl: String,
    val apiKey: String
) {
    fun normalized(): ServerConfiguration = copy(serverUrl = serverUrl.trimEnd('/'))
}
