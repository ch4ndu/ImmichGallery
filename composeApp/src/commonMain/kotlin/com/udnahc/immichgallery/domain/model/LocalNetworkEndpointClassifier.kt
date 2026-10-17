package com.udnahc.immichgallery.domain.model

import io.ktor.http.Url
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface EndpointHostResolver {
    suspend fun resolve(host: String): List<String>?
}

internal class BoundedEndpointHostResolver(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxConcurrentLookups: Int = DEFAULT_MAX_CONCURRENT_LOOKUPS,
    private val blockingResolve: (String) -> List<String>?
) : EndpointHostResolver {
    private val lookupScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val concurrencyGate = Semaphore(maxConcurrentLookups)

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun resolve(host: String): List<String>? {
        if (!concurrencyGate.tryAcquire()) return null
        return suspendCancellableCoroutine { continuation ->
            val job = lookupScope.launch(start = CoroutineStart.ATOMIC) {
                val result = try {
                    if (continuation.isActive) {
                        runCatching { blockingResolve(host) }.getOrNull()
                    } else {
                        null
                    }
                } finally {
                    concurrencyGate.release()
                }
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            continuation.invokeOnCancellation {
                job.cancel()
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_LOOKUPS = 4
    }
}

internal enum class EndpointNetworkScope {
    Local,
    Public,
    Unknown
}

internal class LocalNetworkEndpointClassifier(
    private val hostResolver: EndpointHostResolver
) {
    suspend fun classify(serverUrl: String): EndpointNetworkScope {
        val host = normalizedHost(serverUrl) ?: return EndpointNetworkScope.Unknown
        literalScope(host)?.let { return it }
        if (!host.contains('.')) return EndpointNetworkScope.Local
        if (host.endsWith(".local")) return EndpointNetworkScope.Local

        val resolvedAddresses = withTimeoutOrNull(ENDPOINT_RESOLUTION_TIMEOUT_MILLIS) {
            hostResolver.resolve(host)
        } ?: return EndpointNetworkScope.Unknown
        if (resolvedAddresses.isEmpty()) return EndpointNetworkScope.Unknown

        return if (resolvedAddresses.any { literalScope(it) == EndpointNetworkScope.Local }) {
            EndpointNetworkScope.Local
        } else {
            EndpointNetworkScope.Public
        }
    }

    private fun normalizedHost(serverUrl: String): String? = runCatching {
        val url = Url(serverUrl)
        if (url.protocol.name !in HTTP_PROTOCOLS || !serverUrl.contains("://")) return null
        url.host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun literalScope(host: String): EndpointNetworkScope? {
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == IPV4_PART_COUNT && ipv4Parts.all { it.toIntOrNull() != null }) {
            val octets = ipv4Parts.map { it.toIntOrNull() ?: return EndpointNetworkScope.Unknown }
            if (octets.any { it !in IPV4_OCTET_RANGE }) return EndpointNetworkScope.Unknown
            return if (isLocalIpv4(octets)) EndpointNetworkScope.Local else EndpointNetworkScope.Public
        }

        if (!host.contains(':')) return null
        val normalizedIpv6 = host.substringBefore('%').lowercase()
        val mappedIpv4 = normalizedIpv6.removePrefix("::ffff:")
        if (mappedIpv4 != normalizedIpv6) return literalScope(mappedIpv4)

        return if (
            normalizedIpv6 == "::1" ||
            normalizedIpv6.startsWith("fc") ||
            normalizedIpv6.startsWith("fd") ||
            normalizedIpv6.startsWith("fe8") ||
            normalizedIpv6.startsWith("fe9") ||
            normalizedIpv6.startsWith("fea") ||
            normalizedIpv6.startsWith("feb")
        ) {
            EndpointNetworkScope.Local
        } else {
            EndpointNetworkScope.Public
        }
    }

    private fun isLocalIpv4(octets: List<Int>): Boolean =
        octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 100 && octets[1] in 64..127)

    private companion object {
        const val ENDPOINT_RESOLUTION_TIMEOUT_MILLIS = 1_500L
        const val IPV4_PART_COUNT = 4
        val IPV4_OCTET_RANGE = 0..255
        val HTTP_PROTOCOLS = setOf("http", "https")
    }
}
