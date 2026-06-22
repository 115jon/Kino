package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val desktopPlaybackUrlResolverLog = Logger.withTag("DesktopPlaybackUrlResolver")

private fun desktopPlaybackUrlResolverTrace(message: String) {
    desktopPlaybackUrlResolverLog.d { message }
}

internal object DesktopPlaybackUrlResolver {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val resolvedUrlCache = ConcurrentHashMap<String, String>()

    fun resolveUrlIfNeeded(url: String, headers: Map<String, String>): String {
        val host = runCatching { URI.create(url).host?.lowercase(Locale.ROOT) }.getOrNull()
        if (host.isNullOrBlank() || !host.endsWith("elfhosted.com")) {
            return url
        }
        resolvedUrlCache[url]?.let { return it }

        return runCatching {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Range", "bytes=0-0")
                .GET()
            headers.forEach { (key, value) ->
                if (!key.equals("Range", ignoreCase = true) && key.isNotBlank() && value.isNotBlank()) {
                    builder.header(key, value)
                }
            }
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding())
            val finalUrl = response.uri().toString()
            desktopPlaybackUrlResolverTrace(
                "resolved sourceHost=$host status=${response.statusCode()} finalUrl=${finalUrl.take(240)}"
            )
            if (finalUrl.isNotBlank() && finalUrl != url) {
                resolvedUrlCache[url] = finalUrl
                finalUrl
            } else {
                url
            }
        }.getOrElse { error ->
            desktopPlaybackUrlResolverTrace(
                "resolve failed sourceHost=$host message=${error.message}"
            )
            url
        }
    }
}
