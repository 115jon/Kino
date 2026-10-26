package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvEngineLifecycleTest {
    private val client = FakeMpvClient()
    private val dispatcher = ManualMpvDispatcher()
    private val engine = MpvEngine(
        tracePrefix = "test panel",
        dispatcher = dispatcher,
        clientFactory = { client },
        resolutionContext = Dispatchers.Unconfined,
    )

    @AfterTest
    fun tearDown() {
        engine.shutdown()
    }

    @Test
    fun `wid output selects the d3d11 window presentation`() {
        val output = WidOutput(hwnd = 4242L)

        assertEquals("info", output.logLevel)
        assertEquals(
            listOf(
                MpvOption("wid", "4242", required = true),
                MpvOption("vo", "gpu-next,gpu"),
                MpvOption("gpu-api", "d3d11", required = true),
                MpvOption("gpu-context", "d3d11", required = true),
                MpvOption("d3d11-output-mode", "window", required = true),
                MpvOption("d3d11-exclusive-fs", "no", required = true),
                MpvOption("d3d11-flip", "no", required = true),
            ),
            output.options(),
        )
    }

    @Test
    fun `render api output selects libmpv and quieter logging`() {
        val output = RenderApiOutput {}

        assertEquals("warn", output.logLevel)
        assertEquals(listOf(MpvOption("vo", "libmpv")), output.options())
    }

    @Test
    fun `every output shares the playback options after its own`() {
        val options = mpvEngineOptions(WidOutput(1L))

        assertEquals("wid", options.first().name)
        assertEquals(mpvEngineCommonOptions, options.drop(7))
        assertTrue(MpvOption("hwdec", "auto-copy-safe") in options)
        assertTrue(MpvOption("keep-open", "yes") in options)
        assertTrue(MpvOption("sub-ass-override", "force") in options)
    }

    @Test
    fun `start applies options then initializes observes and arms the wakeup`() {
        engine.start(WidOutput(7L))

        val calls = client.snapshotCalls()
        val optionCalls = calls.takeWhile { it.startsWith("option ") }
        assertEquals(mpvEngineOptions(WidOutput(7L)).map { "option ${it.name}=${it.value}" }, optionCalls)
        val rest = calls.drop(optionCalls.size)
        assertEquals("initialize", rest[0])
        assertEquals("log info", rest[1])
        assertEquals(mpvObservedProperties.map { "observe ${it.name}" }, rest.subList(2, 2 + mpvObservedProperties.size))
        assertEquals("wakeup true", rest[2 + mpvObservedProperties.size])
        assertTrue(engine.isReady)
    }

    @Test
    fun `rejected required option terminates the client and rethrows`() {
        client.optionResults["gpu-api"] = -5

        val error = assertFailsWith<RuntimeException> { engine.start(WidOutput(7L)) }

        assertEquals("Unsupported libmpv option gpu-api=d3d11 (error -5)", error.message)
        assertEquals("terminate", client.snapshotCalls().last())
        assertFalse(engine.isReady)
    }

    @Test
    fun `failed initialize terminates the client`() {
        client.initializeResult = -1

        assertFailsWith<RuntimeException> { engine.start(WidOutput(7L)) }

        assertTrue("initialize" in client.snapshotCalls())
        assertEquals("terminate", client.snapshotCalls().last())
        assertFalse(engine.isReady)
    }

    @Test
    fun `optional option rejection does not stop startup`() {
        client.optionResults["vo"] = -1

        engine.start(WidOutput(7L))

        assertTrue(engine.isReady)
    }

    @Test
    fun `shutdown clears the wakeup before terminating and runs once`() {
        engine.start(WidOutput(7L))
        client.clearCalls()

        engine.shutdown()
        engine.shutdown()

        assertEquals(listOf("wakeup false", "terminate"), client.snapshotCalls())
        assertFalse(engine.isReady)
    }

    @Test
    fun `commands are ignored once the engine is closed`() {
        engine.start(WidOutput(7L))
        engine.close()
        client.clearCalls()

        engine.setPaused(true)
        engine.seekBy(1_000L)

        assertEquals(emptyList(), client.snapshotCalls())
    }

    @Test
    fun `playback commands map onto mpv properties and commands`() {
        engine.start(WidOutput(7L))
        client.clearCalls()

        engine.setPaused(true)
        engine.setPaused(false)
        engine.togglePause()
        engine.seekTo(12_500L)
        engine.seekBy(-10_000L)
        engine.setSpeed(1.5f)
        engine.selectSubtitleTrack(-1)
        engine.applyAudioLanguagePreferences(listOf("en", "ja"))
        engine.setResizeMode(PlayerResizeMode.Zoom)

        assertEquals(
            listOf(
                "set pause=yes",
                "set pause=no",
                "command cycle pause",
                "command seek ${String.format("%.3f", 12.5)} absolute",
                "command seek ${String.format("%.3f", -10.0)} relative",
                "set speed=1.5",
                "set sid=no",
                "set alang=en,ja",
                "set aid=auto",
                "option panscan=0.0",
                "option video-unscaled=downscale-big",
            ),
            client.snapshotCalls(),
        )
    }

    @Test
    fun `volume level updates coalesce to the latest value`() {
        val queued = ManualMpvDispatcher(runPostsImmediately = false)
        val queuedClient = FakeMpvClient()
        val queuedEngine = MpvEngine("test panel", queued, { queuedClient }, resolutionContext = Dispatchers.Unconfined)
        queuedEngine.start(WidOutput(7L))
        queuedClient.clearCalls()

        queuedEngine.setVolumeLevel(0.2f)
        val level = queuedEngine.setVolumeLevel(0.55f)
        queued.runPosted()

        assertEquals(listOf("set volume=55"), queuedClient.snapshotCalls())
        assertEquals(0.55f, level?.fraction)
        assertEquals(level, queuedEngine.playback.volumeLevel)
        queuedEngine.shutdown()
    }
}
