package com.nuvio.app.features.addons

import com.nuvio.app.core.network.InFlightRequestCoalescer
import io.ktor.util.date.GMTDate

private const val AddonResponseReuseWindowMs = 3_000L

private val addonResponseRequests = InFlightRequestCoalescer<Pair<String, Boolean>, String>(
    reuseWindowMs = AddonResponseReuseWindowMs,
    currentTimeMillis = { GMTDate().timestamp },
)

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String =
    addonResponseRequests.run(key = url to forceRefresh, allowReuse = !forceRefresh) {
        if (forceRefresh) {
            httpGetTextWithHeaders(
                url = url,
                headers = mapOf("Cache-Control" to "no-cache"),
            )
        } else {
            httpGetText(url)
        }
    }
