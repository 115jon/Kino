package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRepositoryTest {

    @Test
    fun `normalize auth email trims surrounding whitespace`() {
        assertEquals(
            "person@example.com",
            normalizeAuthEmail("  person@example.com  \n"),
        )
    }

    @Test
    fun `normalize auth email preserves internal characters`() {
        assertEquals(
            "first.last+tag@example.com",
            normalizeAuthEmail("first.last+tag@example.com"),
        )
    }

    @Test
    fun `auth email diagnostics captures surrounding whitespace and control characters`() {
        val diagnostics = buildAuthEmailDiagnostics("\t person@example.com \r\n")

        assertEquals(23, diagnostics.originalLength)
        assertEquals(18, diagnostics.normalizedLength)
        assertTrue(diagnostics.normalizationChanged)
        assertTrue(diagnostics.hadLeadingWhitespace)
        assertTrue(diagnostics.hadTrailingWhitespace)
        assertFalse(diagnostics.hasInternalWhitespace)
        assertTrue(diagnostics.hasControlCharacters)
    }

    @Test
    fun `auth email diagnostics detects internal whitespace without logging the email`() {
        val diagnostics = buildAuthEmailDiagnostics("person @example.com")

        assertFalse(diagnostics.normalizationChanged)
        assertTrue(diagnostics.hasInternalWhitespace)
        assertFalse(diagnostics.hasControlCharacters)
        assertEquals(
            "emailMetrics={rawLen=19, normalizedLen=19, changed=false, leadingWs=false, trailingWs=false, internalWs=true, controlChars=false}",
            diagnostics.toLogFields(),
        )
    }
}
