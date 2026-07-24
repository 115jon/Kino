package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

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

    @Test
    fun windowsJsonHeaderParserRejectsMalformedHeaderNamesAndValues() {
        val headersJson = buildString {
            append('{')
            append("\"X-Valid\":\"ok\",")
            append("\"Bad Name\":\"value\",")
            append("\"Bad\\u0000Name\":\"value\",")
            append("\"Control\":\"value\\nwith-control\",")
            append("\"Oversized\":\"")
            append("x".repeat(MaxPlaybackHeaderValueLength + 1))
            append("\",\"Range\":\"bytes=0-1\"")
            append('}')
        }

        assertEquals(
            mapOf("X-Valid" to "ok"),
            parseWindowsPlaybackHeaders(headersJson),
        )
    }

    @Test
    fun separateAudioResolutionUsesResolvedVideoOrigin() {
        val headers = mapOf(
            "Referer" to "https://example.com/watch",
            "User-Agent" to "Kino",
        )
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        val resolved = resolveWindowsPlaybackUrls(
            url = "https://media.example/video-source",
            audioUrl = "https://media.example/audio-source",
            headers = headers,
            urlResolver = { candidate, candidateHeaders, _ ->
                calls += candidate to candidateHeaders
                DesktopPlaybackUrlResolution(
                    url = if (candidate.endsWith("/video-source")) {
                        "https://video.example/video-redirect"
                    } else {
                        "https://audio.example/audio-redirect"
                    },
                    headers = candidateHeaders,
                )
            },
        )

        assertEquals(
            listOf(
                "https://media.example/video-source" to headers,
                "https://media.example/audio-source" to mapOf("User-Agent" to "Kino"),
            ),
            calls,
        )
        assertEquals("https://video.example/video-redirect", resolved.url)
        assertEquals("https://audio.example/audio-redirect", resolved.audioUrl)
        assertEquals(mapOf("User-Agent" to "Kino"), resolved.audioHeaders)
    }

    @Test
    fun redirectedResourcesClearOnlyTheirOwnHeaders() {
        val headers = mapOf("Referer" to "https://example.com", "User-Agent" to "Kino")

        val videoRedirected = resolveWindowsPlaybackUrls(
            url = "https://media.example/video-source",
            audioUrl = "https://media.example/audio-source",
            headers = headers,
            urlResolver = { candidate, candidateHeaders, _ ->
                DesktopPlaybackUrlResolution(
                    url = if (candidate.endsWith("/video-source")) {
                        "https://cdn.example/video-redirect"
                    } else {
                        candidate
                    },
                    headers = if (candidate.endsWith("/video-source")) {
                        mapOf("User-Agent" to "Kino")
                    } else {
                        candidateHeaders
                    },
                )
            },
        )
        val audioRedirected = resolveWindowsPlaybackUrls(
            url = "https://media.example/video-source",
            audioUrl = "https://media.example/audio-source",
            headers = headers,
            urlResolver = { candidate, candidateHeaders, _ ->
                DesktopPlaybackUrlResolution(
                    url = if (candidate.endsWith("/audio-source")) {
                        "https://cdn.example/audio-redirect"
                    } else {
                        candidate
                    },
                    headers = if (candidate.endsWith("/audio-source")) {
                        mapOf("User-Agent" to "Kino")
                    } else {
                        candidateHeaders
                    },
                )
            },
        )

        assertEquals(mapOf("User-Agent" to "Kino"), videoRedirected.videoHeaders)
        assertEquals(mapOf("User-Agent" to "Kino"), videoRedirected.audioHeaders)
        assertEquals(headers, audioRedirected.videoHeaders)
        assertEquals(mapOf("User-Agent" to "Kino"), audioRedirected.audioHeaders)
    }

    @Test
    fun separateAudioResourceNeverReceivesVideoCredentialsAcrossOrigins() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "Origin" to "https://video.example",
            "Referer" to "https://video.example/watch",
            "User-Agent" to "Kino",
        )
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        val resolved = resolveWindowsPlaybackUrls(
            url = "https://video.example/video.mkv",
            audioUrl = "https://audio.example/audio.mka",
            headers = headers,
            urlResolver = { candidate, candidateHeaders, _ ->
                calls += candidate to candidateHeaders
                DesktopPlaybackUrlResolution(candidate, candidateHeaders)
            },
        )

        assertEquals(mapOf("User-Agent" to "Kino"), calls[1].second)
        assertEquals(mapOf("User-Agent" to "Kino"), resolved.audioHeaders)
    }

    @Test
    fun separateAudioResourceKeepsHeadersOnTheSameOrigin() {
        val headers = mapOf("Authorization" to "Bearer secret", "User-Agent" to "Kino")

        val resolved = resolveWindowsPlaybackUrls(
            url = "https://media.example/video.mkv",
            audioUrl = "https://media.example/audio.mka",
            headers = headers,
            urlResolver = { candidate, candidateHeaders, _ ->
                DesktopPlaybackUrlResolution(candidate, candidateHeaders)
            },
        )

        assertEquals(headers, resolved.audioHeaders)
    }

    @Test
    fun resolvedAudioOriginMustMatchResolvedVideoOriginForCredentials() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "User-Agent" to "Kino",
        )

        val resolved = resolveWindowsPlaybackUrls(
            url = "https://video.example/video.mkv",
            audioUrl = "https://audio.example/audio.mka",
            headers = headers,
            urlResolver = { candidate, candidateHeaders, _ ->
                DesktopPlaybackUrlResolution(
                    url = if (candidate.contains("video")) {
                        "https://cdn.example/video.mkv"
                    } else {
                        "https://audio.example/audio.mka"
                    },
                    headers = candidateHeaders,
                )
            },
        )

        assertEquals(mapOf("User-Agent" to "Kino"), resolved.audioHeaders)
    }

    @Test
    fun malformedOrRelativeAudioOriginsFailClosedToUserAgentOnly() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "Origin" to "https://video.example",
            "Referer" to "https://video.example/watch",
            "User-Agent" to "Kino",
        )

        assertEquals(
            mapOf("User-Agent" to "Kino"),
            desktopPlaybackHeadersForAudioResource(
                videoUrl = "relative-video",
                audioUrl = "https://audio.example/audio.mka",
                headers = headers,
            ),
        )
        assertEquals(
            mapOf("User-Agent" to "Kino"),
            desktopPlaybackHeadersForAudioResource(
                videoUrl = "https://video.example/video.mkv",
                audioUrl = "relative-audio",
                headers = headers,
            ),
        )
    }

    @Test
    fun delayedAudioAddRestoresVideoHeaderBlob() {
        val videoHeaders = mapOf("Referer" to "https://video.example")
        val audioHeaders = mapOf("Origin" to "https://audio.example")
        val applied = mutableListOf<String>()

        withWindowsAudioHeadersTemporarily(
            videoHeaders = videoHeaders,
            audioHeaders = audioHeaders,
            setHeaders = { value -> applied += value; 0 },
            audioAdd = { 0 },
        )

        assertEquals(
            listOf(
                encodeWindowsPlaybackHeaders(audioHeaders),
                encodeWindowsPlaybackHeaders(videoHeaders),
            ),
            applied,
        )
    }

    @Test
    fun delayedAudioAddRechecksGenerationAfterWaitingForMpvLock() {
        val lock = ReentrantLock()
        val generation = AtomicLong(1L)
        val callbackStarted = CountDownLatch(1)
        val applied = mutableListOf<String>()

        lock.lock()
        val callback = thread {
            callbackStarted.countDown()
            withWindowsAudioAddIfCurrent(
                lock = lock,
                isCurrent = { generation.get() == 1L },
                videoHeaders = mapOf("Referer" to "https://video.example"),
                audioHeaders = mapOf("Origin" to "https://audio.example"),
                setHeaders = { value -> applied += value; 0 },
                audioAdd = { applied += "audio-add"; 0 },
            )
        }
        try {
            assertTrue(callbackStarted.await(1L, TimeUnit.SECONDS))
            generation.set(2L)
        } finally {
            lock.unlock()
        }

        callback.join(1_000L)
        assertFalse(callback.isAlive)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun malformedPlaybackInputsRemainSafe() {
        assertTrue(parseWindowsPlaybackHeaders("x".repeat(MaxWindowsPlaybackHeadersJsonLength + 1)).isEmpty())
        assertTrue(parseWindowsPlaybackHeaders("{\"empty\":\"\",\"list\":[]}").isEmpty())

        val resolved = resolveWindowsPlaybackUrls(
            url = "not a url",
            audioUrl = "",
            headers = emptyMap(),
        )

        assertEquals("not a url", resolved.url)
        assertEquals(null, resolved.audioUrl)
        assertTrue(resolved.failed)
    }

    @Test
    fun signedUrlsAreRedactedFromResolverAndTrackMetadataLogs() {
        val signedUrl = "https://user:secret@cdn.example.com/video.m3u8?token=signed-value#part"
        val redactedUrl = redactDesktopPlaybackUrlForLog(signedUrl)
        val redactedMetadata = playbackMetadataForLog("codec=$signedUrl")

        assertFalse(redactedUrl.contains("secret"))
        assertFalse(redactedUrl.contains("token"))
        assertFalse(redactedUrl.contains("#part"))
        assertFalse(redactedMetadata.contains("secret"))
        assertFalse(redactedMetadata.contains("signed-value"))
        assertFalse(redactedMetadata.contains("#part"))
    }

    @Test
    fun trackMetadataLogFieldsAreBounded() {
        assertTrue(playbackMetadataForLog("x".repeat(10_000)).length <= 96)
    }

    @Test
    fun longMpvTrackFieldsAreBoundedForPlayerModels() {
        val longId = "track-".repeat(400)
        val longTitle = "title-".repeat(400)

        val metadata = windowsMpvTrackMetadataForModel(
            id = longId,
            title = longTitle,
            language = "en",
            codec = "aac",
        )

        assertEquals("", metadata.id)
        assertEquals(256, metadata.title.length)
    }

    @Test
    fun delayedAudioAddReportsAudioHeaderSetterExceptionAndRestoresVideoHeaders() {
        val applied = mutableListOf<String>()
        val audioHeaders = encodeWindowsPlaybackHeaders(mapOf("Origin" to "https://audio.example"))
        val videoHeaders = encodeWindowsPlaybackHeaders(mapOf("Referer" to "https://video.example"))

        val result = withWindowsAudioHeadersTemporarily(
            videoHeaders = mapOf("Referer" to "https://video.example"),
            audioHeaders = mapOf("Origin" to "https://audio.example"),
            setHeaders = { value ->
                applied += value
                if (value == audioHeaders) throw IllegalStateException("setter failed")
                0
            },
            audioAdd = { 0 },
        )

        assertEquals(WindowsAudioAttachmentResult.HeaderApplyFailed, result)
        assertEquals(listOf(audioHeaders, videoHeaders), applied)
    }

    @Test
    fun delayedAudioAddReportsAudioAddExceptionAndRestoresVideoHeaders() {
        val result = withWindowsAudioHeadersTemporarily(
            videoHeaders = mapOf("Referer" to "https://video.example"),
            audioHeaders = mapOf("Origin" to "https://audio.example"),
            setHeaders = { 0 },
            audioAdd = { throw IllegalStateException("audio add failed") },
        )

        assertEquals(WindowsAudioAttachmentResult.AudioAddFailed, result)
    }

    @Test
    fun delayedAudioAddReportsHeaderRestoreException() {
        var setCount = 0

        val result = withWindowsAudioHeadersTemporarily(
            videoHeaders = mapOf("Referer" to "https://video.example"),
            audioHeaders = mapOf("Origin" to "https://audio.example"),
            setHeaders = {
                setCount += 1
                if (setCount == 2) throw IllegalStateException("restore failed")
                0
            },
            audioAdd = { 0 },
        )

        assertEquals(WindowsAudioAttachmentResult.HeaderRestoreFailed, result)
    }

    @Test
    fun failedAudioHeaderSetterSkipsAudioAddAndRestoresVideoHeaders() {
        val applied = mutableListOf<String>()
        var audioAddCount = 0

        val result = withWindowsAudioHeadersTemporarily(
            videoHeaders = mapOf("Referer" to "https://video.example"),
            audioHeaders = mapOf("Origin" to "https://audio.example"),
            setHeaders = { value ->
                applied += value
                if (applied.size == 1) -1 else 0
            },
            audioAdd = { audioAddCount += 1; 0 },
        )

        assertEquals(WindowsAudioAttachmentResult.HeaderApplyFailed, result)
        assertEquals(0, audioAddCount)
        assertEquals(2, applied.size)
    }

    @Test
    fun headerRestoreFailureIsReportedSeparately() {
        var setCount = 0

        val result = withWindowsAudioHeadersTemporarily(
            videoHeaders = mapOf("Referer" to "https://video.example"),
            audioHeaders = mapOf("Origin" to "https://audio.example"),
            setHeaders = {
                setCount += 1
                if (setCount == 2) -1 else 0
            },
            audioAdd = { 0 },
        )

        assertEquals(WindowsAudioAttachmentResult.HeaderRestoreFailed, result)
    }

    @Test
    fun failedOrTerminalPlaybackDoesNotScheduleAudioAdd() {
        assertFalse(shouldScheduleWindowsAudioAdd(-1, true, false))
        assertFalse(shouldScheduleWindowsAudioAdd(0, false, false))
        assertFalse(shouldScheduleWindowsAudioAdd(0, true, true))
        assertTrue(shouldScheduleWindowsAudioAdd(0, true, false))
    }

    @Test
    fun failedUrlResolutionAbortsPlaybackBeforeMpvLoad() {
        val failed = WindowsResolvedPlaybackUrls(
            url = "https://example.com/source",
            audioUrl = null,
            videoHeaders = emptyMap(),
            audioHeaders = emptyMap(),
            failed = true,
        )

        assertTrue(shouldAbortWindowsPlaybackLoad(failed))
        assertFalse(shouldAbortWindowsPlaybackLoad(failed.copy(failed = false)))
    }

    @Test
    fun endFileOnlyBlocksAudioAddForTheCurrentLoadedPlaylistEntry() {
        assertTrue(isCurrentWindowsMpvEndFile(7L, 7L, null, 4L, null, 4L))
        assertTrue(isCurrentWindowsMpvEndFile(7L, null, 7L, null, 4L, 4L))
        assertFalse(isCurrentWindowsMpvEndFile(7L, 8L, null, 4L, null, 4L))
        assertFalse(isCurrentWindowsMpvEndFile(7L, 7L, null, 3L, null, 4L))
    }

    @Test
    fun encodedHeaderAggregateAndCountStayBounded() {
        val oversized = encodeWindowsPlaybackHeaders(
            mapOf("X-Large" to "x".repeat(MaxPlaybackHeaderAggregateLength),
            ),
        )
        val tooMany = encodeWindowsPlaybackHeaders(
            (0..MaxPlaybackHeaderCount).associate { index -> "X-$index" to "value" },
        )

        assertTrue(oversized.length <= MaxPlaybackHeaderAggregateLength)
        assertTrue(tooMany.length <= MaxPlaybackHeaderAggregateLength)
    }

    @Test
    fun loggedMetadataStripsAnsiAndControlCharactersBeforeOutput() {
        val value = "\u001B[31mhttps://user:secret@example.com/video?token=value\u001B[0m\u0001\u0085"
        val logged = playbackMetadataForLog(value)

        assertFalse(logged.contains("\u001B"))
        assertFalse(logged.contains("\u0001"))
        assertFalse(logged.contains("\u0085"))
        assertFalse(logged.contains("secret"))
        assertFalse(logged.contains("token=value"))
    }

    @Test
    fun rawTrackValuesRemainSelectableWhileTrackLogsStayBoundedAndSafe() {
        val rawId = "https://user:secret@cdn.example.com/" + "x".repeat(200)
        val rawTitle = "English\u202E"
        val rawLanguage = "eng\u2066"
        val rawCodec = "eac3\u202E"
        val track = AudioTrack(
            index = 0,
            id = rawId,
            label = rawTitle,
            language = rawLanguage,
            codec = rawCodec,
        )

        assertEquals(rawId, track.id)
        assertEquals(rawTitle, track.label)
        assertEquals(rawLanguage, track.language)
        assertEquals(rawCodec, track.codec)

        val logged = listOf(track.id, track.label, track.language, track.codec)
            .joinToString(" ") { playbackMetadataForLog(it) }
        assertTrue(logged.length <= 4 * MaxWindowsMpvMetadataFieldLength)
        assertFalse(logged.contains("secret"))
        assertFalse(logged.contains("\u202E"))
        assertFalse(logged.contains("\u2066"))
    }

    @Test
    fun loggedMetadataRemovesBidiAndFormatControls() {
        val logged = playbackMetadataForLog("before\u202Espoof\u2066after")

        assertEquals("beforespoofafter", logged)
        assertFalse(logged.any { it.category == CharCategory.FORMAT })
    }
}
