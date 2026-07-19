package com.nuvio.app.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private const val desktopLogFileName = "kino.log"

private val desktopTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

internal fun initializeDesktopAppLogging() {
    val mode = resolveAppLogMode(
        isDebugBinary = isDesktopDebugRuntime(),
        overrideValue = System.getProperty("nuvio.logging.mode") ?: System.getenv("NUVIO_LOG_MODE"),
    )
    val policy = AppLogPolicy.forMode(mode)
    val storage = JvmAppLogStorage(desktopLogsDirectory())
    val sink = RollingAppLogFileSink(
        storage = storage,
        activeFileName = desktopLogFileName,
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
                DesktopConsoleLogWriter(
                    minSeverity = policy.consoleMinSeverity,
                    sessionId = sessionId,
                ),
                AppStructuredLogWriter(
                    minSeverity = policy.fileMinSeverity,
                    sessionId = sessionId,
                    timestampProvider = ::desktopCurrentTimestamp,
                    threadNameProvider = ::desktopCurrentThreadName,
                    sink = sink::append,
                ),
            ),
            logDirectoryPath = storage.directoryPath,
            installCrashHandler = ::installDesktopCrashLogging,
        ),
    )
}

private class DesktopConsoleLogWriter(
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
            timestamp = desktopCurrentTimestamp(),
            severity = severity,
            tag = tag,
            message = message,
            throwable = throwable,
            threadName = desktopCurrentThreadName(),
            sessionId = sessionId,
        )

        val stream = if (severity.ordinal >= Severity.Warn.ordinal) System.err else System.out
        stream.println(line)
        throwable?.stackTraceToString()?.let(stream::println)
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

private fun desktopCurrentTimestamp(): String =
    desktopTimestampFormatter.format(Instant.ofEpochMilli(System.currentTimeMillis()))

private fun desktopCurrentThreadName(): String =
    Thread.currentThread().name.takeUnless { it.isBlank() } ?: "main"

private fun isDesktopDebugRuntime(): Boolean {
    val classPath = System.getProperty("java.class.path").orEmpty().lowercase()
    return classPath.contains("/classes/kotlin/desktop") ||
        classPath.contains("\\classes\\kotlin\\desktop") ||
        classPath.contains("/composeapp/build/") ||
        classPath.contains("\\composeapp\\build\\")
}

private fun desktopLogsDirectory(): File {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        osName.contains("win") -> {
            val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), "AppData/Local").absolutePath
            File(root).resolve("Kino").resolve("logs")
        }

        osName.contains("mac") -> {
            File(System.getProperty("user.home"))
                .resolve("Library")
                .resolve("Application Support")
                .resolve("Kino")
                .resolve("logs")
        }

        else -> {
            File(System.getProperty("user.home"))
                .resolve(".local")
                .resolve("share")
                .resolve("Kino")
                .resolve("logs")
        }
    }
}

private fun installDesktopCrashLogging() {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Logger.withTag("UncaughtException").a(throwable) {
            "uncaught exception on thread=${thread.name}"
        }
        previousHandler?.uncaughtException(thread, throwable)
    }
}
