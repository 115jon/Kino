package com.nuvio.app.core.diagnostics

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.headersOf
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val LOGBOOK_LOG_FILE_NAME = "kino.log.gz"
internal const val MAX_CRASH_BYTES = 1_000_000
internal const val MAX_LOG_GZIP_BYTES = 10 * 1024 * 1024
private const val TRUNCATED_MARKER = "\n[truncated]"

internal class LogbookUpload(
    val report: LogbookReport,
    val gzippedLog: ByteArray? = null,
    val crash: String? = null,
)

internal sealed interface LogbookResult {
    data class Sent(val id: String, val duplicate: Boolean) : LogbookResult
    data object NotConfigured : LogbookResult
    data object Unauthorized : LogbookResult
    data object TooLarge : LogbookResult
    data class RateLimited(val retryAfterSeconds: Long?) : LogbookResult
    data class ServerError(val status: Int) : LogbookResult
    data class NetworkError(val message: String?) : LogbookResult
}

internal val LogbookResult.isRetryable: Boolean
    get() = this is LogbookResult.RateLimited ||
        this is LogbookResult.NetworkError ||
        (this is LogbookResult.ServerError && status >= 500)

internal class LogbookClient(
    private val baseUrl: String,
    private val ingestKey: String,
    private val http: HttpClient,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && ingestKey.isNotBlank()

    suspend fun send(upload: LogbookUpload): LogbookResult {
        if (!isConfigured) return LogbookResult.NotConfigured
        return try {
            val response = http.post("${baseUrl.trim().trimEnd('/')}/v1/reports") {
                header(HttpHeaders.Authorization, "Bearer ${ingestKey.trim()}")
                setBody(MultiPartFormDataContent(upload.parts()))
            }
            when (response.status) {
                HttpStatusCode.Created, HttpStatusCode.OK -> LogbookResult.Sent(
                    id = parseReportId(response.bodyAsText()) ?: upload.report.reportId,
                    duplicate = response.status == HttpStatusCode.OK,
                )
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> LogbookResult.Unauthorized
                HttpStatusCode.PayloadTooLarge -> LogbookResult.TooLarge
                HttpStatusCode.TooManyRequests -> LogbookResult.RateLimited(
                    parseRetryAfterSeconds(response.headers[HttpHeaders.RetryAfter]),
                )
                else -> LogbookResult.ServerError(response.status.value)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LogbookResult.NetworkError(error.message)
        }
    }

    private fun LogbookUpload.parts(): List<PartData> = buildList {
        add(
            PartData.FormItem(
                value = LogbookJson.encodeToString(LogbookReport.serializer(), report),
                dispose = {},
                partHeaders = partHeaders("report", null, ContentType.Application.Json),
            ),
        )
        gzippedLog?.takeIf { it.size <= MAX_LOG_GZIP_BYTES }?.let { bytes ->
            add(
                PartData.BinaryChannelItem(
                    provider = { ByteReadChannel(bytes) },
                    partHeaders = partHeaders("log", LOGBOOK_LOG_FILE_NAME, ContentType.Application.GZip),
                ),
            )
        }
        crash?.let { trace ->
            add(
                PartData.FormItem(
                    value = trace.truncateUtf8(MAX_CRASH_BYTES),
                    dispose = {},
                    partHeaders = partHeaders("crash", null, ContentType.Text.Plain.withParameter("charset", "utf-8")),
                ),
            )
        }
    }

    private fun partHeaders(name: String, fileName: String?, contentType: ContentType) = headersOf(
        HttpHeaders.ContentDisposition to listOf(
            buildString {
                append("form-data; name=\"").append(name).append('"')
                if (fileName != null) append("; filename=\"").append(fileName).append('"')
            },
        ),
        HttpHeaders.ContentType to listOf(contentType.toString()),
    )

    private fun parseReportId(body: String): String? = runCatching {
        LogbookJson.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun parseRetryAfterSeconds(value: String?): Long? =
    value?.trim()?.toLongOrNull()?.coerceAtLeast(0)

internal fun String.truncateUtf8(maxBytes: Int): String {
    if (encodeToByteArray().size <= maxBytes) return this
    val budget = maxBytes - TRUNCATED_MARKER.encodeToByteArray().size
    var bytes = 0
    var end = 0
    while (end < length) {
        val char = this[end]
        val width = when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            char.isHighSurrogate() -> 4
            else -> 3
        }
        if (bytes + width > budget) break
        bytes += width
        end += if (char.isHighSurrogate()) 2 else 1
    }
    return substring(0, end) + TRUNCATED_MARKER
}
