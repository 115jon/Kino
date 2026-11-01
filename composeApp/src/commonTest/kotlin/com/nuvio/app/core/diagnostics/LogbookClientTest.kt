package com.nuvio.app.core.diagnostics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogbookClientTest {
    private val report = LogbookReport(
        reportId = "6f1c1a3e-2a4b-4c55-9b77-0d6f7e8a9b10",
        kind = ReportKind.Manual,
        createdAt = "2026-11-01T10:00:00Z",
        app = ReportApp(platform = "windows", version = "0.5.0", build = "5", channel = "stable", packageId = "site.x115jon.kino.desktop"),
        device = ReportDevice(os = "Windows 11", displays = listOf(ReportDisplay(2560, 1440, 1.5, 144.0))),
        runtime = mapOf("jvm" to "21"),
        user = null,
        installId = "install",
        sessionId = "session",
        description = "Playback stops",
        contactEmail = "me@example.com",
    )

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Pair<LogbookClient, MockEngine> {
        val engine = MockEngine(handler)
        return LogbookClient("https://logs.example/", "ingest-key", HttpClient(engine)) to engine
    }

    @Test
    fun `posts multipart report with bearer key and parses created id`() = runTest {
        var body = ""
        val (client, engine) = client { request ->
            body = request.body.toByteArray().decodeToString()
            respond("""{"id":"rep_123"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val upload = LogbookUpload(report, gzippedLog = byteArrayOf(0x1f, 0x8b.toByte(), 1), crash = "java.lang.IllegalStateException")

        assertEquals(LogbookResult.Sent("rep_123", duplicate = false), client.send(upload))
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://logs.example/v1/reports", request.url.toString())
        assertEquals("Bearer ingest-key", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
        assertTrue("Content-Disposition: form-data; name=\"report\"" in body)
        assertTrue("Content-Type: application/json" in body)
        assertTrue("\"schemaVersion\":1" in body)
        assertTrue("\"kind\":\"manual\"" in body)
        assertTrue("\"user\":null" in body)
        assertTrue("\"app\":{\"id\":\"kino\",\"platform\":\"windows\"" in body)
        assertTrue("Content-Disposition: form-data; name=\"log\"; filename=\"kino.log.gz\"" in body)
        assertTrue("Content-Type: application/gzip" in body)
        assertTrue("Content-Disposition: form-data; name=\"crash\"" in body)
        assertTrue("java.lang.IllegalStateException" in body)
    }

    @Test
    fun `treats repeated report id as sent duplicate`() = runTest {
        val (client, _) = client { respond("""{"id":"rep_123"}""", HttpStatusCode.OK) }
        assertEquals(LogbookResult.Sent("rep_123", duplicate = true), client.send(LogbookUpload(report)))
    }

    @Test
    fun `maps error statuses`() = runTest {
        assertEquals(LogbookResult.Unauthorized, client { respond("", HttpStatusCode.Unauthorized) }.first.send(LogbookUpload(report)))
        assertEquals(LogbookResult.TooLarge, client { respond("", HttpStatusCode.PayloadTooLarge) }.first.send(LogbookUpload(report)))
        assertEquals(
            LogbookResult.RateLimited(30),
            client { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30")) }.first.send(LogbookUpload(report)),
        )
        assertEquals(LogbookResult.ServerError(503), client { respond("", HttpStatusCode.ServiceUnavailable) }.first.send(LogbookUpload(report)))
        assertTrue(client { error("offline") }.first.send(LogbookUpload(report)) is LogbookResult.NetworkError)
    }

    @Test
    fun `skips the network when not configured`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Created) }
        val client = LogbookClient("", "", HttpClient(engine))
        assertEquals(LogbookResult.NotConfigured, client.send(LogbookUpload(report)))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `retries with backoff and honours retry after`() = runTest {
        val statuses = ArrayDeque(listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.BadGateway, HttpStatusCode.Created))
        val (client, engine) = client {
            when (val status = statuses.removeFirst()) {
                HttpStatusCode.TooManyRequests -> respond("", status, headersOf(HttpHeaders.RetryAfter, "7"))
                HttpStatusCode.Created -> respond("""{"id":"rep_9"}""", status)
                else -> respond("", status)
            }
        }
        val sleeps = mutableListOf<Long>()
        val policy = LogbookRetryPolicy(initialDelayMs = 1_000, multiplier = 4)
        val result = sendWithRetry(client, LogbookUpload(report), policy, sleep = { sleeps += it })

        assertEquals(LogbookResult.Sent("rep_9", duplicate = false), result)
        assertEquals(listOf(7_000L, 4_000L), sleeps)
        assertEquals(3, engine.requestHistory.size)
    }

    @Test
    fun `stops retrying on client errors and long retry after`() = runTest {
        val (unauthorized, unauthorizedEngine) = client { respond("", HttpStatusCode.Unauthorized) }
        assertEquals(LogbookResult.Unauthorized, sendWithRetry(unauthorized, LogbookUpload(report), sleep = {}))
        assertEquals(1, unauthorizedEngine.requestHistory.size)

        val (limited, limitedEngine) = client {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600"))
        }
        assertEquals(LogbookResult.RateLimited(3600), sendWithRetry(limited, LogbookUpload(report), sleep = {}))
        assertEquals(1, limitedEngine.requestHistory.size)
    }

    @Test
    fun `truncates crash text to the byte cap and drops oversized logs`() = runTest {
        var body = ""
        val (client, _) = client { request ->
            body = request.body.toByteArray().decodeToString()
            respond("""{"id":"rep_1"}""", HttpStatusCode.Created)
        }
        val crash = "at frame\n".repeat(200_000)
        client.send(LogbookUpload(report, gzippedLog = ByteArray(MAX_LOG_GZIP_BYTES + 1), crash = crash))

        assertTrue("name=\"log\"" !in body)
        val crashPart = body.substringAfter("name=\"crash\"").substringAfter("\r\n\r\n").substringBefore("\r\n--")
        assertTrue(crashPart.encodeToByteArray().size <= MAX_CRASH_BYTES)
        assertTrue(crashPart.endsWith("[truncated]"))
    }

    @Test
    fun `utf8 truncation never splits a character`() {
        val text = "\u00e9".repeat(10)
        val truncated = text.truncateUtf8(15)
        assertTrue(truncated.encodeToByteArray().size <= 15)
        assertTrue(truncated.startsWith("\u00e9"))
        assertEquals("short", "short".truncateUtf8(15))
    }
}
