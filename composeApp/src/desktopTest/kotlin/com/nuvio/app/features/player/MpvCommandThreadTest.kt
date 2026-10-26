package com.nuvio.app.features.player

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MpvCommandThreadTest {
    @Test
    fun `posted work runs in order on the command thread and survives a failing task`() {
        val thread = MpvCommandThread()
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val caller = Thread.currentThread().name

        thread.post { seen += "a:${Thread.currentThread().name}" }
        thread.post { error("boom") }
        thread.post {
            seen += "b"
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("a:Kino-mpv-commands", "b"), seen.toList())
        assertNotEquals("Kino-mpv-commands", caller)
        thread.shutdown()
        assertTrue(thread.awaitTermination(5_000L))
    }

    @Test
    fun `shutdown drops pending delayed work and rejects new posts`() {
        val thread = MpvCommandThread()
        val ran = CountDownLatch(1)
        val delayedRan = AtomicBoolean(false)
        val postedAfterShutdownRan = AtomicBoolean(false)

        thread.postDelayed(20L) { ran.countDown() }
        thread.postDelayed(60_000L) { delayedRan.set(true) }
        assertTrue(ran.await(5, TimeUnit.SECONDS))

        thread.shutdown()
        thread.post { postedAfterShutdownRan.set(true) }

        assertTrue(thread.awaitTermination(5_000L))
        assertFalse(delayedRan.get())
        assertFalse(postedAfterShutdownRan.get())
    }
}
