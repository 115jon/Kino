package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleAudioModelsTest {
    @Test
    fun trackMetadataFallsBackFromPlaceholderLabels() {
        assertEquals("eng", trackMetadataFallbackValue("Unknown", "eng"))
        assertEquals("eng", trackMetadataFallbackValue("", "eng"))
        assertEquals(null, trackMetadataFallbackValue("Unknown", "und"))
        assertEquals("English", trackMetadataFallbackValue("English", "eng"))
    }

    @Test
    fun regionalLanguageCodesUseTheirKnownPrimaryLanguage() {
        assertEquals("en", languagePreferenceCodeForDisplay("en-US"))
        assertEquals("pt-BR", languagePreferenceCodeForDisplay("pt-BR"))
        assertEquals(null, languagePreferenceCodeForDisplay("xx-YY"))
    }

    @Test
    fun audioTrackKeepsReliableMpvMetadata() {
        val track = AudioTrack(
            index = 0,
            id = "2",
            label = "English",
            language = "eng",
            codec = "eac3",
            sampleRate = 48_000,
            channelCount = 6,
            channelLayout = "5.1",
            bitrate = 640_000L,
        )

        assertEquals("eac3", track.codec)
        assertEquals(48_000, track.sampleRate)
        assertEquals(6, track.channelCount)
        assertEquals("5.1", track.channelLayout)
        assertEquals(640_000L, track.bitrate)
    }

    @Test
    fun audioTrackLeavesEmptyMpvMetadataUnknown() {
        val track = AudioTrack(index = 0, id = "1", label = "")

        assertNull(track.codec)
        assertNull(track.sampleRate)
        assertNull(track.channelCount)
        assertNull(track.channelLayout)
        assertNull(track.bitrate)
    }
}
