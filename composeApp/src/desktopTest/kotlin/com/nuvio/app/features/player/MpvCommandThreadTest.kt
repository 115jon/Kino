package com.nuvio.app.features.player

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MpvCommandThreadTest {
    @Test
    fun `posted work runs in order on the command thread`() {
        val thread = MpvCommandThread()
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val caller = Thread.currentThread().name

        thread.post { seen += "a:${Thread.currentThread().name}" }
        thread.post { error("boom") }
        thread.post { seen += "b"; done.countDown() }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("a:Kino-mpv-commands", "b"), seen.toList())
        assertNotEquals("Kino-mpv-commands", caller)
        thread.shutdown()
    }

    @Test
    fun `delayed work runs after its delay and is dropped on shutdown`() {
        val thread = MpvCommandThread()
        val ran = CountDownLatch(1)
        var dropped = false

        thread.postDelayed(20L) { ran.countDown() }
        thread.postDelayed(60_000L) { dropped = true }

        assertTrue(ran.await(5, TimeUnit.SECONDS))
        thread.shutdown()
        thread.post { dropped = true }
        assertFalse(dropped)
    }
}
