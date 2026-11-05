package com.udnahc.immichgallery.data.remote

import com.sun.net.httpserver.HttpServer
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.test.TestSettings
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClientEngineJvmTest {
    @Test
    fun loginWorksWhenServerRejectsHttp2Upgrade() = runTest {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/") { exchange ->
            exchange.use {
                if (exchange.requestHeaders.getFirst("Upgrade") != null) {
                    exchange.sendResponseHeaders(400, -1)
                    return@createContext
                }
                val path = exchange.requestURI.path
                requests += path to exchange.requestHeaders.getFirst("x-api-key")
                val body = if (path == "/api/server/ping") """{"res":"pong"}""" else "[]"
                val bytes = body.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        val client = HttpClientFactory.create(createHttpClientEngine())
        try {
            ImmichApiService(client, ServerConfigRepository(TestSettings())).validateConnection(
                "http://127.0.0.1:${server.address.port}",
                "test-key"
            )
            assertEquals(
                listOf("/api/server/ping" to null, "/api/albums" to "test-key"),
                requests.toList()
            )
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
