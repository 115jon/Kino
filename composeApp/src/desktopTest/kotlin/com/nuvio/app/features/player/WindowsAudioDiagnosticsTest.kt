package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsAudioDiagnosticsTest {
    @Test
    fun malformedAudioMetadataIsIgnored() {
        assertNull(parseWindowsAudioInt(null))
        assertNull(parseWindowsAudioInt(""))
        assertNull(parseWindowsAudioInt("not-a-number"))
        assertNull(parseWindowsAudioLong("NaN"))
        assertEquals(48_000, parseWindowsAudioInt("48000"))
        assertEquals(640_000L, parseWindowsAudioLong("640000"))
    }

    @Test
    fun diagnosticsExposeAudioSourceAndOutputStateWithoutUrls() {
        val diagnostics = WindowsAudioDiagnostics(
            selectedCodec = "eac3",
            sourceCodec = "eac3",
            sourceSampleRate = 48_000,
            sourceChannelCount = 6,
            sourceChannelLayout = "5.1",
            sourceBitrate = 640_000L,
            outputSampleRate = 44_100,
            outputChannelCount = 2,
            outputChannelLayout = "stereo",
            outputFormat = "s16",
            configuredOutputDevice = "auto",
            activeOutputDevice = "Speakers https://private.example/device",
            outputDriver = "wasapi",
            resampling = "active",
            downmix = "active",
            audioSpeedCorrection = 0.001,
            audioDelay = 0.02,
            sessionUnderrunCount = 2L,
        )

        val log = formatWindowsAudioDiagnostics("track-change", diagnostics)

        assertTrue(log.contains("selectedCodec=eac3"))
        assertTrue(log.contains("sourceSampleRate=48000"))
        assertTrue(log.contains("activeOutputDevice=Speakers <redacted-url>"))
        assertTrue(log.contains("resampling=active"))
        assertTrue(log.contains("downmix=active"))
        assertTrue(log.contains("sessionUnderrunCount=2"))
        assertFalse(log.contains("https://"))
    }

    @Test
    fun diagnosticsAreRateLimitedButChangesRemainObservable() {
        var nowMs = 0L
        val limiter = WindowsAudioDiagnosticsRateLimiter(
            minIntervalMs = 2_000L,
            nowMs = { nowMs },
        )
        val diagnostics = WindowsAudioDiagnostics(selectedCodec = "aac")

        assertTrue(limiter.shouldEmit(1L, diagnostics))
        assertFalse(limiter.shouldEmit(1L, diagnostics.copy(outputFormat = "s16")))
        nowMs = 2_000L
        assertTrue(limiter.shouldEmit(1L, diagnostics.copy(outputFormat = "s16")))
        assertFalse(limiter.shouldEmit(1L, diagnostics.copy(outputFormat = "s16")))
        nowMs = 4_000L
        assertTrue(limiter.shouldEmit(2L, diagnostics.copy(outputFormat = "s16")))
    }

    @Test
    fun diagnosticSignaturesAndFinalMessagesAreBoundedAndRedacted() {
        val oversized = "x".repeat(10_000) + " https://user:secret@example.com/path?token=value"
        val diagnostics = WindowsAudioDiagnostics(
            selectedCodec = oversized,
            activeOutputDevice = oversized,
        )

        val signature = diagnostics.signature()
        val log = formatWindowsAudioDiagnostics("${oversized}\ntrigger", diagnostics)

        assertTrue(signature.length <= 1_024)
        assertTrue(log.length <= 4_096)
        assertFalse(log.contains("secret"))
        assertFalse(log.contains("token=value"))
    }

    @Test
    fun diagnosticValuesStripBidiFormatAndAnsiControls() {
        val value = "\u001B[31mcodec\u202Eevil\u2066name\u2069\u0001\u0085\u001B[0m"
        val diagnostics = WindowsAudioDiagnostics(selectedCodec = value)

        val log = formatWindowsAudioDiagnostics("audio", diagnostics)

        assertFalse(log.contains("\u001B"))
        assertFalse(log.contains("\u202E"))
        assertFalse(log.contains("\u2066"))
        assertFalse(log.contains("\u2069"))
        assertFalse(log.contains("\u0001"))
        assertFalse(log.contains("\u0085"))
    }

    @Test
    fun audioDiagnosticLogsRemoveBidiAndFormatControlsFromMetadata() {
        val log = formatWindowsAudioDiagnostics(
            "load\u202Espoof\u2066",
            WindowsAudioDiagnostics(
                selectedCodec = "eac3\u202E",
                activeOutputDevice = "Speakers\u2066",
            ),
        )

        assertFalse(log.contains("\u202E"))
        assertFalse(log.contains("\u2066"))
        assertFalse(log.any { it.category == CharCategory.FORMAT })
    }

    @Test
    fun mpvTrackCountsAreBoundedBeforeMetadataPolling() {
        assertEquals(64, boundedWindowsMpvTrackCount("9999"))
        assertEquals(0, boundedWindowsMpvTrackCount("-1"))
        assertEquals(0, boundedWindowsMpvTrackCount("not-a-number"))
    }

    @Test
    fun audioUnderrunLogsAreRecognizedWithoutTreatingOtherLogsAsUnderruns() {
        assertTrue(isWindowsAudioUnderrunLog("AO: audio buffer underrun"))
        assertTrue(isWindowsAudioUnderrunLog("audio under-run detected"))
        assertFalse(isWindowsAudioUnderrunLog("audio stream started"))
    }

    @Test
    fun activeOutputDriverWinsOverConfiguredAndLegacyFallbacks() {
        assertEquals(
            "wasapi",
            selectWindowsAudioOutputDriver("wasapi", "configured", "legacy"),
        )
        assertEquals(
            "configured",
            selectWindowsAudioOutputDriver(null, "configured", "legacy"),
        )
        assertEquals(
            "legacy",
            selectWindowsAudioOutputDriver(null, null, "legacy"),
        )
    }

    @Test
    fun underrunsAreCumulativeForThePlayerSession() {
        val tracker = WindowsAudioUnderrunTracker()

        assertFalse(tracker.record(false))
        assertTrue(tracker.record(true))
        assertEquals(1L, tracker.count())
        assertTrue(tracker.record(true))
        assertEquals(2L, tracker.count())
        assertTrue(
            WindowsAudioUnderrunTracker::class.java.declaredMethods.none { method ->
                method.name == "beginLoad"
            },
        )
    }
}
