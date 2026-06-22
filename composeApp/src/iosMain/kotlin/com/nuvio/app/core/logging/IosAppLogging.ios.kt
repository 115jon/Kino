package com.nuvio.app.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.random.Random

private const val iosLogFileName = "nuvio.log"

private val iosTimestampFormatter = NSDateFormatter().apply {
    dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
}

internal fun initializeIosAppLogging(isDebug: Boolean) {
    val mode = resolveAppLogMode(
        isDebugBinary = isDebug,
        overrideValue = null,
    )
    val policy = AppLogPolicy.forMode(mode)
    val storage = IosAppLogStorage(iosLogsDirectoryPath())
    val sink = RollingAppLogFileSink(
        storage = storage,
        activeFileName = iosLogFileName,
        maxFileBytes = policy.maxFileBytes,
        maxArchivedFiles = policy.maxArchivedFiles,
    )
    val sessionId = buildAppLogSessionId(
        epochMillis = (NSDate().timeIntervalSince1970 * 1000.0).toLong(),
        randomToken = Random.nextInt().toUInt(),
    )

    AppLogging.initialize(
        AppLoggingRuntime(
            mode = mode,
            sessionId = sessionId,
            policy = policy,
            writers = listOf(
                IosConsoleLogWriter(
                    minSeverity = policy.consoleMinSeverity,
                    sessionId = sessionId,
                ),
                AppStructuredLogWriter(
                    minSeverity = policy.fileMinSeverity,
                    sessionId = sessionId,
                    timestampProvider = ::iosCurrentTimestamp,
                    threadNameProvider = ::iosCurrentThreadName,
                    sink = sink::append,
                ),
            ),
            logDirectoryPath = storage.directoryPath,
        ),
    )
}

private class IosConsoleLogWriter(
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

        println(
            AppLogLineFormatter.format(
                timestamp = iosCurrentTimestamp(),
                severity = severity,
                tag = tag,
                message = message,
                throwable = throwable,
                threadName = iosCurrentThreadName(),
                sessionId = sessionId,
            ),
        )
        throwable?.stackTraceToString()?.let(::println)
    }
}

private class IosAppLogStorage(
    override val directoryPath: String,
) : AppLogStorage {
    override fun ensureDirectory() {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directoryPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    override fun fileSize(fileName: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(pathFor(fileName), error = null)
        val value = attributes?.get("NSFileSize")
        return when (value) {
            is Long -> value
            is Number -> value.toLong()
            else -> 0L
        }
    }

    override fun appendLine(fileName: String, line: String) {
        (line + "\n").encodeToByteArray().writeToFile(
            path = pathFor(fileName),
            append = true,
        )
    }

    override fun move(sourceFileName: String, targetFileName: String) {
        val sourcePath = pathFor(sourceFileName)
        if (!NSFileManager.defaultManager.fileExistsAtPath(sourcePath)) {
            return
        }

        val targetPath = pathFor(targetFileName)
        if (NSFileManager.defaultManager.fileExistsAtPath(targetPath)) {
            NSFileManager.defaultManager.removeItemAtPath(targetPath, null)
        }
        NSFileManager.defaultManager.moveItemAtPath(
            srcPath = sourcePath,
            toPath = targetPath,
            error = null,
        )
    }

    override fun delete(fileName: String) {
        val filePath = pathFor(fileName)
        if (NSFileManager.defaultManager.fileExistsAtPath(filePath)) {
            NSFileManager.defaultManager.removeItemAtPath(filePath, null)
        }
    }

    private fun pathFor(fileName: String): String = "$directoryPath/$fileName"
}

private fun iosLogsDirectoryPath(): String =
    NSHomeDirectory().trimEnd('/') + "/Library/Application Support/Nuvio/logs"

private fun iosCurrentTimestamp(): String = iosTimestampFormatter.stringFromDate(NSDate())

private fun iosCurrentThreadName(): String {
    val threadName = NSThread.currentThread.name?.takeIf { it.isNotBlank() }
    return threadName ?: if (NSThread.isMainThread) "main" else "background"
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.writeToFile(
    path: String,
    append: Boolean,
): Boolean = usePinned { pinned ->
    val file = fopen(path, if (append) "ab" else "wb") ?: return false
    try {
        val written = fwrite(
            pinned.addressOf(0),
            1.convert(),
            size.convert(),
            file,
        )
        written.toLong() == size.toLong()
    } finally {
        fclose(file)
    }
}
