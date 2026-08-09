package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientFactory {
    fun create(
        engine: HttpClientEngine,
        serverConfigRepository: ServerConfigRepository
    ): HttpClient {
        return HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                })
            }
            install(Logging) {
                level = LogLevel.NONE
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            defaultRequest {
                val apiKey = serverConfigRepository.getApiKey()
                if (apiKey.isNotBlank()) {
                    header("x-api-key", apiKey)
                }
            }
        }
    }

    internal const val REQUEST_TIMEOUT_MS = 60_000L
    internal const val CONNECT_TIMEOUT_MS = 15_000L
    internal const val SOCKET_TIMEOUT_MS = 60_000L
}
