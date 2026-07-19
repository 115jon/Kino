package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
