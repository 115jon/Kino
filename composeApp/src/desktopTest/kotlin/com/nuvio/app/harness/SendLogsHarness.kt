package com.nuvio.app.harness

import co.touchlab.kermit.Logger
import com.nuvio.app.core.diagnostics.DiagnosticsPlatform
import com.nuvio.app.core.diagnostics.LogbookResult
import com.nuvio.app.core.diagnostics.ManualReports
import com.nuvio.app.core.diagnostics.ReportCollector
import com.nuvio.app.core.logging.initializeDesktopAppLogging
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main() {
    initializeDesktopAppLogging()
    Logger.withTag("SendLogsHarness").i {
        "addon https://torrentio.strem.fun/realdebrid=HARNESSFAKEKEY1234567890/manifest.json Authorization: Bearer harness-fake-token-123456"
    }
    DiagnosticsPlatform.warmUp()
    if (!ManualReports.isConfigured) {
        println("not configured")
        exitProcess(2)
    }
    val result = runBlocking {
        val prepared = ManualReports.prepare(
            reportId = ReportCollector.newReportId(),
            description = System.getProperty("kino.sendlogs.description") ?: "Send logs harness report",
            contactEmail = System.getProperty("kino.sendlogs.email").orEmpty(),
            includeLogs = true,
        )
        println("reportId=${prepared.report.reportId} logGzipBytes=${prepared.gzippedLog?.size ?: 0}")
        ManualReports.send(prepared)
    }
    println("result=$result")
    exitProcess(if (result is LogbookResult.Sent) 0 else 1)
}
