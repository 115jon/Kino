package com.nuvio.app.core.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PreparedReport(
    val report: LogbookReport,
    val gzippedLog: ByteArray?,
)

internal object ManualReports {
    val isConfigured: Boolean
        get() = LogbookService.isConfigured

    suspend fun prepare(
        reportId: String,
        description: String,
        contactEmail: String,
        includeLogs: Boolean,
    ): PreparedReport = withContext(Dispatchers.Default) {
        val redactor = ReportSecrets.redactor()
        PreparedReport(
            report = ReportCollector.collect(
                kind = ReportKind.Manual,
                reportId = reportId,
                description = description,
                contactEmail = contactEmail,
                redactor = redactor,
                includeLogs = includeLogs,
            ),
            gzippedLog = if (includeLogs) ReportCollector.gzippedLogTail(redactor) else null,
        )
    }

    suspend fun send(prepared: PreparedReport): LogbookResult = withContext(Dispatchers.Default) {
        LogbookService.client.send(LogbookUpload(prepared.report, prepared.gzippedLog))
    }
}
