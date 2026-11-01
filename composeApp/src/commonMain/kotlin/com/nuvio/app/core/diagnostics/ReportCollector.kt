package com.nuvio.app.core.diagnostics

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.logging.AppLogging
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.UpdatePreferences
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val MAX_LOG_TAIL_BYTES = 4 * 1024 * 1024

internal object ReportCollector {
    @OptIn(ExperimentalUuidApi::class)
    fun newReportId(): String = Uuid.random().toString()

    fun collect(
        kind: ReportKind,
        reportId: String,
        description: String?,
        contactEmail: String?,
        redactor: ReportRedactor,
        includeLogs: Boolean,
        tags: Map<String, String> = emptyMap(),
    ): LogbookReport = LogbookReport(
        reportId = reportId,
        kind = kind,
        createdAt = Clock.System.now().toString(),
        app = app(),
        device = runCatching { DiagnosticsPlatform.device() }.getOrDefault(ReportDevice()),
        runtime = runCatching { DiagnosticsPlatform.runtime() }.getOrDefault(emptyMap())
            .mapValues { (_, value) -> redactor.redact(value) },
        user = user(),
        installId = InstallIdentity.installId,
        sessionId = AppLogging.sessionId,
        description = description?.trim()?.takeIf(String::isNotEmpty)?.let(redactor::redact),
        contactEmail = contactEmail?.trim()?.takeIf(String::isNotEmpty),
        tags = tags + ("logs" to if (includeLogs) "included" else "omitted"),
        extra = mapOf(
            "settings" to JsonObject(ReportSettingsSnapshot.collect(redactor).mapValues { JsonPrimitive(it.value) }),
        ),
    )

    fun gzippedLogTail(redactor: ReportRedactor): ByteArray? {
        val files = DiagnosticsPlatform.files ?: return null
        val tail = files.readLogTail(MAX_LOG_TAIL_BYTES)?.takeIf(String::isNotBlank) ?: return null
        return files.gzip(redactor.redact(tail).encodeToByteArray())
    }

    fun app(): ReportApp = ReportApp(
        platform = DiagnosticsPlatform.platformName,
        version = AppVersionConfig.VERSION_NAME,
        build = AppVersionConfig.VERSION_CODE.toString(),
        channel = channel(),
        packageId = DiagnosticsPlatform.packageId,
    )

    private fun channel(): String = when {
        DiagnosticsPlatform.isDebugBuild -> "debug"
        AppUpdaterPlatform.isSupported -> runCatching { UpdatePreferences.shared.channel.value.storedValue }.getOrDefault("release")
        else -> "release"
    }

    private fun user(): ReportUser? {
        val state = AuthRepository.state.value as? AuthState.Authenticated ?: return null
        val remoteUser = if (state.isAnonymous) null else runCatching { SupabaseProvider.client.auth.currentUserOrNull() }.getOrNull()
        val metadataName = remoteUser?.userMetadata?.let { metadata ->
            listOf("display_name", "full_name", "name", "user_name")
                .firstNotNullOfOrNull { key -> (metadata[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        }
        val profileName = runCatching { ProfileRepository.state.value.activeProfile?.name }.getOrNull()?.takeIf(String::isNotBlank)
        return ReportUser(
            id = state.userId,
            email = state.email ?: remoteUser?.email,
            displayName = metadataName ?: profileName,
        )
    }
}
