package com.nuvio.app.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

internal enum class AppLogMode {
    Debug,
    Release,
}

internal data class AppLogPolicy(
    val mode: AppLogMode,
    val globalMinSeverity: Severity,
    val consoleMinSeverity: Severity,
    val fileMinSeverity: Severity,
    val maxFileBytes: Long,
    val maxArchivedFiles: Int,
) {
    companion object {
        fun forMode(mode: AppLogMode): AppLogPolicy = when (mode) {
            AppLogMode.Debug -> AppLogPolicy(
                mode = mode,
                globalMinSeverity = Severity.Debug,
                consoleMinSeverity = Severity.Debug,
                fileMinSeverity = Severity.Debug,
                maxFileBytes = 1_048_576L,
                maxArchivedFiles = 10,
            )

            AppLogMode.Release -> AppLogPolicy(
                mode = mode,
                globalMinSeverity = Severity.Info,
                consoleMinSeverity = Severity.Warn,
                fileMinSeverity = Severity.Info,
                maxFileBytes = 524_288L,
                maxArchivedFiles = 6,
            )
        }
    }
}

internal data class AppLoggingRuntime(
    val mode: AppLogMode,
    val sessionId: String,
    val policy: AppLogPolicy,
    val writers: List<LogWriter>,
    val logDirectoryPath: String?,
    val installCrashHandler: (() -> Unit)? = null,
)

internal object AppLogging {
    private val initLock = Any()
    private var initialized = false

    fun initialize(runtime: AppLoggingRuntime) {
        synchronized(initLock) {
            if (initialized) {
                return
            }

            Logger.setMinSeverity(runtime.policy.globalMinSeverity)
            Logger.setLogWriters(runtime.writers)
            runtime.installCrashHandler?.invoke()
            initialized = true

            Logger.withTag("AppLogging").i {
                "initialized mode=${runtime.mode.name.lowercase()} session=${runtime.sessionId} globalMin=${runtime.policy.globalMinSeverity.name.uppercase()} consoleMin=${runtime.policy.consoleMinSeverity.name.uppercase()} fileMin=${runtime.policy.fileMinSeverity.name.uppercase()} logsDirectory=${runtime.logDirectoryPath ?: "disabled"}"
            }
        }
    }
}

internal fun resolveAppLogMode(
    isDebugBinary: Boolean,
    overrideValue: String?,
): AppLogMode {
    val normalizedOverride = overrideValue
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    return when (normalizedOverride) {
        "debug", "dev", "development" -> AppLogMode.Debug
        "release", "prod", "production" -> AppLogMode.Release
        else -> if (isDebugBinary) AppLogMode.Debug else AppLogMode.Release
    }
}

internal fun buildAppLogSessionId(
    epochMillis: Long,
    randomToken: UInt,
): String = "${epochMillis.toString(16)}-${randomToken.toString(16).padStart(8, '0')}"

internal object AppLogLineFormatter {
    fun format(
        timestamp: String,
        severity: Severity,
        tag: String,
        message: String,
        throwable: Throwable?,
        threadName: String,
        sessionId: String,
    ): String {
        val normalizedTag = tag.ifBlank { "App" }
        val throwableSuffix = throwable?.toLogSummary()?.let { " | $it" }.orEmpty()
        return "$timestamp ${severity.name.uppercase()} [$sessionId] [$threadName] $normalizedTag - $message$throwableSuffix"
    }
}

internal interface AppLogStorage {
    val directoryPath: String

    fun ensureDirectory()

    fun fileSize(fileName: String): Long

    fun appendLine(fileName: String, line: String)

    fun move(sourceFileName: String, targetFileName: String)

    fun delete(fileName: String)
}

internal class RollingAppLogFileSink(
    private val storage: AppLogStorage,
    private val activeFileName: String,
    private val maxFileBytes: Long,
    private val maxArchivedFiles: Int,
) {
    init {
        require(activeFileName.isNotBlank())
        require(maxFileBytes > 0L)
        require(maxArchivedFiles >= 0)
    }

    @Synchronized
    fun append(line: String) {
        storage.ensureDirectory()

        val nextEntryBytes = line.encodeToByteArray().size.toLong() + 1L
        val currentFileSize = storage.fileSize(activeFileName)
        if (currentFileSize > 0L && currentFileSize + nextEntryBytes > maxFileBytes) {
            rotateArchives()
        }

        storage.appendLine(activeFileName, line)
    }

    private fun rotateArchives() {
        if (maxArchivedFiles == 0) {
            storage.delete(activeFileName)
            return
        }

        storage.delete("$activeFileName.$maxArchivedFiles")
        for (index in maxArchivedFiles - 1 downTo 1) {
            storage.move("$activeFileName.$index", "$activeFileName.${index + 1}")
        }
        storage.move(activeFileName, "$activeFileName.1")
    }
}

internal class AppStructuredLogWriter(
    private val minSeverity: Severity,
    private val sessionId: String,
    private val timestampProvider: () -> String,
    private val threadNameProvider: () -> String,
    private val sink: (String) -> Unit,
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

        sink(
            AppLogLineFormatter.format(
                timestamp = timestampProvider(),
                severity = severity,
                tag = tag,
                message = message,
                throwable = throwable,
                threadName = threadNameProvider(),
                sessionId = sessionId,
            ),
        )

        throwable
            ?.stackTraceToString()
            ?.lineSequence()
            ?.map(String::trimEnd)
            ?.filter(String::isNotBlank)
            ?.forEach { stackLine ->
                sink("    $stackLine")
            }
    }
}

private fun Throwable.toLogSummary(): String {
    val rawType = this::class.toString()
    val normalizedType = rawType
        .substringAfterLast('.')
        .substringAfterLast('$')
        .substringAfter(' ')
        .ifBlank { "Throwable" }
    val normalizedMessage = message?.trim().orEmpty()
    return if (normalizedMessage.isEmpty()) {
        normalizedType
    } else {
        "$normalizedType: $normalizedMessage"
    }
}
