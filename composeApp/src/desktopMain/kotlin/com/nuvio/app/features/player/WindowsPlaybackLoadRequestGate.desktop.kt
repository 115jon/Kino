package com.nuvio.app.features.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val MaxWindowsPlaybackHeadersJsonLength = 32_768

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
    val videoHeaders: Map<String, String>,
    val audioHeaders: Map<String, String>,
    val failed: Boolean = false,
)

internal fun shouldAbortWindowsPlaybackLoad(resolved: WindowsResolvedPlaybackUrls): Boolean = resolved.failed

internal enum class WindowsAudioAttachmentResult {
    Success,
    NotCurrent,
    HeaderApplyFailed,
    AudioAddFailed,
    HeaderRestoreFailed,
}

internal fun parseWindowsPlaybackHeaders(headersJson: String?): Map<String, String> {
    if (headersJson.isNullOrBlank()) return emptyMap()
    if (headersJson.length > MaxWindowsPlaybackHeadersJsonLength) return emptyMap()
    val jsonObject = runCatching { Json.parseToJsonElement(headersJson).jsonObject }.getOrNull() ?: return emptyMap()
    val parsedHeaders = jsonObject.mapNotNull { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        key to primitive.content
    }.toMap()
    return sanitizePlaybackHeaders(parsedHeaders)
}

internal fun resolveWindowsPlaybackUrls(
    url: String,
    audioUrl: String?,
    headers: Map<String, String>,
    forceRefresh: Boolean = false,
    urlResolver: (String, Map<String, String>, Boolean) -> DesktopPlaybackUrlResolution = { candidate, candidateHeaders, refresh ->
        DesktopPlaybackUrlResolver.resolveUrlIfNeeded(candidate, candidateHeaders, refresh)
    },
): WindowsResolvedPlaybackUrls {
    val sanitizedHeaders = sanitizePlaybackHeaders(headers)
    val videoResolution = urlResolver(url, sanitizedHeaders, forceRefresh)
    val audioResolution = audioUrl?.let {
        if (it.isBlank()) return@let null
        val audioHeaders = desktopPlaybackHeadersForAudioResource(
            videoUrl = videoResolution.url,
            audioUrl = it,
            headers = videoResolution.headers,
        )
        urlResolver(it, audioHeaders, forceRefresh)
    }
    val resolvedAudioHeaders = if (audioUrl.isNullOrBlank()) {
        emptyMap()
    } else {
        audioResolution?.let {
            desktopPlaybackHeadersForAudioResource(
                videoUrl = videoResolution.url,
                audioUrl = it.url,
                headers = it.headers,
            )
        }.orEmpty()
    }
    return WindowsResolvedPlaybackUrls(
        url = videoResolution.url,
        audioUrl = audioResolution?.url,
        videoHeaders = videoResolution.headers,
        audioHeaders = resolvedAudioHeaders,
        failed = videoResolution.failed || audioResolution?.failed == true,
    )
}

internal fun encodeWindowsPlaybackHeaders(headers: Map<String, String>): String = buildString {
    sanitizePlaybackHeaders(headers).entries.forEach { entry ->
        val encoded = "${entry.key}: ${entry.value.replace("\\", "\\\\").replace(",", "\\,")}"
        val separatorLength = if (length == 0) 0 else 1
        if (length + separatorLength + encoded.length > MaxPlaybackHeaderAggregateLength) return@forEach
        if (separatorLength > 0) append(',')
        append(encoded)
    }
}

internal fun withWindowsAudioHeadersTemporarily(
    videoHeaders: Map<String, String>,
    audioHeaders: Map<String, String>,
    setHeaders: (String) -> Int,
    audioAdd: () -> Int,
): WindowsAudioAttachmentResult {
    var result = WindowsAudioAttachmentResult.HeaderApplyFailed
    try {
        if (runCatching { setHeaders(encodeWindowsPlaybackHeaders(audioHeaders)) }.getOrDefault(-1) >= 0) {
            result = try {
                if (audioAdd() >= 0) {
                    WindowsAudioAttachmentResult.Success
                } else {
                    WindowsAudioAttachmentResult.AudioAddFailed
                }
            } catch (_: Throwable) {
                WindowsAudioAttachmentResult.AudioAddFailed
            }
        }
    } finally {
        if (runCatching { setHeaders(encodeWindowsPlaybackHeaders(videoHeaders)) }.getOrDefault(-1) < 0) {
            result = WindowsAudioAttachmentResult.HeaderRestoreFailed
        }
    }
    return result
}

internal fun withWindowsAudioAddIfCurrent(
    lock: ReentrantLock,
    isCurrent: () -> Boolean,
    videoHeaders: Map<String, String>,
    audioHeaders: Map<String, String>,
    setHeaders: (String) -> Int,
    audioAdd: () -> Int,
): WindowsAudioAttachmentResult = lock.withLock {
    if (!isCurrent()) return@withLock WindowsAudioAttachmentResult.NotCurrent
    return withWindowsAudioHeadersTemporarily(
        videoHeaders = videoHeaders,
        audioHeaders = audioHeaders,
        setHeaders = setHeaders,
        audioAdd = audioAdd,
    )
}
