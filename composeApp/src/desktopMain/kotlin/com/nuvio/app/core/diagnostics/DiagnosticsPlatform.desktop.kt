package com.nuvio.app.core.diagnostics

import com.nuvio.app.core.logging.desktopLogFileName
import com.nuvio.app.core.logging.desktopLogsDirectory
import com.nuvio.app.core.logging.isDesktopDebugRuntime
import java.time.Duration
import java.time.Instant

internal actual object DiagnosticsPlatform {
    actual val crashReportsSupported: Boolean = true

    actual val files: ReportFileStore? by lazy {
        val logs = desktopLogsDirectory()
        JvmReportFileStore(logs, desktopLogFileName, logs.resolveSibling("crashes"))
    }

    actual val platformName: String
        get() = desktopOsFamily()

    actual val packageId: String = "site.x115jon.kino.desktop"

    actual val isDebugBuild: Boolean by lazy { isDesktopDebugRuntime() }

    actual fun warmUp() {
        DesktopDeviceFacts.staticFacts
        DesktopMpvVersion.load()
    }

    actual fun device(): ReportDevice = DesktopDeviceFacts.collect()

    actual fun runtime(): Map<String, String> = buildMap {
        put("jvm", System.getProperty("java.runtime.version").orEmpty())
        put("jvmVendor", System.getProperty("java.vendor").orEmpty())
        put("jvmName", System.getProperty("java.vm.name").orEmpty())
        DesktopMpvVersion.mpv?.let { put("mpv", it) }
        DesktopMpvVersion.ffmpeg?.let { put("ffmpeg", it) }
        DesktopRendererInfo.renderApi?.let { put("renderApi", it) }
        DesktopRendererInfo.renderInfo?.let { put("renderInfo", it) }
        processUptimeSeconds()?.let { put("uptimeSeconds", it.toString()) }
    }.filterValues(String::isNotBlank)

    private fun processUptimeSeconds(): Long? = runCatching {
        ProcessHandle.current().info().startInstant().map { Duration.between(it, Instant.now()).seconds }.orElse(null)
    }.getOrNull()
}

internal fun desktopOsFamily(): String {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        osName.contains("win") -> "windows"
        osName.contains("mac") -> "macos"
        else -> "linux"
    }
}
