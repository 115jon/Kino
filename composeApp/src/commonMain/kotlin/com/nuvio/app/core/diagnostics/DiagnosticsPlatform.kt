package com.nuvio.app.core.diagnostics

internal interface ReportFileStore {
    fun readLogTail(maxBytes: Int): String?
    fun gzip(bytes: ByteArray): ByteArray
    fun writeCrash(reportId: String, reportJson: String, stackTrace: String)
    fun pendingCrashes(): List<StoredCrash>
    fun deleteCrash(reportId: String)
}

internal data class StoredCrash(
    val reportId: String,
    val reportJson: String,
    val stackTrace: String,
)

internal expect object DiagnosticsPlatform {
    val crashReportsSupported: Boolean
    val files: ReportFileStore?
    val platformName: String
    val packageId: String
    val isDebugBuild: Boolean
    fun warmUp()
    fun device(): ReportDevice
    fun runtime(): Map<String, String>
}

internal expect object DiagnosticsStorage {
    fun loadCrashReportsEnabled(): Boolean?
    fun saveCrashReportsEnabled(enabled: Boolean)
    fun loadInstallId(): String?
    fun saveInstallId(installId: String)
}
