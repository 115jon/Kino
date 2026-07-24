package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val desktopPlaybackUrlResolverLog = Logger.withTag("DesktopPlaybackUrlResolver")

private fun desktopPlaybackUrlResolverTrace(message: String) {
    desktopPlaybackUrlResolverLog.d { boundWindowsMpvLogMessage(message) }
}

internal data class DesktopPlaybackRedirectResponse(
    val statusCode: Int,
    val location: String?,
)

internal data class DesktopPlaybackUrlResolution(
    val url: String,
    val headers: Map<String, String>,
    val failed: Boolean = false,
)

private val desktopPlaybackRedirectStatusCodes = setOf(301, 302, 303, 307, 308)

internal fun isElfhostedPlaybackHost(host: String): Boolean {
    val normalized = host.trimEnd('.').lowercase(Locale.ROOT)
    return normalized == "elfhosted.com" || normalized.endsWith(".elfhosted.com")
}

private fun desktopPlaybackOrigin(url: String): Triple<String, String, Int>? =
    runCatching { URI.create(url) }
        .getOrNull()
        ?.let { uri ->
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@let null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@let null
            val port = when {
                uri.port >= 0 -> uri.port
                scheme == "https" -> 443
                scheme == "http" -> 80
                else -> -1
            }
            Triple(scheme, host, port)
        }

private fun isLocalDesktopPlaybackHost(host: String): Boolean {
    val normalized = host.trimEnd('.').lowercase(Locale.ROOT)
    return normalized == "localhost" ||
        normalized.endsWith(".localhost") ||
        normalized == "local" ||
        normalized.endsWith(".local") ||
        normalized == "broadcasthost" ||
        normalized == "ip6-localhost" ||
        normalized == "ip6-loopback"
}

private fun isUnsafeDesktopPlaybackAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) return true

    val bytes = address.address
    if (bytes.size == 4) return isUnsafeDesktopIpv4(bytes)
    if (bytes.size != 16) return false
    val unsigned = { index: Int -> bytes[index].toInt() and 0xFF }
    if (unsigned(0) in 0xFC..0xFD) return true
    val isIpv4Mapped = (0 until 10).all { unsigned(it) == 0 } && unsigned(10) == 0xFF && unsigned(11) == 0xFF
    if (isIpv4Mapped) return isUnsafeDesktopIpv4(bytes.copyOfRange(12, 16))
    return (unsigned(0) == 0x20 && unsigned(1) == 0x01 && unsigned(2) == 0x0D && unsigned(3) == 0xB8) ||
        (unsigned(0) == 0x20 && unsigned(1) == 0x01 && unsigned(2) == 0x00 && unsigned(3) == 0x00) ||
        (unsigned(0) == 0x20 && unsigned(1) == 0x01 && unsigned(2) == 0x00 && unsigned(3) == 0x02) ||
        (unsigned(0) == 0x20 && unsigned(1) == 0x01 && unsigned(2) == 0x00 && unsigned(3) in 0x10..0x1F) ||
        (unsigned(0) == 0x20 && unsigned(1) == 0x02)
}

private fun isUnsafeDesktopIpv4(bytes: ByteArray): Boolean {
    if (bytes.size != 4) return true
    val unsigned = { index: Int -> bytes[index].toInt() and 0xFF }
    val first = unsigned(0)
    val second = unsigned(1)
    return first == 0 ||
        first == 10 ||
        (first == 100 && second in 64..127) ||
        first == 127 ||
        (first == 169 && second == 254) ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 0) ||
        (first == 192 && second == 168) ||
        (first == 192 && second == 88 && unsigned(2) == 99) ||
        (first == 198 && second in 18..19) ||
        (first == 198 && second == 51 && unsigned(2) == 100) ||
        (first == 203 && second == 0 && unsigned(2) == 113) ||
        first >= 224
}

internal fun isSafeDesktopPlaybackRedirectDestination(
    uri: URI,
    resolveAddresses: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
): Boolean {
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (isLocalDesktopPlaybackHost(host)) return false
    val addresses = runCatching { resolveAddresses(host) }.getOrNull() ?: return false
    return addresses.isNotEmpty() && addresses.none(::isUnsafeDesktopPlaybackAddress)
}

internal fun desktopPlaybackHeadersForRedirect(
    headers: Map<String, String>,
    fromUrl: String,
    toUrl: String,
): Map<String, String> {
    val validatedHeaders = desktopPlaybackHeadersForUrl(fromUrl, headers)
    val fromOrigin = desktopPlaybackOrigin(fromUrl)
    val toOrigin = desktopPlaybackOrigin(toUrl)
    if (fromOrigin != null && fromOrigin == toOrigin) return validatedHeaders
    return validatedHeaders.filterKeys { key -> key.equals("User-Agent", ignoreCase = true) }
}

private fun desktopPlaybackHeadersForUrl(
    url: String,
    headers: Map<String, String>,
): Map<String, String> {
    val validatedHeaders = sanitizePlaybackHeaders(headers)
    if (desktopPlaybackOrigin(url)?.first != "http") return validatedHeaders
    return validatedHeaders.filterKeys { key -> key.equals("User-Agent", ignoreCase = true) }
}

private fun desktopPlaybackFailureHeaders(headers: Map<String, String>): Map<String, String> =
    sanitizePlaybackHeaders(headers).filterKeys { key -> key.equals("User-Agent", ignoreCase = true) }

