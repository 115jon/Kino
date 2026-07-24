package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerEngineTest {
    @Test
    fun playbackHeadersRejectInvalidNamesControlsAndOversizedValues() {
        val sanitized = sanitizePlaybackHeaders(
            mapOf(
                " Accept " to " application/json ",
                "Bad Name" to "value",
                "Bad\u0000Name" to "value",
                "Empty" to "",
                "Control" to "value\nwith-control",
                "C1Control" to "value\u0085with-control",
                "Oversized" to "x".repeat(MaxPlaybackHeaderValueLength + 1),
                "Range" to "bytes=0-1",
            ),
        )

        assertEquals(mapOf("Accept" to "application/json"), sanitized)
    }

    @Test
    fun responseHeadersUseTheSameStrictValidationWithoutRemovingRange() {
        val sanitized = sanitizePlaybackResponseHeaders(
            mapOf(
                "Content-Type" to "video/mp4",
                "Bad:Name" to "value",
                "Bad\u0000Name" to "value",
                "Control" to "value\rwith-control",
                "Oversized" to "x".repeat(MaxPlaybackHeaderValueLength + 1),
                "Range" to "bytes=0-1",
            ),
        )

        assertEquals(
            mapOf(
                "Content-Type" to "video/mp4",
                "Range" to "bytes=0-1",
            ),
            sanitized,
        )
    }

    @Test
    fun playbackHeaderCountAndAggregateStayBounded() {
        val tooMany = sanitizePlaybackHeaders(
            (0..MaxPlaybackHeaderCount).associate { index -> "X-$index" to "value" },
        )
        val tooLarge = sanitizePlaybackHeaders(
            mapOf("X-Large" to "x".repeat(MaxPlaybackHeaderAggregateLength)),
        )

        assertTrue(tooMany.size <= MaxPlaybackHeaderCount)
        assertTrue(tooLarge.isEmpty())
    }
}
