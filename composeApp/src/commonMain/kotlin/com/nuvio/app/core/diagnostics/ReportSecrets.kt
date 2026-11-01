package com.nuvio.app.core.diagnostics

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseConfig
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.tmdb.TmdbConfig
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktConfig
import io.github.jan.supabase.auth.auth

internal object ReportSecrets {
    fun known(): List<String> = buildList {
        add(LogbookConfig.INGEST_KEY)
        add(SupabaseConfig.ANON_KEY)
        add(TmdbConfig.API_KEY)
        add(TraktConfig.CLIENT_SECRET)
        runCatching { add(ServerConfigurationRepository.active.value.publishableKey) }
        runCatching { add(TmdbSettingsRepository.effectiveApiKey()) }
        runCatching {
            SupabaseProvider.client.auth.currentSessionOrNull()?.let { session ->
                add(session.accessToken)
                add(session.refreshToken)
            }
        }
    }

    fun redactor(): ReportRedactor = ReportRedactor(known())
}
