package com.udnahc.immichgallery.data.remote

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

actual fun createHttpClientEngine(): HttpClientEngine = Darwin.create {
    configureSession {
        HTTPMaximumConnectionsPerHost = 10
        timeoutIntervalForRequest = 30.0
        timeoutIntervalForResource = 300.0
    }
}
