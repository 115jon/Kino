package com.nuvio.app.core.logging

import android.content.Context
import android.util.Log
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.nuvio.app.BuildConfig
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private const val androidLogFileName = "nuvio.log"

private val androidTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

internal fun initializeAndroidAppLogging(context: Context) {
    val mode = resolveAppLogMode(
        isDebugBinary = BuildConfig.DEBUG,
        overrideValue = null,
    )
    val policy = AppLogPolicy.forMode(mode)
    val storage = JvmAppLogStorage(File(context.filesDir, "logs"))
    val sink = RollingAppLogFileSink(
        storage = storage,
        activeFileName = androidLogFileName,
        maxFileBytes = policy.maxFileBytes,
        maxArchivedFiles = policy.maxArchivedFiles,
    )
    val sessionId = buildAppLogSessionId(
        epochMillis = System.currentTimeMillis(),
        randomToken = Random.nextInt().toUInt(),
    )

    AppLogging.initialize(
        AppLoggingRuntime(
            mode = mode,
            sessionId = sessionId,
            policy = policy,
            writers = listOf(
                AndroidConsoleLogWriter(
                    minSeverity = policy.consoleMinSeverity,
                    sessionId = sessionId,
                ),
                AppStructuredLogWriter(
                    minSeverity = policy.fileMinSeverity,
                    sessionId = sessionId,
                    timestampProvider = ::androidCurrentTimestamp,
                    threadNameProvider = ::androidCurrentThreadName,
                    sink = sink::append,
                ),
            ),
            logDirectoryPath = storage.directoryPath,
            installCrashHandler = ::installAndroidCrashLogging,
        ),
    )
}

private class AndroidConsoleLogWriter(
    private val minSeverity: Severity,
    private val sessionId: String,
) : LogWriter() {
    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        if (severity.ordinal < minSeverity.ordinal) {
            return
        }

        val line = AppLogLineFormatter.format(
            timestamp = androidCurrentTimestamp(),
            severity = severity,
            tag = tag,
            message = message,
            throwable = throwable,
            threadName = androidCurrentThreadName(),
            sessionId = sessionId,
        )

        when (severity) {
            Severity.Verbose -> Log.v(tag.ifBlank { "App" }, line)
            Severity.Debug -> Log.d(tag.ifBlank { "App" }, line)
            Severity.Info -> Log.i(tag.ifBlank { "App" }, line)
            Severity.Warn -> Log.w(tag.ifBlank { "App" }, line)
            Severity.Error -> Log.e(tag.ifBlank { "App" }, line)
            Severity.Assert -> Log.wtf(tag.ifBlank { "App" }, line)
        }

        throwable?.let {
            val stackTrace = it.stackTraceToString()
            when (severity) {
                Severity.Verbose -> Log.v(tag.ifBlank { "App" }, stackTrace)
                Severity.Debug -> Log.d(tag.ifBlank { "App" }, stackTrace)
                Severity.Info -> Log.i(tag.ifBlank { "App" }, stackTrace)
                Severity.Warn -> Log.w(tag.ifBlank { "App" }, stackTrace)
                Severity.Error -> Log.e(tag.ifBlank { "App" }, stackTrace)
                Severity.Assert -> Log.wtf(tag.ifBlank { "App" }, stackTrace)
            }
        }
    }
}

private class JvmAppLogStorage(
    private val directory: File,
) : AppLogStorage {
    override val directoryPath: String = directory.absolutePath

    override fun ensureDirectory() {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    override fun fileSize(fileName: String): Long =
        directory.resolve(fileName)
            .takeIf(File::exists)
            ?.length()
            ?: 0L

    override fun appendLine(fileName: String, line: String) {
        directory.resolve(fileName).appendText(line + "\n")
    }

    override fun move(sourceFileName: String, targetFileName: String) {
        val sourceFile = directory.resolve(sourceFileName)
        if (!sourceFile.exists()) {
            return
        }

        val targetFile = directory.resolve(targetFileName)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        sourceFile.renameTo(targetFile)
    }

    override fun delete(fileName: String) {
        val targetFile = directory.resolve(fileName)
        if (targetFile.exists()) {
            targetFile.delete()
        }
    }
}

private fun androidCurrentTimestamp(): String =
    androidTimestampFormatter.format(Instant.ofEpochMilli(System.currentTimeMillis()))

private fun androidCurrentThreadName(): String =
    Thread.currentThread().name.takeUnless { it.isBlank() } ?: "main"

private fun installAndroidCrashLogging() {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Logger.withTag("UncaughtException").a(throwable) {
            "uncaught exception on thread=${thread.name}"
        }
        previousHandler?.uncaughtException(thread, throwable)
    }
}
