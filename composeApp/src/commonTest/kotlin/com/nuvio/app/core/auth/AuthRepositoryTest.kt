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

    @Test
    fun `auth error sanitizer hides raw transport details from the user`() {
        val rawMessage = """Unknown Error
            |URL: https://example.supabase.co/auth/v1/token?grant_type=password
            |Headers: {Authorization=[Bearer sb... (len=46)], apikey=[sb... (len=46)]}
            |Http Method: POST
        """.trimMargin()

        assertEquals(
            "Sign-in failed",
            sanitizeAuthErrorMessage(rawMessage, "Sign-in failed"),
        )
    }

    @Test
    fun `auth error sanitizer explains service quota failures without exposing transport details`() {
        val rawMessage = "Service for this project is restricted due to the following violations: exceed_egress_quota"

        assertEquals(
            "The service is temporarily unavailable. Please try again later.",
            sanitizeAuthErrorMessage(rawMessage, "Sign-in failed"),
        )
    }

    @Test
    fun `auth error sanitizer preserves concise server messages`() {
        assertEquals(
            "Invalid login credentials",
            sanitizeAuthErrorMessage("Invalid login credentials", "Sign-in failed"),
        )
    }
}
