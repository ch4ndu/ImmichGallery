package com.udnahc.immichgallery.di

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.test.TestSettings
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ImageLoaderSecurityPolicyTest {
    @Test
    fun imageClientDisablesRedirectsAndAuthenticatesOnlyExactOrigin() {
        val configuration = ServerConfigRepository(TestSettings()).apply {
            replaceSession("https://immich.example:443/base", "image-key")
        }
        val headers = mutableMapOf<String, String?>()
        val base = buildAndroidImageClient(configuration)
        val client = base.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                headers[request.url.host] = request.header("x-api-key")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("image".toResponseBody())
                    .build()
            }
            .build()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        client.newCall(Request.Builder().url("https://immich.example/image").build()).execute().close()
        client.newCall(Request.Builder().url("https://cdn.example/image").build()).execute().close()

        assertEquals("image-key", headers["immich.example"])
        assertNull(headers["cdn.example"])
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
