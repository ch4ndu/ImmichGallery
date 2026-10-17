package com.udnahc.immichgallery.domain.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkEndpointClassifierTest {
    @Test
    fun literalAndLanHostsRequireLocalNetworkAccess() = runTest {
        val classifier = LocalNetworkEndpointClassifier { emptyList() }

        assertEquals(EndpointNetworkScope.Local, classifier.classify("http://127.0.0.1:2283"))
        assertEquals(EndpointNetworkScope.Local, classifier.classify("http://192.168.1.20"))
        assertEquals(EndpointNetworkScope.Local, classifier.classify("http://[fd00::10]"))
        assertEquals(EndpointNetworkScope.Local, classifier.classify("http://immich.local"))
        assertEquals(EndpointNetworkScope.Local, classifier.classify("http://immich"))
    }

    @Test
    fun resolvedPrivateHostnameRequiresLocalNetworkAccess() = runTest {
        val classifier = LocalNetworkEndpointClassifier { host ->
            if (host == "photos.example.com") listOf("10.0.0.8") else emptyList()
        }

        assertEquals(EndpointNetworkScope.Local, classifier.classify("https://photos.example.com"))
    }

    @Test
    fun publicAndUnresolvedHostsDoNotTriggerThePrompt() = runTest {
        val publicClassifier = LocalNetworkEndpointClassifier { listOf("203.0.113.42") }
        val unresolvedClassifier = LocalNetworkEndpointClassifier { null }

        assertEquals(EndpointNetworkScope.Public, publicClassifier.classify("https://photos.example.com"))
        assertEquals(EndpointNetworkScope.Unknown, unresolvedClassifier.classify("https://photos.example.com"))
        assertEquals(EndpointNetworkScope.Unknown, publicClassifier.classify("not a server url"))
    }

    @kotlin.concurrent.Volatile
    private var stalledLookupStarted = false

    @kotlin.concurrent.Volatile
    private var releaseStalledLookup = false

    @Test
    fun boundedHostResolverTimesOutPromptlyIgnoresLateCompletionAndReleasesPermit() = runTest {
        stalledLookupStarted = false
        releaseStalledLookup = false
        val resolver = BoundedEndpointHostResolver(
            dispatcher = Dispatchers.Default,
            maxConcurrentLookups = 1
        ) { host ->
            if (host == "stalled.example.com") {
                val workerStart = TimeSource.Monotonic.markNow()
                stalledLookupStarted = true
                while (!releaseStalledLookup && workerStart.elapsedNow() < 5.seconds) {
                    // Simulate a non-cooperative blocking lookup that ignores coroutine cancellation.
                }
                listOf("192.168.1.50")
            } else {
                listOf("10.0.0.8")
            }
        }
        val classifier = LocalNetworkEndpointClassifier(resolver)

        try {
            // 1. Keep virtual time at 0ms by yielding on TestDispatcher until the worker enters blockingResolve,
            // then let virtual time advance to 1,500ms so classification times out while the worker is still stuck.
            val stalledClassification = async {
                classifier.classify("https://stalled.example.com")
            }
            val startupWait = TimeSource.Monotonic.markNow()
            while (!stalledLookupStarted) {
                check(startupWait.elapsedNow() < 5.seconds) {
                    "Timed out waiting for stalled lookup worker to enter blockingResolve"
                }
                yield()
            }
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(EndpointNetworkScope.Unknown, stalledClassification.await())
            assertEquals(1_500L, testScheduler.currentTime)

            // 2. While the single permit is still held by the stuck lookup, a second lookup fails fast.
            assertEquals(null, resolver.resolve("healthy.example.com"))
            assertEquals(
                EndpointNetworkScope.Unknown,
                classifier.classify("https://healthy.example.com")
            )

            // 3. Once the late stalled lookup completes, its result is ignored and the permit is released.
            releaseStalledLookup = true
            val healthyResult = withContext(Dispatchers.Default) {
                val recoveryWait = TimeSource.Monotonic.markNow()
                var resolved: List<String>? = null
                while (resolved == null) {
                    check(recoveryWait.elapsedNow() < 5.seconds) {
                        "Timed out waiting for stalled lookup worker to release permit"
                    }
                    resolved = resolver.resolve("healthy.example.com")
                    if (resolved == null) yield()
                }
                classifier.classify("https://healthy.example.com")
            }
            assertEquals(EndpointNetworkScope.Local, healthyResult)
        } finally {
            releaseStalledLookup = true
        }
    }
}
