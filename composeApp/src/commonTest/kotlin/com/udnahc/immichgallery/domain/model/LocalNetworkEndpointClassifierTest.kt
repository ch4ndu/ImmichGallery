package com.udnahc.immichgallery.domain.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
