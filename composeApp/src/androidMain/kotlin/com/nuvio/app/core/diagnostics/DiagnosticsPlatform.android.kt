package com.nuvio.app.core.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.nuvio.app.core.logging.androidLogFileName
import java.io.File

internal actual object DiagnosticsPlatform {
    private var appContext: Context? = null
    private var cachedFiles: ReportFileStore? = null

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        DiagnosticsStorage.initialize(applicationContext)
    }

    actual val crashReportsSupported: Boolean = true

    actual val files: ReportFileStore?
        get() = cachedFiles ?: appContext?.let { context ->
            JvmReportFileStore(
                logDirectory = File(context.filesDir, "logs"),
                logFileName = androidLogFileName,
                crashDirectory = File(context.filesDir, "crashes"),
            ).also { cachedFiles = it }
        }

    actual val platformName: String
        get() = if (appContext?.let(AndroidDeviceFacts::isTelevision) == true) "android-tv" else "android"

    actual val packageId: String
        get() = appContext?.packageName.orEmpty()

    actual val isDebugBuild: Boolean
        get() = appContext?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } == true

    actual fun warmUp() {
        AndroidDeviceFacts.gpu
    }

    actual fun device(): ReportDevice = AndroidDeviceFacts.collect(appContext)

    actual fun runtime(): Map<String, String> = buildMap {
        put("art", System.getProperty("java.vm.version").orEmpty())
        put("sdk", Build.VERSION.SDK_INT.toString())
        put("securityPatch", Build.VERSION.SECURITY_PATCH.orEmpty())
        put("abis", Build.SUPPORTED_ABIS.joinToString(","))
        put("uptimeSeconds", ((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000).toString())
    }.filterValues(String::isNotBlank)
}
