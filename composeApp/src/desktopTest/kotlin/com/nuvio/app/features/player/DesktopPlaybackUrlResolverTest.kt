package com.nuvio.app.features.player

import java.net.InetAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaybackUrlResolverTest {
    @Test
    fun crossOriginRedirectsKeepOnlyUserAgent() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "Referer" to "https://elfhosted.com/watch",
            "User-Agent" to "Kino",
        )

        assertEquals(
            mapOf("User-Agent" to "Kino"),
            desktopPlaybackHeadersForRedirect(
                headers = headers,
                fromUrl = "https://elfhosted.com/watch",
                toUrl = "https://cdn.example/video.mkv",
            ),
        )
    }

    @Test
    fun sameOriginRedirectsKeepValidatedHeaders() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "Referer" to "https://elfhosted.com/watch",
            "User-Agent" to "Kino",
        )

        assertEquals(
            headers,
            desktopPlaybackHeadersForRedirect(
                headers = headers,
                fromUrl = "https://elfhosted.com/watch",
                toUrl = "https://elfhosted.com/video.mkv",
            ),
        )
    }

    @Test
    fun crossOriginRedirectStaysStrippedWhenReturningToOriginalOrigin() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "Referer" to "https://elfhosted.com/watch",
            "User-Agent" to "Kino",
        )

        val resolved = resolveDesktopPlaybackRedirects(
            url = "https://elfhosted.com/start",
            headers = headers,
            resolveAddresses = { listOf(InetAddress.getByName("93.184.216.34")) },
        ) { uri, _ ->
            when (uri.host) {
                "elfhosted.com" if uri.path == "/start" -> DesktopPlaybackRedirectResponse(302, "https://cdn.example/asset")
                "cdn.example" -> DesktopPlaybackRedirectResponse(302, "https://elfhosted.com/final")
                else -> DesktopPlaybackRedirectResponse(200, null)
            }
        }

        assertEquals("https://cdn.example/asset", resolved.url)
        assertEquals(mapOf("User-Agent" to "Kino"), resolved.headers)
    }

    @Test
    fun sameOriginRedirectResultKeepsHeadersWithTheFinalUrl() {
        val headers = mapOf("Authorization" to "Bearer secret", "User-Agent" to "Kino")

        val resolved = resolveDesktopPlaybackRedirects(
            url = "https://elfhosted.com/start",
            headers = headers,
        ) { uri, _ ->
            if (uri.path == "/start") {
                DesktopPlaybackRedirectResponse(302, "/final")
            } else {
                DesktopPlaybackRedirectResponse(200, null)
            }
        }

        assertEquals("https://elfhosted.com/final", resolved.url)
        assertEquals(headers, resolved.headers)
    }

    @Test
    fun explicitRedirectResolutionStopsAtTheConfiguredBound() {
        val requests = mutableListOf<URI>()
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "Referer" to "https://elfhosted.com/watch",
            "User-Agent" to "Kino",
        )

        val resolved = resolveDesktopPlaybackRedirects(
            url = "https://elfhosted.com/start",
            headers = headers,
            maxRedirects = 2,
        ) { uri, _ ->
            requests += uri
            DesktopPlaybackRedirectResponse(
                statusCode = 302,
                location = "/next-${requests.size}",
            )
        }

        assertEquals("https://elfhosted.com/start", resolved.url)
        assertEquals(mapOf("User-Agent" to "Kino"), resolved.headers)
        assertEquals(3, requests.size)
    }

    @Test
    fun missingLocationFallsBackToOriginalUrlWithSafeHeaders() {
        val resolved = resolveDesktopPlaybackRedirects(
            url = "https://elfhosted.com/start",
            headers = mapOf(
                "Authorization" to "Bearer secret",
                "Cookie" to "session=secret",
                "Referer" to "https://elfhosted.com/watch",
                "User-Agent" to "Kino",
            ),
        ) { _, _ -> DesktopPlaybackRedirectResponse(302, null) }

        assertEquals("https://elfhosted.com/start", resolved.url)
        assertEquals(mapOf("User-Agent" to "Kino"), resolved.headers)
    }

    @Test
    fun resolverExceptionFallsBackToOriginalUrlWithSafeHeaders() {
        val resolved = resolveDesktopPlaybackRedirects(
            url = "https://elfhosted.com/start",
            headers = mapOf(
                "Authorization" to "Bearer secret",
                "Cookie" to "session=secret",
                "User-Agent" to "Kino",
            ),
        ) { _, _ -> error("resolver failed") }

        assertEquals("https://elfhosted.com/start", resolved.url)
        assertEquals(mapOf("User-Agent" to "Kino"), resolved.headers)
    }

    @Test
    fun unsupportedRedirectSchemeFallsBackToOriginalUrlWithSafeHeaders() {
        val resolved = resolveDesktopPlaybackRedirects(
            url = "https://elfhosted.com/start",
            headers = mapOf("Authorization" to "Bearer secret", "User-Agent" to "Kino"),
        ) { _, _ -> DesktopPlaybackRedirectResponse(302, "file:///video.mkv") }

        assertEquals("https://elfhosted.com/start", resolved.url)
        assertEquals(mapOf("User-Agent" to "Kino"), resolved.headers)
    }

    @Test
    fun non2xxTerminalResponsesFallBackWithSafeHeaders() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "Referer" to "https://elfhosted.com/watch",
            "User-Agent" to "Kino",
        )

        listOf(199, 300, 404, 500).forEach { statusCode ->
            val resolved = resolveDesktopPlaybackRedirects(
                url = "https://elfhosted.com/start",
                headers = headers,
            ) { _, _ -> DesktopPlaybackRedirectResponse(statusCode, null) }

            assertEquals("https://elfhosted.com/start", resolved.url)
            assertEquals(mapOf("User-Agent" to "Kino"), resolved.headers)
        }
    }

    @Test
    fun successfulTerminalResponsesKeepFullHeaders() {
        val headers = mapOf("Authorization" to "Bearer secret", "User-Agent" to "Kino")

        listOf(200, 206).forEach { statusCode ->
            val resolved = resolveDesktopPlaybackRedirects(
                url = "https://elfhosted.com/start",
                headers = headers,
            ) { _, _ -> DesktopPlaybackRedirectResponse(statusCode, null) }

            assertEquals("https://elfhosted.com/start", resolved.url)
            assertEquals(headers, resolved.headers)
        }
    }

    @Test
    fun plainHttpPlaybackStripsCredentialHeadersBeforePreflight() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "Cookie2" to "legacy=secret",
            "Proxy-Authorization" to "Basic secret",
            "X-Api-Key" to "secret",
            "X-Auth-Token" to "secret",
            "Referer" to "http://example.com/watch",
            "User-Agent" to "Kino",
        )

        val resolved = resolveDesktopPlaybackRedirects(
            url = "http://media.example/video.mkv",
            headers = headers,
        ) { _, requestHeaders ->
            assertEquals(
                mapOf("User-Agent" to "Kino"),
                requestHeaders,
            )
            DesktopPlaybackRedirectResponse(200, null)
        }

        assertEquals(
            mapOf("User-Agent" to "Kino"),
            resolved.headers,
        )
    }

    @Test
    fun elfhostedHostMatchingRequiresTheRealDomainBoundary() {
        assertTrue(isElfhostedPlaybackHost("ELFHOSTED.COM"))
        assertTrue(isElfhostedPlaybackHost("cdn.elfhosted.com."))
        assertFalse(isElfhostedPlaybackHost("evil-elfhosted.com"))
        assertFalse(isElfhostedPlaybackHost("elfhosted.com.example"))
    }

    @Test
    fun redirectDestinationPolicyRejectsLocalAddressesAndUnknownHosts() {
        assertFalse(
            isSafeDesktopPlaybackRedirectDestination(
                URI("https://localhost/video"),
                resolveAddresses = { listOf(InetAddress.getByName("127.0.0.1")) },
            ),
        )
        assertFalse(
            isSafeDesktopPlaybackRedirectDestination(
                URI("https://private.example/video"),
                resolveAddresses = { listOf(InetAddress.getByName("192.168.1.4")) },
            ),
        )
        assertFalse(
            isSafeDesktopPlaybackRedirectDestination(
                URI("https://carrier.example/video"),
                resolveAddresses = { listOf(InetAddress.getByName("100.100.100.200")) },
            ),
        )
        assertFalse(
            isSafeDesktopPlaybackRedirectDestination(
                URI("https://documentation.example/video"),
                resolveAddresses = { listOf(InetAddress.getByName("2001:db8::1")) },
            ),
        )
        assertFalse(
            isSafeDesktopPlaybackRedirectDestination(
                URI("https://benchmark.example/video"),
                resolveAddresses = { listOf(InetAddress.getByName("198.18.0.1")) },
            ),
        )
        assertFalse(
            isSafeDesktopPlaybackRedirectDestination(
                URI("https://unknown.example/video"),
                resolveAddresses = { emptyList() },
            ),
        )
    }

    @Test
    fun redirectDestinationPolicyAllowsResolvedPublicAddresses() {
        assertTrue(
            isSafeDesktopPlaybackRedirectDestination(
                URI("https://cdn.example/video"),
                resolveAddresses = { listOf(InetAddress.getByName("93.184.216.34")) },
            ),
        )
    }

    @Test
    fun directPlaybackRejectsNonHttpSchemes() {
        assertTrue(
            DesktopPlaybackUrlResolver.resolveUrlIfNeeded("file:///C:/secret.mkv", emptyMap()).failed,
        )
        assertTrue(
            DesktopPlaybackUrlResolver.resolveUrlIfNeeded("smb://server/share/video.mkv", emptyMap()).failed,
        )
    }

    @Test
    fun unsafeRedirectDestinationIsRejectedBeforeItIsRequested() {
        var requestCount = 0
        val resolved = resolveDesktopPlaybackRedirects(
            url = "https://source.example/start",
            headers = emptyMap(),
            resolveAddresses = { host ->
                if (host == "127.0.0.1") {
                    listOf(InetAddress.getByName(host))
                } else {
                    listOf(InetAddress.getByName("93.184.216.34"))
                }
            },
        ) { _, _ ->
            requestCount += 1
            DesktopPlaybackRedirectResponse(302, "http://127.0.0.1/private")
        }

        assertEquals(1, requestCount)
        assertEquals("https://source.example/start", resolved.url)
    }
}
