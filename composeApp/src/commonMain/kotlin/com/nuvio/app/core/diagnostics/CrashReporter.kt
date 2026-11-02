package com.nuvio.app.core.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object CrashReporter {
    private const val MAX_PENDING_CRASHES = 5
    private const val STARTUP_DELAY_MS = 5_000L
    private val log = Logger.withTag("CrashReporter")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(STARTUP_DELAY_MS)
            runCatching { DiagnosticsPlatform.warmUp() }
            runCatching { uploadPending(LogbookService.client) }
                .onFailure { log.w(it) { "Pending crash upload failed" } }
        }
    }

    fun capture(threadName: String, throwable: Throwable) {
        try {
            if (!CrashReportSettings.isSupported) return
            CrashReportSettings.ensureLoaded()
            if (!CrashReportSettings.enabled.value) return
            val files = DiagnosticsPlatform.files ?: return
            val redactor = ReportSecrets.redactor()
            val reportId = ReportCollector.newReportId()
            val summary = throwable::class.simpleName.orEmpty().ifBlank { "Throwable" } +
                (throwable.message?.let { ": $it" } ?: "")
            val report = ReportCollector.collect(
                kind = ReportKind.Crash,
                reportId = reportId,
                description = summary.take(500),
                contactEmail = null,
                redactor = redactor,
                includeLogs = true,
                tags = mapOf("thread" to threadName),
            )
            files.writeCrash(
                reportId = reportId,
                reportJson = LogbookJson.encodeToString(LogbookReport.serializer(), report),
                stackTrace = redactor.redact(throwable.stackTraceToString()),
            )
        } catch (_: Throwable) {
        }
    }

    suspend fun uploadPending(
        client: LogbookClient,
        policy: LogbookRetryPolicy = LogbookRetryPolicy(),
        sleep: (suspend (Long) -> Unit)? = null,
    ) {
        val files = DiagnosticsPlatform.files ?: return
        val pending = files.pendingCrashes()
        if (pending.isEmpty()) return
        CrashReportSettings.ensureLoaded()
        if (!CrashReportSettings.enabled.value) {
            pending.forEach { files.deleteCrash(it.reportId) }
            return
        }
        pending.drop(MAX_PENDING_CRASHES).forEach { files.deleteCrash(it.reportId) }
        if (!client.isConfigured) return
        val redactor = ReportSecrets.redactor()
        for (crash in pending.take(MAX_PENDING_CRASHES)) {
            val report = runCatching { LogbookJson.decodeFromString(LogbookReport.serializer(), crash.reportJson) }.getOrNull()
            if (report == null) {
                files.deleteCrash(crash.reportId)
                continue
            }
            val upload = LogbookUpload(report, ReportCollector.gzippedLogTail(redactor), crash.stackTrace)
            var result = sendWithRetry(client, upload, policy, sleep)
            if (result == LogbookResult.TooLarge && upload.gzippedLog != null) {
                result = sendWithRetry(client, LogbookUpload(report, null, crash.stackTrace), policy, sleep)
            }
            when (result) {
                is LogbookResult.Sent -> {
                    files.deleteCrash(crash.reportId)
                    log.i { "Uploaded crash report ${result.id}" }
                }
                LogbookResult.TooLarge -> files.deleteCrash(crash.reportId)
                else -> {
                    log.w { "Crash report upload stopped: $result" }
                    return
                }
            }
        }
    }
}
