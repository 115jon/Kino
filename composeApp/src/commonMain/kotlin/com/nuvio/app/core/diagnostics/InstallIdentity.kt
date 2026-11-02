package com.nuvio.app.core.diagnostics

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal object InstallIdentity {
    @OptIn(ExperimentalUuidApi::class)
    val installId: String by lazy {
        DiagnosticsStorage.loadInstallId()
            ?.trim()
            ?.takeIf { stored -> runCatching { Uuid.parse(stored) }.isSuccess }
            ?: Uuid.random().toString().also(DiagnosticsStorage::saveInstallId)
    }
}
