package com.udnahc.immichgallery.data.remote

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import java.net.http.HttpClient

actual fun createHttpClientEngine(): HttpClientEngine = Java.create {
    // Some self-hosted servers close connections on Java's default h2c upgrade.
    protocolVersion = HttpClient.Version.HTTP_1_1
}
