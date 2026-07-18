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

    private const val ResolvedUrlCacheTtlMs = 5 * 60 * 1000L
    private const val ResolvedUrlCacheMaxEntries = 256

    private data class ResolvedUrlCacheEntry(
        val url: String,
        val expiresAtMs: Long,
    )

    private val resolvedUrlCache = ConcurrentHashMap<String, ResolvedUrlCacheEntry>()
    private val resolvedUrlCacheLock = Any()

    private fun resolvedUrlCacheKey(url: String, headers: Map<String, String>): String = buildString {
        append(url)
        headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
            append('\u0000').append(key).append('=').append(value)
        }
    }

    fun resolveUrlIfNeeded(
        url: String,
        headers: Map<String, String>,
        forceRefresh: Boolean = false,
    ): String {
        val host = runCatching { URI.create(url).host?.lowercase(Locale.ROOT) }.getOrNull()
        if (host.isNullOrBlank() || !host.endsWith("elfhosted.com")) {
            return url
        }
        val cacheKey = resolvedUrlCacheKey(url, headers)
        if (!forceRefresh) {
            val nowMs = System.currentTimeMillis()
            resolvedUrlCache[cacheKey]?.let { entry ->
                if (entry.expiresAtMs > nowMs) return entry.url
                resolvedUrlCache.remove(cacheKey, entry)
            }
        }

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
                synchronized(resolvedUrlCacheLock) {
                    if (resolvedUrlCache.size >= ResolvedUrlCacheMaxEntries && !resolvedUrlCache.containsKey(cacheKey)) {
                        resolvedUrlCache.clear()
                    }
                    resolvedUrlCache[cacheKey] = ResolvedUrlCacheEntry(
                        url = finalUrl,
                        expiresAtMs = System.currentTimeMillis() + ResolvedUrlCacheTtlMs,
                    )
                }
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
