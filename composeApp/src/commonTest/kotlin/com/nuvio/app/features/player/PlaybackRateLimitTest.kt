package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRateLimitTest {
    @Test
    fun `detects 429 in ffmpeg log lines only`() {
        assertTrue(isRateLimitedPlaybackLog("https: HTTP error 429 Too Many Requests"))
        assertFalse(isRateLimitedPlaybackLog("https: HTTP error 4290"))
        assertFalse(isRateLimitedPlaybackLog("https: HTTP error 403 Forbidden"))
        assertFalse(isRateLimitedPlaybackLog("sourceBitrate=4291213"))
        assertFalse(isRateLimitedPlaybackLog(null))
    }

    @Test
    fun `rate limit key is the lowercase host of http urls`() {
        assertEquals("torrentio.strem.fun", playbackRateLimitKey("https://Torrentio.Strem.Fun/resolve/torbox/key/hash/null/0/file.mkv"))
        assertEquals("nexus-236.cnam.tb-cdn.io", playbackRateLimitKey("https://nexus-236.cnam.tb-cdn.io:443/dl/file?token=x"))
        assertEquals("cdn.example", playbackRateLimitKey("http://user:pass@cdn.example/a"))
        assertNull(playbackRateLimitKey("magnet:?xt=urn:btih:abc"))
        assertNull(playbackRateLimitKey("file:///C:/video.mkv"))
        assertNull(playbackRateLimitKey(null))
    }

    @Test
    fun `cooldown backs off exponentially and caps`() {
        val cooldown = PlaybackRateLimitCooldown(baseDelayMs = 15_000L, maxBackoffMs = 120_000L)
        var now = 1_000_000L

        assertEquals(15_000L, cooldown.record("host", now))
        now += 16_000L
        assertEquals(30_000L, cooldown.record("host", now))
        now += 31_000L
        assertEquals(60_000L, cooldown.record("host", now))
        now += 61_000L
        assertEquals(120_000L, cooldown.record("host", now))
        now += 121_000L
        assertEquals(120_000L, cooldown.record("host", now))
    }

    @Test
    fun `cooldown strikes reset after a quiet period`() {
        val cooldown = PlaybackRateLimitCooldown(baseDelayMs = 15_000L, maxBackoffMs = 120_000L)

        cooldown.record("host", 0L)
        cooldown.record("host", 20_000L)

        assertEquals(15_000L, cooldown.record("host", 50_000L + 120_000L))
    }

    @Test
    fun `cooldown honours retry after seconds over backoff`() {
        val cooldown = PlaybackRateLimitCooldown(baseDelayMs = 15_000L, maxRetryAfterMs = 600_000L)

        assertEquals(90_000L, cooldown.record("host", 0L, retryAfterHeader = "90"))
        assertEquals(10_000L, cooldown.remainingMs("host", 80_000L))
        assertEquals(0L, cooldown.remainingMs("host", 95_000L))
    }

    @Test
    fun `cooldown caps an excessive retry after`() {
        val cooldown = PlaybackRateLimitCooldown(maxRetryAfterMs = 600_000L)

        assertEquals(600_000L, cooldown.record("host", 0L, retryAfterHeader = "86400"))
    }

    @Test
    fun `cooldown is scoped per host`() {
        val cooldown = PlaybackRateLimitCooldown()

        cooldown.record("torrentio.strem.fun", 0L)

        assertTrue(cooldown.remainingMs("torrentio.strem.fun", 1_000L) > 0L)
        assertEquals(0L, cooldown.remainingMs("comet.elfhosted.com", 1_000L))
        assertEquals(0L, cooldown.remainingMs(null, 1_000L))
    }
}
