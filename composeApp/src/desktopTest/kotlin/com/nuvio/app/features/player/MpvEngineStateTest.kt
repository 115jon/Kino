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
        val display = WindowsVideoOutputTarget.Display
        assertEquals("", windowsVideoPipelineFilter(DesktopVideoPipelineMode.Standard, display))
        assertEquals(WindowsHdrCompatibilityFilter, windowsVideoPipelineFilter(DesktopVideoPipelineMode.HdrCompatibility, display))
        assertEquals(
            WindowsDolbyVisionCompatibilityFilter,
            windowsVideoPipelineFilter(DesktopVideoPipelineMode.DolbyVisionCompatibility, display),
        )
        assertTrue("apply_dolbyvision=1" in WindowsDolbyVisionCompatibilityFilter)
    }

    @Test
    fun `sdr textures tone map only dolby vision in libplacebo`() {
        val sdr = WindowsVideoOutputTarget.SdrTexture
        assertEquals("", windowsVideoPipelineFilter(DesktopVideoPipelineMode.Standard, sdr))
        assertEquals("", windowsVideoPipelineFilter(DesktopVideoPipelineMode.HdrCompatibility, sdr))
        assertEquals(WindowsDolbyVisionSdrFilter, windowsVideoPipelineFilter(DesktopVideoPipelineMode.DolbyVisionCompatibility, sdr))
        assertTrue(WindowsDolbyVisionSdrFilter.startsWith(WindowsDolbyVisionCompatibilityFilter))
        assertTrue("format=yuv420p" in WindowsDolbyVisionSdrFilter)
    }

    @Test
    fun `sdr video keeps the plain pipeline`() {
        val plan = planWindowsVideoPipeline(diagnostics(), MpvPlaybackState(), WindowsVideoOutputTarget.Display)

        assertEquals(DesktopVideoPipelineMode.Standard, plan?.decision?.mode)
        assertEquals("", plan?.filterSpec)
    }

    @Test
    fun `pq video selects the hdr libplacebo filter`() {
        val plan = planWindowsVideoPipeline(
            diagnostics(pixelFormat = "p010", primaries = "bt.2020", transfer = "pq"),
            MpvPlaybackState(),
            WindowsVideoOutputTarget.Display,
        )

        assertEquals(DesktopVideoPipelineMode.HdrCompatibility, plan?.decision?.mode)
        assertEquals(WindowsHdrCompatibilityFilter, plan?.filterSpec)
    }

    @Test
    fun `dolby vision metadata selects the dolby vision filter`() {
        val plan = planWindowsVideoPipeline(diagnostics(dolbyVision = "dvhe.08"), MpvPlaybackState(), WindowsVideoOutputTarget.Display)

        assertEquals(DesktopVideoPipelineMode.DolbyVisionCompatibility, plan?.decision?.mode)
        assertEquals(WindowsDolbyVisionCompatibilityFilter, plan?.filterSpec)
    }

    @Test
    fun `stream profile dolby vision wins over sdr video params`() {
        val state = MpvPlaybackState().apply { streamHasDolbyVision = true }

        val plan = planWindowsVideoPipeline(diagnostics(), state, WindowsVideoOutputTarget.Display)

        assertEquals(DesktopVideoPipelineMode.DolbyVisionCompatibility, plan?.decision?.mode)
    }

    @Test
    fun `pipeline is not planned without video params or for an unchanged signature`() {
        val state = MpvPlaybackState()
        assertNull(planWindowsVideoPipeline(diagnostics(pixelFormat = null), state, WindowsVideoOutputTarget.Display))

        val first = planWindowsVideoPipeline(diagnostics(), state, WindowsVideoOutputTarget.Display)
        state.lastVideoDiagnosticsSignature = first?.signature

        assertNull(planWindowsVideoPipeline(diagnostics(), state, WindowsVideoOutputTarget.Display))
    }

    @Test
    fun `configuring the pipeline writes vf once per signature`() {
        val client = FakeMpvClient()
        client.properties["video-params/pixelformat"] = "p010"
        client.properties["video-params/primaries"] = "bt.2020"
        client.properties["video-params/gamma"] = "pq"
        val state = MpvPlaybackState()

        configureWindowsVideoPipeline(client, state, WindowsVideoOutputTarget.Display, "test")
        configureWindowsVideoPipeline(client, state, WindowsVideoOutputTarget.Display, "test")

        assertEquals(listOf("set file-local-options/vf=$WindowsHdrCompatibilityFilter"), client.snapshotCalls())
        assertEquals(DesktopVideoPipelineMode.HdrCompatibility, state.videoPipelineMode)
        resetWindowsVideoPipeline(state)
        assertNull(state.lastVideoDiagnosticsSignature)
        assertEquals("", state.appliedVideoFilter)
        assertEquals(DesktopVideoPipelineMode.Standard, state.videoPipelineMode)
    }

    @Test
    fun `sdr video does not rebuild an empty filter chain`() {
        val client = FakeMpvClient()
        client.properties["video-params/pixelformat"] = "nv12"

        configureWindowsVideoPipeline(client, MpvPlaybackState(), WindowsVideoOutputTarget.SdrTexture, "test")

        assertEquals(emptyList(), client.snapshotCalls())
    }

    @Test
    fun `container dolby vision is planned before the first decoded frame`() {
        val client = FakeMpvClient()
        client.properties["current-tracks/video/dolby-vision-profile"] = "8"
        val state = MpvPlaybackState()

        configureWindowsVideoPipeline(client, state, WindowsVideoOutputTarget.SdrTexture, "file-loaded")
        client.properties["video-params/pixelformat"] = "p010"
        configureWindowsVideoPipeline(client, state, WindowsVideoOutputTarget.SdrTexture, "poll")

        assertEquals(
            listOf(
                "set file-local-options/hwdec=$WindowsFilterCopyHwdec",
                "set file-local-options/vf=$WindowsDolbyVisionSdrFilter",
            ),
            client.snapshotCalls(),
        )
        assertEquals(DesktopVideoPipelineMode.DolbyVisionCompatibility, state.videoPipelineMode)
    }

    @Test
    fun `a filter on gpu surfaces switches to copy decoding until the next load`() {
        val client = FakeMpvClient()
        client.properties["video-params/pixelformat"] = "cuda"
        client.properties["video-params/hw-pixelformat"] = "p010"
        client.properties["video-params/primaries"] = "bt.2020"
        client.properties["video-params/gamma"] = "pq"
        val state = MpvPlaybackState()

        configureWindowsVideoPipeline(client, state, WindowsVideoOutputTarget.Display, "test")
        resetWindowsVideoPipeline(state)

        assertEquals(
            listOf(
                "set file-local-options/hwdec=$WindowsFilterCopyHwdec",
                "set file-local-options/vf=$WindowsHdrCompatibilityFilter",
            ),
            client.snapshotCalls(),
        )
        assertFalse(state.hwdecCopyForced)
    }

    @Test
    fun `filters on system frames keep the configured decoder`() {
        val filter = WindowsHdrCompatibilityFilter
        assertFalse(shouldForceWindowsCopyHwdec(filter, "p010", null, alreadyForced = false))
        assertFalse(shouldForceWindowsCopyHwdec("", "cuda", "p010", alreadyForced = false))
        assertFalse(shouldForceWindowsCopyHwdec(filter, "cuda", "p010", alreadyForced = true))
        assertTrue(shouldForceWindowsCopyHwdec(filter, "cuda", "p010", alreadyForced = false))
        assertTrue(shouldForceWindowsCopyHwdec(filter, null, null, alreadyForced = false))
    }

    @Test
    fun `only the originally observed properties wake the player state`() {
        assertTrue(mpvPropertyChangeNotifiesState("pause"))
        assertTrue(mpvPropertyChangeNotifiesState("track-list/count"))
        assertTrue(mpvPropertyChangeNotifiesState(""))
        assertFalse(mpvPropertyChangeNotifiesState("path"))
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
    fun `event pump applies property changes and start file events`() {
        val client = FakeMpvClient()
        val engine = MpvEngine(tracePrefix = "test panel", clientFactory = { client }, dispatcher = ManualMpvDispatcher(), resolutionContext = Dispatchers.Unconfined)
        val notified = CountDownLatch(1)
        engine.onStateChanged = { notified.countDown() }
        engine.start(WidOutput(1L))

        client.events += MpvClientEvent.PropertyChange(MPV_EVENT_PROPERTY_CHANGE, 0, "path", "https://cdn.example/a.mkv")
        client.events += MpvClientEvent.PropertyChange(MPV_EVENT_PROPERTY_CHANGE, 0, "pause", true)
        client.events += MpvClientEvent.StartFile(MPV_EVENT_START_FILE, 0, 4L)
        client.wakeup?.invoke()

        assertTrue(notified.await(5, TimeUnit.SECONDS))
        assertEquals("https://cdn.example/a.mkv", engine.properties.string("path"))
        assertTrue(engine.properties.flag("pause"))
        assertEquals(4L, engine.playback.activePlaylistEntryId)
        assertEquals(0L, engine.playback.activePlaylistEntryGeneration)
        engine.shutdown()
    }

    @Test
    fun `poll builds the snapshot from observed properties`() {
        val client = FakeMpvClient()
        val engine = MpvEngine(tracePrefix = "test panel", clientFactory = { client }, dispatcher = ManualMpvDispatcher(), resolutionContext = Dispatchers.Unconfined)
        engine.start(WidOutput(1L))
        engine.properties.update("path", "https://cdn.example/video.mkv")
        client.properties["time-pos"] = "61.000000"
        engine.properties.update("duration", 120.0)
        client.properties["demuxer-cache-time"] = "9.000000"
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
