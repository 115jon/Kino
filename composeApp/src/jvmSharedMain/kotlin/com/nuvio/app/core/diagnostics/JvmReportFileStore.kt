package com.nuvio.app.core.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream

internal class JvmReportFileStore(
    private val logDirectory: File,
    private val logFileName: String,
    private val crashDirectory: File,
) : ReportFileStore {
    override fun readLogTail(maxBytes: Int): String? {
        val chunks = ArrayDeque<ByteArray>()
        var remaining = maxBytes
        var truncated = false
        for (file in logFilesNewestFirst()) {
            if (remaining <= 0) {
                truncated = true
                break
            }
            val bytes = readTail(file, remaining)
            if (bytes.size.toLong() < file.length()) truncated = true
            chunks.addFirst(bytes)
            remaining -= bytes.size
        }
        if (chunks.isEmpty()) return null
        val text = chunks.joinToString(separator = "") { it.decodeToString() }
        return if (truncated) text.substringAfter('\n', text) else text
    }

    override fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    override fun writeCrash(reportId: String, reportJson: String, stackTrace: String) {
        crashDirectory.mkdirs()
        crashDirectory.resolve("$reportId$TRACE_SUFFIX").writeText(stackTrace)
        crashDirectory.resolve("$reportId$REPORT_SUFFIX").writeText(reportJson)
    }

    override fun pendingCrashes(): List<StoredCrash> =
        crashDirectory.listFiles { file -> file.isFile && file.name.endsWith(REPORT_SUFFIX) }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .mapNotNull { reportFile ->
                val reportId = reportFile.name.removeSuffix(REPORT_SUFFIX)
                runCatching {
                    StoredCrash(
                        reportId = reportId,
                        reportJson = reportFile.readText(),
                        stackTrace = crashDirectory.resolve("$reportId$TRACE_SUFFIX").takeIf(File::isFile)?.readText().orEmpty(),
                    )
                }.getOrNull()
            }

    override fun deleteCrash(reportId: String) {
        crashDirectory.resolve("$reportId$REPORT_SUFFIX").delete()
        crashDirectory.resolve("$reportId$TRACE_SUFFIX").delete()
    }

    private fun logFilesNewestFirst(): List<File> = buildList {
        logDirectory.resolve(logFileName).takeIf(File::isFile)?.let(::add)
        var index = 1
        while (true) {
            val archive = logDirectory.resolve("$logFileName.$index").takeIf(File::isFile) ?: break
            add(archive)
            index++
        }
    }

    private fun readTail(file: File, maxBytes: Int): ByteArray =
        RandomAccessFile(file, "r").use { access ->
            val length = access.length()
            val size = minOf(length, maxBytes.toLong()).toInt()
            access.seek(length - size)
            ByteArray(size).also(access::readFully)
        }

    private companion object {
        const val REPORT_SUFFIX = ".report.json"
        const val TRACE_SUFFIX = ".crash.txt"
    }
}
