package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvEngineLoadTest {
    private val client = FakeMpvClient()
    private val dispatcher = ManualMpvDispatcher()
    private var resolved = WindowsResolvedPlaybackUrls(
        url = "https://cdn.example/video.mkv",
        audioUrl = null,
        videoHeaders = mapOf("Referer" to "https://site.example"),
        audioHeaders = emptyMap(),
    )
    private val resolverCalls = mutableListOf<String>()
    private val engine = MpvEngine(
        tracePrefix = "test panel",
        dispatcher = dispatcher,
        clientFactory = { client },
        urlResolver = { url, _, _, force ->
            resolverCalls += "$url force=$force"
            resolved
        },
        resolutionContext = Dispatchers.Unconfined,
    )
    private val styleCalls = mpvSubtitleStyleProperties(SubtitleStyleState.DEFAULT).map { (name, value) -> "set $name=$value" }

    @AfterTest
    fun tearDown() {
        engine.shutdown()
    }

    private fun startEngine() {
        engine.start(WidOutput(1L))
        client.clearCalls()
    }

    @Test
    fun `load writes video headers then style then loadfile replace`() {
        startEngine()
        client.properties["playlist/0/id"] = "3"
        val committed = mutableListOf<Long>()

        engine.load("https://addon.example/play", null, mapOf("Referer" to "x"), onCommitted = { committed += it })

        assertEquals(
            listOf("set http-header-fields=Referer: https://site.example") +
                styleCalls +
                listOf("command loadfile https://cdn.example/video.mkv replace"),
            client.snapshotCalls(),
        )
        assertEquals(listOf(1L), committed)
        assertEquals(listOf("https://addon.example/play force=false"), resolverCalls)
        assertEquals(1L, engine.playback.sourceGeneration)
        assertNull(engine.playback.pendingLoadRequestGeneration)
        assertEquals(3L, engine.playback.pendingPlaylistEntryId)
        assertEquals(1L, engine.playback.pendingPlaylistEntryGeneration)
        assertEquals(listOf(300L), dispatcher.delayed.map { it.first })
    }

    @Test
    fun `separate audio is added later with audio headers then video headers restored`() {
        startEngine()
        resolved = resolved.copy(
            audioUrl = "https://cdn.example/audio.m4a",
            audioHeaders = mapOf("Referer" to "https://audio.example"),
        )

        engine.load("https://addon.example/play", "https://addon.example/audio", emptyMap())
        client.clearCalls()
        dispatcher.runDelayed(500L)

        assertEquals(
            listOf(
                "set http-header-fields=Referer: https://audio.example",
                "command audio-add https://cdn.example/audio.m4a select",
                "set http-header-fields=Referer: https://site.example",
            ),
            client.snapshotCalls(),
        )
        assertNull(engine.playback.terminalPlaybackError)
    }

    @Test
    fun `failed header restore after audio-add stops playback and reports it`() {
        startEngine()
        resolved = resolved.copy(audioUrl = "https://cdn.example/audio.m4a")
        var fatal = false
        engine.onFatalError = { fatal = true }

        engine.load("https://addon.example/play", "https://addon.example/audio", emptyMap())
        client.propertyResults["http-header-fields"] = -1
        dispatcher.runDelayed(500L)

        assertEquals("Failed to restore video headers", engine.playback.terminalPlaybackError)
        assertTrue("command stop" in client.snapshotCalls())
        assertFalse(fatal)
    }

    @Test
    fun `audio is not added when the source changed before the delay`() {
        startEngine()
        resolved = resolved.copy(audioUrl = "https://cdn.example/audio.m4a")

        engine.load("https://addon.example/one", "https://addon.example/audio", emptyMap())
        resolved = resolved.copy(audioUrl = null)
        engine.load("https://addon.example/two", null, emptyMap())
        client.clearCalls()
        dispatcher.runDelayed(500L)

        assertEquals(emptyList(), client.snapshotCalls())
    }

    @Test
    fun `failed url resolution records a terminal error without loading`() {
        startEngine()
        resolved = resolved.copy(failed = true)

        engine.load("https://addon.example/play", null, emptyMap())

        assertEquals(emptyList(), client.snapshotCalls())
        assertEquals("Playback URL resolution failed", engine.playback.terminalPlaybackError)
        assertEquals(1L, engine.playback.terminalPlaybackErrorGeneration)
        assertNull(engine.playback.pendingLoadRequestGeneration)
    }

    @Test
    fun `rejected video headers stop the load before loadfile`() {
        startEngine()
        client.propertyResults["http-header-fields"] = -1

        engine.load("https://addon.example/play", null, emptyMap())

        assertEquals(listOf("set http-header-fields=Referer: https://site.example"), client.snapshotCalls())
        assertEquals("Failed to apply video headers", engine.playback.terminalPlaybackError)
    }

    @Test
    fun `failed loadfile command is reported as a terminal error`() {
        startEngine()
        client.commandResult = { args -> if (args.first() == "loadfile") -2 else 0 }
        val committed = mutableListOf<Long>()

        engine.load("https://addon.example/play", null, emptyMap(), onCommitted = { committed += it })

        assertEquals(emptyList(), committed)
        assertEquals(selectWindowsPlaybackCommandError(-2), engine.playback.terminalPlaybackError)
        assertNull(engine.playback.pendingPlaylistEntryId)
    }

    @Test
    fun `only the latest of overlapping loads is committed`() {
        val queued = ManualMpvDispatcher(runPostsImmediately = false)
        val queuedClient = FakeMpvClient()
        val queuedEngine = MpvEngine(
            tracePrefix = "test panel",
            dispatcher = queued,
            clientFactory = { queuedClient },
            urlResolver = { url, _, _, _ -> resolved.copy(url = url) },
            resolutionContext = Dispatchers.Unconfined,
        )
        queuedEngine.start(WidOutput(1L))
        queuedClient.clearCalls()

        queuedEngine.load("https://cdn.example/first.mkv", null, emptyMap())
        queuedEngine.load("https://cdn.example/second.mkv", null, emptyMap())
        assertEquals(2L, queuedEngine.playback.sourceGeneration)
        queued.runPosted()

        val loads = queuedClient.snapshotCalls().filter { it.startsWith("command loadfile") }
        assertEquals(listOf("command loadfile https://cdn.example/second.mkv replace"), loads)
        queuedEngine.shutdown()
    }

    @Test
    fun `retry restores the previous position after the load commits`() {
        startEngine()
        client.properties["time-pos"] = "42.5"

        val position = engine.currentPositionSeconds()
        engine.load("https://addon.example/play", null, emptyMap(), onCommitted = { generation ->
            engine.restorePositionAfterLoad(generation, position)
        }, forceResolve = true)
        client.clearCalls()
        dispatcher.runDelayed(500L)

        assertEquals(listOf("command seek ${String.format("%.3f", 42.5)} absolute"), client.snapshotCalls())
        assertEquals(listOf("https://addon.example/play force=true"), resolverCalls)
    }

    @Test
    fun `load is ignored before the engine starts`() {
        engine.load("https://addon.example/play", null, emptyMap())

        assertEquals(0L, engine.playback.sourceGeneration)
        assertEquals(emptyList(), resolverCalls)
    }

    @Test
    fun `pending events are drained into the property cache before loadfile`() {
        startEngine()
        engine.closeEvents()
        client.events += MpvClientEvent.PropertyChange(MPV_EVENT_PROPERTY_CHANGE, 0, "pause", true)
        client.events += MpvClientEvent.StartFile(MPV_EVENT_START_FILE, 0, 9L)
        var cacheHadPauseAtLoadfile = false
        var queueWasEmptyAtLoadfile = false
        client.commandResult = { args ->
            if (args.first() == "loadfile") {
                cacheHadPauseAtLoadfile = engine.properties.flag("pause")
                queueWasEmptyAtLoadfile = client.events.isEmpty()
            }
            0
        }

        engine.load("https://addon.example/play", null, emptyMap())

        assertTrue(cacheHadPauseAtLoadfile)
        assertTrue(queueWasEmptyAtLoadfile)
        assertNull(engine.playback.activePlaylistEntryId)
    }

    @Test
    fun `load resolves off the caller and commits on the command thread`() {
        val commandThread = MpvCommandThread()
        val threadedClient = FakeMpvClient()
        val releaseResolution = CountDownLatch(1)
        val committed = CountDownLatch(1)
        var resolverThread = ""
        var loadfileThread = ""
        threadedClient.commandResult = { args ->
            if (args.first() == "loadfile") loadfileThread = Thread.currentThread().name
            0
        }
        val threadedEngine = MpvEngine(
            tracePrefix = "test panel",
            clientFactory = { threadedClient },
            dispatcher = commandThread,
            urlResolver = { url, _, _, _ ->
                resolverThread = Thread.currentThread().name
                releaseResolution.await(5, TimeUnit.SECONDS)
                resolved.copy(url = url)
            },
            resolutionContext = Dispatchers.IO,
        )
        threadedEngine.start(WidOutput(1L))
        threadedClient.clearCalls()

        threadedEngine.load("https://cdn.example/threaded.mkv", null, emptyMap(), onCommitted = { committed.countDown() })

        assertEquals(1L, threadedEngine.playback.sourceGeneration)
        assertTrue(threadedEngine.playback.pendingLoadRequestGeneration != null)
        assertFalse(threadedClient.snapshotCalls().any { it.startsWith("command loadfile") })
        releaseResolution.countDown()

        assertTrue(committed.await(5, TimeUnit.SECONDS))
        val calls = threadedClient.snapshotCalls()
        assertTrue(
            calls.indexOf("set http-header-fields=Referer: https://site.example") <
                calls.indexOf("command loadfile https://cdn.example/threaded.mkv replace"),
        )
        assertEquals("Kino-mpv-commands", loadfileThread)
        assertTrue(resolverThread.isNotEmpty() && resolverThread != "Kino-mpv-commands")
        assertNull(threadedEngine.playback.pendingLoadRequestGeneration)
        threadedEngine.shutdown()
        assertTrue(commandThread.awaitTermination(5_000L))
    }
}
