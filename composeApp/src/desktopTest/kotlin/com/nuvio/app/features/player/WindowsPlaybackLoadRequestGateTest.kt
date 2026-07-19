package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPlaybackLoadRequestGateTest {
    @Test
    fun onlyLatestRequestCanCommit() {
        val gate = WindowsPlaybackLoadRequestGate()

        val firstRequest = gate.allocate()
        val latestRequest = gate.allocate()

        assertFalse(gate.isCurrent(firstRequest))
        assertTrue(gate.isCurrent(latestRequest))
    }

    @Test
    fun invalidationRejectsPendingRequest() {
        val gate = WindowsPlaybackLoadRequestGate()

        val request = gate.allocate()
        gate.invalidate()

        assertFalse(gate.isCurrent(request))
    }

    @Test
    fun playbackHeadersAreDecodedFromSourceSwitchPayload() {
        assertEquals(
            mapOf("Referer" to "https://example.com", "User-Agent" to "Kino"),
            parseWindowsPlaybackHeaders(
                "{\"Referer\":\"https://example.com\",\"User-Agent\":\"Kino\"}",
            ),
        )
        assertTrue(parseWindowsPlaybackHeaders("not-json").isEmpty())
    }
}
