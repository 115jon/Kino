package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvSeekJournalTest {
    private var nowMs = 10_000L
    private val journal = MpvSeekJournal { nowMs }

    @Test
    fun forwardPlaybackIsNotARegression() {
        assertNull(journal.observePosition(1L, 60_000L, seeking = false))
        nowMs += 1_000L
        assertNull(journal.observePosition(1L, 61_000L, seeking = false))
    }

    @Test
    fun unexplainedBackwardsJumpIsReported() {
        journal.observePosition(1L, 60_000L, seeking = false)
        nowMs += 1_000L
        val message = assertNotNull(journal.observePosition(1L, 57_000L, seeking = false))
        assertTrue(message.startsWith("position regressed fromMs=60000 toMs=57000 deltaMs=-3000"), message)
        assertTrue(message.endsWith("lastSeek=none"), message)
    }

    @Test
    fun recentSeekExplainsBackwardsJump() {
        journal.observePosition(1L, 60_000L, seeking = false)
        journal.record("relative", "PlayerShortcut.seekBy:1", null, 60_000L)
        nowMs += 1_000L
        assertNull(journal.observePosition(1L, 50_000L, seeking = false))
    }

    @Test
    fun staleSeekDoesNotExplainBackwardsJump() {
        journal.record("absolute", "PlayerTimeline.onScrubFinished:1", 60_000L, 10_000L)
        nowMs += MpvSeekAttributionWindowMs + 1L
        journal.observePosition(1L, 70_000L, seeking = false)
        nowMs += 1_000L
        val message = assertNotNull(journal.observePosition(1L, 66_000L, seeking = false))
        assertTrue(message.contains("lastSeek=absolute lastSeekCause=PlayerTimeline.onScrubFinished:1"), message)
    }

    @Test
    fun smallJitterAndSeekingSamplesAreIgnored() {
        journal.observePosition(1L, 60_000L, seeking = false)
        assertNull(journal.observePosition(1L, 59_000L, seeking = false))
        assertNull(journal.observePosition(1L, 30_000L, seeking = true))
    }

    @Test
    fun newSourceGenerationResetsTheBaseline() {
        journal.observePosition(1L, 60_000L, seeking = false)
        assertNull(journal.observePosition(2L, 0L, seeking = false))
    }

    @Test
    fun callSiteSkipsEnginePlumbing() {
        val stack = arrayOf(
            StackTraceElement("java.lang.Thread", "getStackTrace", null, 1),
            StackTraceElement("com.nuvio.app.features.player.MpvSeekJournal_desktopKt", "mpvSeekCallSite", null, 2),
            StackTraceElement("com.nuvio.app.features.player.MpvEngine", "seekTo", null, 3),
            StackTraceElement("com.nuvio.app.features.player.WindowsPlayerEngineController", "seekTo", null, 4),
            StackTraceElement("com.nuvio.app.features.player.DesktopMediaKeySession\$bind\$boundController\$1", "seekTo", null, 5),
            StackTraceElement("com.nuvio.app.features.player.PlayerScreenRuntimeGestureActionsKt", "seekBy", null, 215),
        )
        assertEquals("PlayerScreenRuntimeGestureActionsKt.seekBy:215", mpvSeekCallSite(stack))
    }

    @Test
    fun callSiteFallsBackToUnknown() {
        assertEquals("unknown", mpvSeekCallSite(arrayOf(StackTraceElement("java.lang.Thread", "run", null, 1))))
    }
}
