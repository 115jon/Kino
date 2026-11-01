package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRateLimitHandlingTest {
    @BeforeTest
    fun setUp() {
        PlaybackRateLimits.clear()
    }

    @AfterTest
    fun tearDown() {
        PlaybackRateLimits.clear()
    }

    @Test
    fun `redirect probe reports a 429 with its retry after header`() {
        val requested = mutableListOf<String>()
        val resolved = resolveDesktopPlaybackRedirects(
            url = "https://comet.elfhosted.com/playback/abc",
            headers = emptyMap(),
        ) { uri, _ ->
            requested += uri.toString()
            DesktopPlaybackRedirectResponse(429, null, retryAfter = "42")
        }

        assertTrue(resolved.failed)
        assertTrue(resolved.rateLimited)
        assertEquals("42", resolved.retryAfter)
        assertEquals(listOf("https://comet.elfhosted.com/playback/abc"), requested)
    }

    @Test
    fun `rate limited startup log stops mpv once and records a cooldown for the source host`() {
        val client = FakeMpvClient()
        val engine = MpvEngine(
            tracePrefix = "test panel",
            clientFactory = { client },
            dispatcher = ManualMpvDispatcher(),
            resolutionContext = Dispatchers.Unconfined,
        )
        val notified = CountDownLatch(1)
        engine.onStateChanged = { notified.countDown() }
        engine.start(WidOutput(1L))
        engine.playback.currentSourceUrl = "https://torrentio.strem.fun/resolve/torbox/key/hash/null/0/file.mkv"
        client.clearCalls()

        repeat(3) {
            client.events += MpvClientEvent.LogMessage(MPV_EVENT_LOG_MESSAGE, 0, "ffmpeg", "warn", "https: HTTP error 429 Too Many Requests")
        }
        client.events += MpvClientEvent.StartFile(MPV_EVENT_START_FILE, 0, 1L)
        client.wakeup?.invoke()

        assertTrue(notified.await(5, TimeUnit.SECONDS))
        assertEquals(1, client.snapshotCalls().count { it == "command stop" })
        assertEquals(PlaybackRateLimitedMessage, engine.playback.terminalPlaybackError)
        assertEquals(engine.playback.sourceGeneration, engine.playback.terminalPlaybackErrorGeneration)
        assertTrue(PlaybackRateLimits.remainingMs("torrentio.strem.fun", System.currentTimeMillis()) > 0L)
        engine.shutdown()
    }

    @Test
    fun `rate limit during playback keeps the stream running`() {
        val client = FakeMpvClient()
        val engine = MpvEngine(
            tracePrefix = "test panel",
            clientFactory = { client },
            dispatcher = ManualMpvDispatcher(),
            resolutionContext = Dispatchers.Unconfined,
        )
        val notified = CountDownLatch(1)
        engine.onStateChanged = { notified.countDown() }
        engine.start(WidOutput(1L))
        engine.playback.currentSourceUrl = "https://comet.elfhosted.com/playback/abc"
        engine.playback.hasLoadedMedia = true
        client.clearCalls()

        client.events += MpvClientEvent.LogMessage(MPV_EVENT_LOG_MESSAGE, 0, "ffmpeg", "warn", "https: HTTP error 429 Too Many Requests")
        client.events += MpvClientEvent.StartFile(MPV_EVENT_START_FILE, 0, 1L)
        client.wakeup?.invoke()

        assertTrue(notified.await(5, TimeUnit.SECONDS))
        assertFalse(client.snapshotCalls().contains("command stop"))
        assertTrue(PlaybackRateLimits.remainingMs("comet.elfhosted.com", System.currentTimeMillis()) > 0L)
        engine.shutdown()
    }

    @Test
    fun `load during a cooldown fails fast without resolving or opening the url`() {
        val client = FakeMpvClient()
        val resolverCalls = mutableListOf<String>()
        val engine = MpvEngine(
            tracePrefix = "test panel",
            dispatcher = ManualMpvDispatcher(),
            clientFactory = { client },
            urlResolver = { url, audioUrl, headers, _ ->
                resolverCalls += url
                WindowsResolvedPlaybackUrls(url, audioUrl, headers, emptyMap())
            },
            resolutionContext = Dispatchers.Unconfined,
        )
        engine.start(WidOutput(1L))
        client.clearCalls()
        PlaybackRateLimits.record("torrentio.strem.fun", System.currentTimeMillis())

        engine.load("https://torrentio.strem.fun/resolve/torbox/key/hash/null/0/file.mkv", null, emptyMap())

        assertTrue(resolverCalls.isEmpty())
        assertFalse(client.snapshotCalls().any { it.startsWith("command loadfile") })
        assertEquals(PlaybackRateLimitedMessage, engine.playback.terminalPlaybackError)
        engine.shutdown()
    }

    @Test
    fun `end file error does not replace the rate limit message`() {
        val state = MpvPlaybackState().apply {
            sourceGeneration = 2L
            activePlaylistEntryId = 5L
            activePlaylistEntryGeneration = 2L
            markTerminalError(PlaybackRateLimitedMessage, 2L)
        }

        handleWindowsMpvEndFileEvent(
            state,
            MpvEndFileData(reason = WindowsMpvEndFileReasonError, error = -13, playlistEntryId = 5L),
        ) { "loading failed" }

        assertEquals(PlaybackRateLimitedMessage, state.terminalPlaybackError)
    }
}
