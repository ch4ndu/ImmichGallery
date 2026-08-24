package com.udnahc.immichgallery.ui.component

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AuthenticatedMediaUrlTest {
    @Test
    fun appendsApiKeyToUrlWithoutQuery() {
        val authenticated = requireNotNull(
            AuthenticatedMediaUrl.create(
                mediaUrl = "https://photos.example/api/assets/asset/video/playback",
                apiKey = "secret",
            )
        )

        assertEquals("secret", Url(authenticated.url).parameters["apiKey"])
    }

    @Test
    fun preservesExistingQueryAndFragment() {
        val authenticated = requireNotNull(
            AuthenticatedMediaUrl.create(
                mediaUrl = "https://photos.example/media?edited=true&quality=high#viewer",
                apiKey = "secret",
            )
        )
        val parsed = Url(authenticated.url)

        assertEquals("true", parsed.parameters["edited"])
        assertEquals("high", parsed.parameters["quality"])
        assertEquals("viewer", parsed.fragment)
        assertEquals("secret", parsed.parameters["apiKey"])
    }

    @Test
    fun replacesEveryExistingApiKeyWithExactlyOneValue() {
        val authenticated = requireNotNull(
            AuthenticatedMediaUrl.create(
                mediaUrl = "https://photos.example/media?apiKey=old-1&keep=yes&apiKey=old-2",
                apiKey = "new-secret",
            )
        )
        val parameters = Url(authenticated.url).parameters

        assertEquals(listOf("new-secret"), parameters.getAll("apiKey"))
        assertEquals("yes", parameters["keep"])
    }

    @Test
    fun percentEncodesReservedAndUnicodeKeyCharacters() {
        val key = "a b&c=d?#/café🔑"
        val authenticated = requireNotNull(
            AuthenticatedMediaUrl.create(
                mediaUrl = "https://photos.example/media",
                apiKey = key,
            )
        )

        assertEquals(key, Url(authenticated.url).parameters["apiKey"])
        assertFalse(authenticated.url.contains("apiKey=$key"))
        assertFalse(authenticated.url.substringAfter('?').contains("&c=d"))
    }

    @Test
    fun emptyKeyFailsClosed() {
        assertNull(
            AuthenticatedMediaUrl.create(
                mediaUrl = "https://photos.example/media?edited=true",
                apiKey = "",
            )
        )
    }

    @Test
    fun malformedOrRelativeMediaUrlFailsClosed() {
        assertNull(AuthenticatedMediaUrl.create(mediaUrl = "not a media url", apiKey = "secret"))
        assertNull(AuthenticatedMediaUrl.create(mediaUrl = "/api/assets/id", apiKey = "secret"))
    }

    @Test
    fun schemeRelativeMediaUrlFailsClosed() {
        assertNull(
            AuthenticatedMediaUrl.create(
                mediaUrl = "//photos.example/api/assets/id/video/playback",
                apiKey = "secret",
            )
        )
    }

    @Test
    fun nonHttpMediaUrlFailsClosed() {
        assertNull(
            AuthenticatedMediaUrl.create(
                mediaUrl = "ftp://photos.example/api/assets/id/video/playback",
                apiKey = "secret",
            )
        )
    }

    @Test
    fun diagnosticStringNeverContainsRawUrlOrKey() {
        val key = "diagnostic-secret"
        val authenticated = requireNotNull(
            AuthenticatedMediaUrl.create(
                mediaUrl = "https://photos.example/media",
                apiKey = key,
            )
        )
        val diagnostic = authenticated.toString()

        assertEquals("AuthenticatedMediaUrl(<redacted>)", diagnostic)
        assertFalse(diagnostic.contains(key))
        assertFalse(diagnostic.contains("photos.example"))
    }
}
