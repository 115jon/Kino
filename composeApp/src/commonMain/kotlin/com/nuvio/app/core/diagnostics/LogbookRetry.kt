package com.nuvio.app.core.diagnostics

import kotlinx.coroutines.delay

internal data class LogbookRetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelayMs: Long = 2_000,
    val multiplier: Long = 4,
    val maxDelayMs: Long = 5 * 60_000,
)

internal suspend fun sendWithRetry(
    client: LogbookClient,
    upload: LogbookUpload,
    policy: LogbookRetryPolicy = LogbookRetryPolicy(),
    sleep: (suspend (Long) -> Unit)? = null,
): LogbookResult {
    var backoffMs = policy.initialDelayMs
    var attempt = 1
    while (true) {
        val result = client.send(upload)
        if (!result.isRetryable || attempt >= policy.maxAttempts) return result
        val waitMs = (result as? LogbookResult.RateLimited)?.retryAfterSeconds?.times(1_000) ?: backoffMs
        if (waitMs > policy.maxDelayMs) return result
        if (sleep != null) sleep(waitMs) else delay(waitMs)
        backoffMs = (backoffMs * policy.multiplier).coerceAtMost(policy.maxDelayMs)
        attempt++
    }
}
