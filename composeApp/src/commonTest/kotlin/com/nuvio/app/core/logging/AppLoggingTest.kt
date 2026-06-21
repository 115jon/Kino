package com.nuvio.app.core.logging

import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLoggingTest {

    @Test
    fun `debug mode policy keeps debug logs enabled everywhere`() {
        val policy = AppLogPolicy.forMode(AppLogMode.Debug)

        assertEquals(Severity.Debug, policy.globalMinSeverity)
        assertEquals(Severity.Debug, policy.consoleMinSeverity)
        assertEquals(Severity.Debug, policy.fileMinSeverity)
        assertEquals(1_048_576L, policy.maxFileBytes)
        assertEquals(10, policy.maxArchivedFiles)
    }

    @Test
    fun `release mode policy keeps info on disk and warns on console`() {
        val policy = AppLogPolicy.forMode(AppLogMode.Release)

        assertEquals(Severity.Info, policy.globalMinSeverity)
        assertEquals(Severity.Warn, policy.consoleMinSeverity)
        assertEquals(Severity.Info, policy.fileMinSeverity)
        assertEquals(524_288L, policy.maxFileBytes)
        assertEquals(6, policy.maxArchivedFiles)
    }

    @Test
    fun `formatter includes structured metadata and throwable summary`() {
        val line = AppLogLineFormatter.format(
            timestamp = "2026-06-21T18:00:00.123Z",
            severity = Severity.Error,
            tag = "AuthRepository",
            message = "Email sign-in failed",
            throwable = IllegalStateException("Bad credentials"),
            threadName = "main",
            sessionId = "session-abc123",
        )

        assertEquals(
            "2026-06-21T18:00:00.123Z ERROR [session-abc123] [main] AuthRepository - Email sign-in failed | IllegalStateException: Bad credentials",
            line,
        )
    }

    @Test
    fun `rolling sink rotates active file and retains newest archives`() {
        val storage = FakeAppLogStorage()
        val sink = RollingAppLogFileSink(
            storage = storage,
            activeFileName = "nuvio.log",
            maxFileBytes = 12,
            maxArchivedFiles = 2,
        )

        sink.append("one")
        sink.append("two")
        sink.append("three")
        sink.append("four")

        assertEquals("three\nfour\n", storage.files["nuvio.log"])
        assertEquals("one\ntwo\n", storage.files["nuvio.log.1"])
        assertTrue("nuvio.log.2" !in storage.files)
    }

    @Test
    fun `rolling sink trims oldest archive when retention is exceeded`() {
        val storage = FakeAppLogStorage()
        val sink = RollingAppLogFileSink(
            storage = storage,
            activeFileName = "nuvio.log",
            maxFileBytes = 8,
            maxArchivedFiles = 2,
        )

        sink.append("aaa")
        sink.append("bbb")
        sink.append("ccc")
        sink.append("ddd")
        sink.append("eee")

        assertEquals("eee\n", storage.files["nuvio.log"])
        assertEquals("ccc\nddd\n", storage.files["nuvio.log.1"])
        assertEquals("aaa\nbbb\n", storage.files["nuvio.log.2"])
        assertEquals(3, storage.files.size)
    }
}

private class FakeAppLogStorage : AppLogStorage {
    val files = linkedMapOf<String, String>()

    override val directoryPath: String = "/tmp/nuvio-logs"

    override fun ensureDirectory() = Unit

    override fun fileSize(fileName: String): Long =
        files[fileName]?.encodeToByteArray()?.size?.toLong() ?: 0L

    override fun appendLine(fileName: String, line: String) {
        val current = files[fileName].orEmpty()
        files[fileName] = current + line + "\n"
    }

    override fun move(sourceFileName: String, targetFileName: String) {
        val source = files.remove(sourceFileName) ?: return
        files[targetFileName] = source
    }

    override fun delete(fileName: String) {
        files.remove(fileName)
    }
}
