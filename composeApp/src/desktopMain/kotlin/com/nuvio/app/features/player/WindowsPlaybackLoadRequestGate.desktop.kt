package com.nuvio.app.features.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicLong

internal class WindowsPlaybackLoadRequestGate {
    private val generation = AtomicLong(0L)

    fun allocate(): Long = generation.incrementAndGet()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(candidate: Long): Boolean = generation.get() == candidate
}

internal data class WindowsResolvedPlaybackUrls(
    val url: String,
    val audioUrl: String?,
    val headers: Map<String, String>,
)

internal fun parseWindowsPlaybackHeaders(headersJson: String?): Map<String, String> {
    if (headersJson.isNullOrBlank()) return emptyMap()
    val jsonObject = runCatching { Json.parseToJsonElement(headersJson).jsonObject }.getOrNull() ?: return emptyMap()
    return jsonObject.mapNotNull { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        key to primitive.content
    }.toMap()
}

internal fun resolveWindowsPlaybackUrls(
    url: String,
    audioUrl: String?,
    headers: Map<String, String>,
    forceRefresh: Boolean = false,
): WindowsResolvedPlaybackUrls {
    val resolvedUrl = DesktopPlaybackUrlResolver.resolveUrlIfNeeded(url, headers, forceRefresh)
    val resolvedAudioUrl = audioUrl?.let {
        DesktopPlaybackUrlResolver.resolveUrlIfNeeded(it, emptyMap(), forceRefresh)
    }
    return WindowsResolvedPlaybackUrls(
        url = resolvedUrl,
        audioUrl = resolvedAudioUrl,
        headers = if (resolvedUrl != url) emptyMap() else headers,
    )
}
