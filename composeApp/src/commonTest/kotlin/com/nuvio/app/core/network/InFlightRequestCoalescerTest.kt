package com.nuvio.app.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InFlightRequestCoalescerTest {
    private fun TestScope.supervisedScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))

    @Test
    fun `concurrent identical requests share one call`() = runTest {
        var clock = 0L
        val coalescer = InFlightRequestCoalescer<String, String>(3_000L, { clock }, supervisedScope())
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        val results = List(3) {
            async {
                coalescer.run("stream") {
                    calls += 1
                    gate.await()
                    "payload"
                }
            }
        }
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(listOf("payload", "payload", "payload"), results.awaitAll())
        assertEquals(1, calls)
    }

    @Test
    fun `completed result is reused inside the window and refetched after it`() = runTest {
        var clock = 0L
        val coalescer = InFlightRequestCoalescer<String, Int>(3_000L, { clock }, supervisedScope())
        var calls = 0
        val fetch: suspend () -> Int = { ++calls }

        assertEquals(1, coalescer.run("stream", block = fetch))
        clock = 2_000L
        assertEquals(1, coalescer.run("stream", block = fetch))
        clock = 3_000L
        assertEquals(2, coalescer.run("stream", block = fetch))
        assertEquals(2, calls)
    }

    @Test
    fun `reuse can be bypassed for forced refreshes`() = runTest {
        val coalescer = InFlightRequestCoalescer<String, Int>(3_000L, { 0L }, supervisedScope())
        var calls = 0
        val fetch: suspend () -> Int = { ++calls }

        coalescer.run("stream", block = fetch)

        assertEquals(2, coalescer.run("stream", allowReuse = false, block = fetch))
    }

    @Test
    fun `different keys do not share results`() = runTest {
        val coalescer = InFlightRequestCoalescer<String, String>(3_000L, { 0L }, supervisedScope())

        assertEquals("a", coalescer.run("a") { "a" })
        assertEquals("b", coalescer.run("b") { "b" })
    }

    @Test
    fun `failures are not cached`() = runTest {
        val coalescer = InFlightRequestCoalescer<String, String>(3_000L, { 0L }, supervisedScope())
        var calls = 0

        assertFailsWith<IllegalStateException> {
            coalescer.run("stream") {
                calls += 1
                error("boom")
            }
        }

        assertEquals("ok", coalescer.run("stream") { calls += 1; "ok" })
        assertEquals(2, calls)
    }
}
