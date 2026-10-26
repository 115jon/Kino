package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvEngineStateTest {
    private fun diagnostics(
        pixelFormat: String? = "yuv420p",
        primaries: String? = "bt.709",
        transfer: String? = "bt.1886",
        dolbyVision: String? = null,
    ) = MpvVideoDiagnostics(
        codec = "hevc",
        decoder = "hevc",
        hardwareDecoder = null,
        format = null,
        pixelFormat = pixelFormat,
        hardwarePixelFormat = null,
        primaries = primaries,
        transfer = transfer,
        matrix = "bt.709",
        levels = "limited",
        signalPeak = null,
        dolbyVision = dolbyVision,
    )

    @Test
    fun `pipeline filters match each compatibility mode`() {
        assertEquals("", windowsVideoPipelineFilter(DesktopVideoPipelineMode.Standard))
        assertEquals(WindowsHdrCompatibilityFilter, windowsVideoPipelineFilter(DesktopVideoPipelineMode.HdrCompatibility))
        assertEquals(
            WindowsDolbyVisionCompatibilityFilter,
            windowsVideoPipelineFilter(DesktopVideoPipelineMode.DolbyVisionCompatibility),
        )
        assertTrue("apply_dolbyvision=1" in WindowsDolbyVisionCompatibilityFilter)
    }

    @Test
    fun `sdr video keeps the plain pipeline`() {
        val plan = planWindowsVideoPipeline(diagnostics(), MpvPlaybackState())

        assertEquals(DesktopVideoPipelineMode.Standard, plan?.decision?.mode)
        assertEquals("", plan?.filterSpec)
    }

    @Test
    fun `pq video selects the hdr libplacebo filter`() {
        val plan = planWindowsVideoPipeline(
            diagnostics(pixelFormat = "p010", primaries = "bt.2020", transfer = "pq"),
            MpvPlaybackState(),
        )

        assertEquals(DesktopVideoPipelineMode.HdrCompatibility, plan?.decision?.mode)
        assertEquals(WindowsHdrCompatibilityFilter, plan?.filterSpec)
    }

    @Test
    fun `dolby vision metadata selects the dolby vision filter`() {
        val plan = planWindowsVideoPipeline(diagnostics(dolbyVision = "dvhe.08"), MpvPlaybackState())

        assertEquals(DesktopVideoPipelineMode.DolbyVisionCompatibility, plan?.decision?.mode)
        assertEquals(WindowsDolbyVisionCompatibilityFilter, plan?.filterSpec)
    }

    @Test
    fun `stream profile dolby vision wins over sdr video params`() {
        val state = MpvPlaybackState().apply { streamHasDolbyVision = true }

        val plan = planWindowsVideoPipeline(diagnostics(), state)

        assertEquals(DesktopVideoPipelineMode.DolbyVisionCompatibility, plan?.decision?.mode)
    }

    @Test
    fun `pipeline is not planned without video params or for an unchanged signature`() {
        val state = MpvPlaybackState()
        assertNull(planWindowsVideoPipeline(diagnostics(pixelFormat = null), state))

        val first = planWindowsVideoPipeline(diagnostics(), state)
        state.lastVideoDiagnosticsSignature = first?.signature

        assertNull(planWindowsVideoPipeline(diagnostics(), state))
    }

    @Test
    fun `configuring the pipeline writes vf once per signature`() {
        val client = FakeMpvClient()
        client.properties["video-params/pixelformat"] = "p010"
        client.properties["video-params/primaries"] = "bt.2020"
        client.properties["video-params/gamma"] = "pq"
        val state = MpvPlaybackState()

        configureWindowsVideoPipeline(client, state, "test")
        configureWindowsVideoPipeline(client, state, "test")

        assertEquals(listOf("set vf=$WindowsHdrCompatibilityFilter"), client.snapshotCalls())
        assertEquals(DesktopVideoPipelineMode.HdrCompatibility, state.videoPipelineMode)
        resetWindowsVideoPipeline(state)
        assertNull(state.lastVideoDiagnosticsSignature)
        assertEquals(DesktopVideoPipelineMode.Standard, state.videoPipelineMode)
    }

    @Test
    fun `only the originally observed properties wake the player state`() {
        assertTrue(mpvPropertyChangeNotifiesState("pause"))
        assertTrue(mpvPropertyChangeNotifiesState("track-list/count"))
        assertTrue(mpvPropertyChangeNotifiesState(""))
        assertFalse(mpvPropertyChangeNotifiesState("time-pos"))
        assertFalse(mpvPropertyChangeNotifiesState("vo-configured"))
        assertEquals(10, mpvObservedProperties.count { it.notifiesState })
    }

    @Test
    fun `property cache reads typed values the way the poll used strings`() {
        val cache = MpvPropertyCache()
        cache.update("vo-configured", true)
        cache.update("video-params/w", 1920L)
        cache.update("time-pos", 12.5)
        cache.update("path", "https://cdn.example/video.mkv")

        assertEquals("yes", cache.flagString("vo-configured"))
        assertEquals(1920L, cache.long("video-params/w"))
        assertEquals(12.5, cache.double("time-pos"))
        assertEquals("https://cdn.example/video.mkv", cache.string("path"))

        cache.update("path", null)
        cache.update("vo-configured", false)

        assertNull(cache.string("path"))
        assertEquals("no", cache.flagString("vo-configured"))
        assertFalse(cache.flag("pause"))
    }

    @Test
    fun `event pump applies property changes and start file without notifying for silent ones`() {
        val client = FakeMpvClient()
        val engine = MpvEngine("test panel", ManualMpvDispatcher(), { client }, resolutionContext = Dispatchers.Unconfined)
        val notified = CountDownLatch(1)
        engine.onStateChanged = { notified.countDown() }
        engine.start(WidOutput(1L))

        client.events += MpvClientEvent.PropertyChange(MPV_EVENT_PROPERTY_CHANGE, 0, "time-pos", 3.0)
        client.events += MpvClientEvent.PropertyChange(MPV_EVENT_PROPERTY_CHANGE, 0, "pause", true)
        client.events += MpvClientEvent.StartFile(MPV_EVENT_START_FILE, 0, 4L)
        client.wakeup?.invoke()

        assertTrue(notified.await(5, TimeUnit.SECONDS))
        assertEquals(3.0, engine.properties.double("time-pos"))
        assertTrue(engine.properties.flag("pause"))
        assertEquals(4L, engine.playback.activePlaylistEntryId)
        assertEquals(0L, engine.playback.activePlaylistEntryGeneration)
        engine.shutdown()
    }

    @Test
    fun `poll builds the snapshot from observed properties`() {
        val client = FakeMpvClient()
        val engine = MpvEngine("test panel", ManualMpvDispatcher(), { client }, resolutionContext = Dispatchers.Unconfined)
        engine.start(WidOutput(1L))
        engine.properties.update("path", "https://cdn.example/video.mkv")
        engine.properties.update("time-pos", 61.0)
        engine.properties.update("duration", 120.0)
        engine.properties.update("demuxer-cache-time", 9.0)
        engine.properties.update("speed", 1.25)
        engine.properties.update("volume", 40.0)
        engine.properties.update("pause", false)
        engine.properties.update("core-idle", false)
        engine.properties.update("vo-configured", true)
        engine.properties.update("video-params/w", 1920L)
        engine.properties.update("video-params/h", 1080L)
        engine.playback.activePlaylistEntryGeneration = 0L
        engine.playback.lastTrackPollEpochMs = System.currentTimeMillis()

        val outcome = engine.pollPlayback(pollGeneration = 0L, pollLoadRequestGeneration = null)

        val snapshot = outcome?.result?.snapshot
        assertEquals(61_000L, snapshot?.positionMs)
        assertEquals(120_000L, snapshot?.durationMs)
        assertEquals(70_000L, snapshot?.bufferedPositionMs)
        assertEquals(1.25f, snapshot?.playbackSpeed)
        assertEquals(true, snapshot?.isPlaying)
        assertEquals(0.4f, outcome?.result?.volumeLevel?.fraction)
        assertEquals(true, outcome?.result?.videoOutputReady)
        assertEquals(false, outcome?.result?.polledTracks)
        engine.shutdown()
    }
}
