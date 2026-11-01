package com.nuvio.app.core.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmReportFileStoreTest {
    private val root: File = Files.createTempDirectory("kino-report-store").toFile()
    private val logs = root.resolve("logs").apply { mkdirs() }
    private val store = JvmReportFileStore(logs, "kino.log", root.resolve("crashes"))

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `reads archives oldest first and the active file last`() {
        logs.resolve("kino.log.2").writeText("one\n")
        logs.resolve("kino.log.1").writeText("two\n")
        logs.resolve("kino.log").writeText("three\n")

        assertEquals("one\ntwo\nthree\n", store.readLogTail(1024))
    }

    @Test
    fun `keeps only the newest bytes and drops the partial first line`() {
        logs.resolve("kino.log.1").writeText("old line\n")
        logs.resolve("kino.log").writeText("first line\nsecond line\n")

        assertEquals("second line\n", store.readLogTail(15))
    }

    @Test
    fun `returns null without logs`() {
        assertNull(store.readLogTail(1024))
    }

    @Test
    fun `gzips bytes that decompress to the original`() {
        val text = "kino log line\n".repeat(200)
        val gzipped = store.gzip(text.encodeToByteArray())
        assertTrue(gzipped.size < text.length)
        assertEquals(text, GZIPInputStream(gzipped.inputStream()).readBytes().decodeToString())
    }

    @Test
    fun `stores lists and deletes pending crashes`() {
        store.writeCrash("crash-1", """{"reportId":"crash-1"}""", "java.lang.IllegalStateException: boom")

        val pending = store.pendingCrashes().single()
        assertEquals(StoredCrash("crash-1", """{"reportId":"crash-1"}""", "java.lang.IllegalStateException: boom"), pending)

        store.deleteCrash("crash-1")
        assertTrue(store.pendingCrashes().isEmpty())
    }
}