internal fun desktopPlaybackHeadersForAudioResource(
    videoUrl: String,
    audioUrl: String,
    headers: Map<String, String>,
): Map<String, String> {
    val validatedHeaders = desktopPlaybackHeadersForUrl(videoUrl, headers)
    val videoOrigin = desktopPlaybackOrigin(videoUrl)
    val audioOrigin = desktopPlaybackOrigin(audioUrl)
    if (videoOrigin == null || audioOrigin == null || videoOrigin != audioOrigin) {
        return validatedHeaders.filterKeys { key -> key.equals("User-Agent", ignoreCase = true) }
    }
    return desktopPlaybackHeadersForUrl(audioUrl, validatedHeaders)
}

internal fun resolveDesktopPlaybackRedirects(
    url: String,
    headers: Map<String, String>,
    maxRedirects: Int = 6,
    resolveAddresses: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    request: (URI, Map<String, String>) -> DesktopPlaybackRedirectResponse,
): DesktopPlaybackUrlResolution {
    val failureResolution = DesktopPlaybackUrlResolution(url, desktopPlaybackFailureHeaders(headers), failed = true)
    var currentUri = runCatching { URI.create(url) }.getOrNull() ?: return failureResolution
    var currentHeaders = desktopPlaybackHeadersForUrl(currentUri.toString(), headers)
    var redirectCount = 0
    val redirectLimit = maxRedirects.coerceAtLeast(0)

    while (true) {
        val response = runCatching { request(currentUri, currentHeaders) }.getOrNull() ?: return failureResolution
        if (response.statusCode !in desktopPlaybackRedirectStatusCodes) {
            return if (response.statusCode in 200..299) {
                DesktopPlaybackUrlResolution(currentUri.toString(), currentHeaders)
            } else {
                failureResolution
            }
        }
        if (redirectCount >= redirectLimit) return failureResolution
        val location = response.location?.trim()?.takeIf { it.isNotEmpty() } ?: return failureResolution
        val nextUri = runCatching { currentUri.resolve(location) }.getOrNull() ?: return failureResolution
        if (nextUri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return failureResolution
        if (!isSafeDesktopPlaybackRedirectDestination(nextUri, resolveAddresses)) return failureResolution
        currentHeaders = desktopPlaybackHeadersForRedirect(
            headers = currentHeaders,
            fromUrl = currentUri.toString(),
            toUrl = nextUri.toString(),
        )
        if (desktopPlaybackOrigin(currentUri.toString()) != desktopPlaybackOrigin(nextUri.toString())) {
            return DesktopPlaybackUrlResolution(nextUri.toString(), currentHeaders)
        }
        currentUri = nextUri
        redirectCount += 1
    }
}

internal object DesktopPlaybackUrlResolver {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private const val ResolvedUrlCacheTtlMs = 5 * 60 * 1000L
    private const val ResolvedUrlCacheMaxEntries = 256

    private data class ResolvedUrlCacheEntry(
        val resolution: DesktopPlaybackUrlResolution,
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
    ): DesktopPlaybackUrlResolution {
        val parsedUrl = runCatching { URI.create(url) }.getOrNull()
        val scheme = parsedUrl?.scheme?.lowercase(Locale.ROOT)
        val host = parsedUrl?.host?.lowercase(Locale.ROOT)
        val validatedHeaders = sanitizePlaybackHeaders(headers)
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
            return DesktopPlaybackUrlResolution(url, desktopPlaybackFailureHeaders(validatedHeaders), failed = true)
        }
        if (!isSafeDesktopPlaybackRedirectDestination(parsedUrl)) {
            return DesktopPlaybackUrlResolution(url, desktopPlaybackFailureHeaders(validatedHeaders), failed = true)
        }
        if (!isElfhostedPlaybackHost(host)) {
            return DesktopPlaybackUrlResolution(url, desktopPlaybackHeadersForUrl(url, validatedHeaders))
        }
        val cacheKey = resolvedUrlCacheKey(url, validatedHeaders)
        if (!forceRefresh) {
            val nowMs = System.currentTimeMillis()
            resolvedUrlCache[cacheKey]?.let { entry ->
                if (entry.expiresAtMs > nowMs) return entry.resolution
                resolvedUrlCache.remove(cacheKey, entry)
            }
        }

        return runCatching {
            val resolution = resolveDesktopPlaybackRedirects(
                url = url,
                headers = validatedHeaders,
            ) { requestUri, requestHeaders ->
                val builder = HttpRequest.newBuilder()
                    .uri(requestUri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Range", "bytes=0-0")
                    .GET()
                requestHeaders.forEach { (key, value) -> builder.header(key, value) }
                val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding())
                DesktopPlaybackRedirectResponse(
                    statusCode = response.statusCode(),
                    location = response.headers().firstValue("location").orElse(null),
                )
            }
            desktopPlaybackUrlResolverTrace(
                "resolved sourceHost=$host finalUrl=${redactDesktopPlaybackUrlForLog(resolution.url)} " +
                    "survivingHeaderCount=${resolution.headers.size}"
            )
            if (resolution.url.isNotBlank() && resolution.url != url) {
                synchronized(resolvedUrlCacheLock) {
                    if (resolvedUrlCache.size >= ResolvedUrlCacheMaxEntries && !resolvedUrlCache.containsKey(cacheKey)) {
                        resolvedUrlCache.clear()
                    }
                    resolvedUrlCache[cacheKey] = ResolvedUrlCacheEntry(
                        resolution = resolution,
                        expiresAtMs = System.currentTimeMillis() + ResolvedUrlCacheTtlMs,
                    )
                }
                resolution
            } else {
                resolution
            }
        }.getOrElse { error ->
            desktopPlaybackUrlResolverTrace(
                "resolve failed sourceHost=$host message=${playbackMetadataForLog(error.message)}"
            )
            DesktopPlaybackUrlResolution(url, desktopPlaybackFailureHeaders(validatedHeaders), failed = true)
        }
    }
}
