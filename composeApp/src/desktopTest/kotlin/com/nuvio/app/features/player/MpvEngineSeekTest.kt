package com.nuvio.app.features.player

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvEngineSeekTest {
    private val client = FakeMpvClient()
    private val engine = MpvEngine(
        tracePrefix = "test panel",
        dispatcher = ManualMpvDispatcher(),
        clientFactory = { client },
        resolutionContext = Dispatchers.Unconfined,
    )

    @AfterTest
    fun tearDown() {
        engine.shutdown()
    }

    @Test
    fun `seeks use locale independent seconds and record their call site`() {
        engine.start(WidOutput(1L))
        client.properties["time-pos"] = "42.000"
        client.clearCalls()
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            engine.seekTo(12_500L)
            engine.seekBy(-10_000L)
            engine.seekToKeyframe(90_250L)
        } finally {
            Locale.setDefault(previous)
        }

        assertEquals(
            listOf(
                "command seek 12.500 absolute",
                "command seek -10.000 relative+exact",
                "command seek 90.250 absolute keyframes",
            ),
            client.snapshotCalls(),
        )
        val seek = engine.seekJournal.lastSeek!!
        assertEquals("keyframe", seek.kind)
        assertEquals(90_250L, seek.targetMs)
        assertEquals(42_000L, seek.fromMs)
        assertTrue(seek.cause.startsWith("MpvEngineSeekTest."), seek.cause)
    }

    @Test
    fun `pause requests only change mpv when needed`() {
        engine.start(WidOutput(1L))
        client.properties["pause"] = "no"
        client.clearCalls()

        engine.setPaused(false)
        engine.setPaused(true)

        assertEquals(listOf("set pause=no", "set pause=yes"), client.snapshotCalls())
    }
}
