package com.nuvio.app.features.streams

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class StreamFetchSupportTest {
    @Test
    fun `provider timeout becomes a failed result instead of cancelling the fetcher`() = runBlocking {
        val result = runCatchingUnlessCancelledWithTimeout(timeoutMs = 10L) {
            delay(100L)
            "stream payload"
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is StreamLoadTimeoutException)
    }
}
