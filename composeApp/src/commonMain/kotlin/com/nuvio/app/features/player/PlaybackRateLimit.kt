package com.nuvio.app.features.player

import com.nuvio.app.core.network.retryAfterDelayMillis
import kotlinx.atomicfu.atomic

internal const val PlaybackRateLimitedMessage = "Provider is rate-limiting (429). Try again in a minute."

private val rateLimitedPlaybackLogPattern = Regex("HTTP error 429(?!\\d)", RegexOption.IGNORE_CASE)

internal fun isPlaybackRateLimitedMessage(message: String?): Boolean = message == PlaybackRateLimitedMessage

internal fun isRateLimitedPlaybackLog(text: String?): Boolean =
    !text.isNullOrBlank() && rateLimitedPlaybackLogPattern.containsMatchIn(text)

internal fun playbackRateLimitKey(url: String?): String? {
    val value = url?.trim().orEmpty()
    val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    return value.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .substringBefore(':')
        .trimEnd('.')
        .lowercase()
        .takeIf { it.isNotBlank() }
}

internal class PlaybackRateLimitCooldown(
    private val baseDelayMs: Long = 15_000L,
    private val maxBackoffMs: Long = 120_000L,
    private val maxRetryAfterMs: Long = 600_000L,
) {
    private data class Entry(val strikes: Int, val untilEpochMs: Long)

    private val entries = atomic(emptyMap<String, Entry>())

    fun record(key: String, nowEpochMs: Long, retryAfterHeader: String? = null): Long {
        while (true) {
            val current = entries.value
            val previous = current[key]?.takeIf { nowEpochMs - it.untilEpochMs < maxBackoffMs }
            val strikes = (previous?.strikes ?: 0) + 1
            val backoffMs = (baseDelayMs shl (strikes - 1).coerceAtMost(8)).coerceAtMost(maxBackoffMs)
            val delayMs = if (retryAfterHeader.isNullOrBlank()) {
                backoffMs
            } else {
                retryAfterDelayMillis(retryAfterHeader, nowEpochMs, backoffMs).coerceIn(0L, maxRetryAfterMs)
            }
            val untilEpochMs = maxOf(nowEpochMs + delayMs, previous?.untilEpochMs ?: 0L)
            val updated = current + (key to Entry(strikes, untilEpochMs))
            if (entries.compareAndSet(current, updated)) return untilEpochMs - nowEpochMs
        }
    }

    fun remainingMs(key: String?, nowEpochMs: Long): Long {
        if (key == null) return 0L
        val until = entries.value[key]?.untilEpochMs ?: return 0L
        return (until - nowEpochMs).coerceAtLeast(0L)
    }

    fun clear() {
        entries.value = emptyMap()
    }
}

internal val PlaybackRateLimits = PlaybackRateLimitCooldown()
