package com.nuvio.app.core.diagnostics

import com.nuvio.app.core.network.createApiHttpClient

internal object LogbookService {
    val isConfigured: Boolean
        get() = LogbookConfig.URL.isNotBlank() && LogbookConfig.INGEST_KEY.isNotBlank()

    val client: LogbookClient by lazy {
        LogbookClient(
            baseUrl = LogbookConfig.URL,
            ingestKey = LogbookConfig.INGEST_KEY,
            http = createApiHttpClient(),
        )
    }
}
