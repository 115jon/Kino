package com.nuvio.app.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportRedactorTest {
    private val redactor = ReportRedactor()

    @Test
    fun `redacts supabase jwt anywhere in a line`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val result = redactor.redact("session restored with $jwt for user")
        assertFalse(jwt in result)
        assertEquals("session restored with [redacted] for user", result)
    }

    @Test
    fun `redacts authorization and api key headers`() {
        assertEquals("Authorization: [redacted]", redactor.redact("Authorization: Bearer abcdefghijklmnop123"))
        assertEquals("""{"apikey":"[redacted]"}""", redactor.redact("""{"apikey":"sb_publishable_abc123def456"}"""))
        assertEquals("x-api-key=[redacted]", redactor.redact("x-api-key=ABCDEF0123456789"))
        assertEquals("sent Bearer [redacted] upstream", redactor.redact("sent Bearer abc.def-ghi_jkl upstream"))
    }

    @Test
    fun `redacts secret fields in json and key value text`() {
        val result = redactor.redact("""{"access_token":"abc123","refresh_token":"def456","name":"Kino"} password=hunter22 clientSecret: s3cr3t""")
        assertEquals(
            """{"access_token":"[redacted]","refresh_token":"[redacted]","name":"Kino"} password=[redacted] clientSecret: [redacted]""",
            result,
        )
    }

    @Test
    fun `redacts tmdb api keys in query strings and bare form`() {
        val key = "0123456789abcdef0123456789abcdef"
        assertEquals(
            "GET https://api.themoviedb.org/3/movie/550?api_key=[redacted]&language=en-US",
            redactor.redact("GET https://api.themoviedb.org/3/movie/550?api_key=$key&language=en-US"),
        )
        assertEquals("tmdb key [redacted] loaded", redactor.redact("tmdb key $key loaded"))
    }

    @Test
    fun `redacts debrid keys embedded in stremio addon paths`() {
        val torrentio = "https://torrentio.strem.fun/sort=qualitysize|qualityfilter=480p,scr|realdebrid=ABCDEF1234567890ABCDEF12/manifest.json"
        assertEquals(
            "https://torrentio.strem.fun/sort=qualitysize|qualityfilter=480p,scr|realdebrid=[redacted]/manifest.json",
            redactor.redact(torrentio),
        )
        val premiumize = "https://torrentio.strem.fun/premiumize=shortkey9/stream/movie/tt0111161.json"
        assertEquals(
            "https://torrentio.strem.fun/premiumize=[redacted]/stream/movie/tt0111161.json",
            redactor.redact(premiumize),
        )
    }

    @Test
    fun `redacts encoded config and uuid path segments but keeps routing segments`() {
        val config = "eyJkZWJyaWRTZXJ2aWNlIjoicmVhbGRlYnJpZCIsImRlYnJpZEFwaUtleSI6IkFCQ0QxMjM0In0"
        assertEquals(
            "https://comet.elfhosted.com/[redacted]/stream/series/tt0944947:1:2.json",
            redactor.redact("https://comet.elfhosted.com/$config/stream/series/tt0944947:1:2.json"),
        )
        assertEquals(
            "stremio://aiostreams.example/stremio/[redacted]/[redacted]/manifest.json",
            redactor.redact("stremio://aiostreams.example/stremio/550e8400-e29b-41d4-a716-446655440000/Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MA==/manifest.json"),
        )
    }

    @Test
    fun `redacts tokens in addon query strings user info and fragments`() {
        assertEquals(
            "https://[redacted]@addon.example/manifest.json?token=[redacted]&lang=en#[redacted]",
            redactor.redact("https://user:pass@addon.example/manifest.json?token=abc&lang=en#access_token=xyz"),
        )
    }

    @Test
    fun `leaves ordinary urls and log text alone`() {
        val line = "2026-11-01T10:00:00.000Z INFO [abc-123] [main] AddonRepository - loaded https://v3-cinemeta.strem.io/manifest.json catalog=top type=movie id=tt0111161"
        assertEquals(line, redactor.redact(line))
    }

    @Test
    fun `redacts known secret values verbatim`() {
        val known = ReportRedactor(listOf("my-ingest-key-123", "short"))
        assertEquals("key [redacted] used, short kept", known.redact("key my-ingest-key-123 used, short kept"))
    }

    @Test
    fun `does not double redact`() {
        val once = redactor.redact("token=abc https://x.example/manifest.json?api_key=0123456789abcdef0123456789abcdef")
        assertEquals(once, redactor.redact(once))
        assertFalse("]]" in once)
    }

    @Test
    fun `settings snapshot drops secret keys and redacts values`() {
        val sanitized = sanitizeSettings(
            mapOf(
                "player.introDbApiKey" to "abc",
                "player.animeSkipClientId" to "def",
                "player.decoderPriority" to "1",
                "addons.0" to "Torrentio | on | https://torrentio.strem.fun/realdebrid=ABCDEF1234567890ABCDEF12/manifest.json",
            ),
            redactor,
        )
        assertEquals(setOf("player.decoderPriority", "addons.0"), sanitized.keys)
        assertTrue(sanitized.getValue("addons.0").endsWith("realdebrid=[redacted]/manifest.json"))
    }

    @Test
    fun `flattens data class text into dotted keys`() {
        val flattened = flattenDataClassText(
            "player",
            "PlayerSettingsUiState(useLibass=true, subtitleStyle=SubtitleStyleState(size=24, color=White), languages=[en, fr], regex=)",
        )
        assertEquals(
            mapOf(
                "player.useLibass" to "true",
                "player.subtitleStyle.size" to "24",
                "player.subtitleStyle.color" to "White",
                "player.languages" to "[en, fr]",
                "player.regex" to "",
            ),
            flattened,
        )
    }
}
