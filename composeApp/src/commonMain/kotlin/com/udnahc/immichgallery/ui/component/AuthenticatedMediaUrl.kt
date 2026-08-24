package com.udnahc.immichgallery.ui.component

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol

/**
 * A media URL carrying query authentication.
 *
 * Keep the encoded URL behind an explicitly named property so ordinary
 * diagnostics cannot reveal the credential through [toString].
 */
internal class AuthenticatedMediaUrl private constructor(
    val url: String,
) {
    override fun toString(): String = "AuthenticatedMediaUrl(<redacted>)"

    companion object {
        /**
         * Returns null for an empty key or a malformed, scheme-relative, or
         * non-HTTP(S) media URL. An empty key deliberately fails closed instead
         * of starting an unauthenticated request that cannot succeed against
         * Immich.
         */
        fun create(mediaUrl: String, apiKey: String): AuthenticatedMediaUrl? {
            if (apiKey.isEmpty()) return null

            return try {
                val builder = URLBuilder(mediaUrl)
                val protocol = builder.protocolOrNull
                if (
                    builder.host.isEmpty() ||
                    (protocol != URLProtocol.HTTP && protocol != URLProtocol.HTTPS)
                ) {
                    return null
                }

                builder.parameters.remove(API_KEY_QUERY_PARAMETER)
                builder.parameters.append(API_KEY_QUERY_PARAMETER, apiKey)
                AuthenticatedMediaUrl(builder.buildString())
            } catch (_: Exception) {
                null
            }
        }
    }
}

private const val API_KEY_QUERY_PARAMETER = "apiKey"
