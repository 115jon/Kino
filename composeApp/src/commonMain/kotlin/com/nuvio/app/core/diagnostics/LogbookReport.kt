package com.nuvio.app.core.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal const val LOGBOOK_SCHEMA_VERSION = 1
internal const val LOGBOOK_APP_ID = "kino"

@Serializable
internal enum class ReportKind {
    @SerialName("manual")
    Manual,

    @SerialName("crash")
    Crash,
}

@Serializable
internal data class LogbookReport(
    val schemaVersion: Int = LOGBOOK_SCHEMA_VERSION,
    val reportId: String,
    val kind: ReportKind,
    val createdAt: String,
    val app: ReportApp,
    val device: ReportDevice,
    val runtime: Map<String, String>,
    val user: ReportUser?,
    val installId: String,
    val sessionId: String,
    val description: String?,
    val contactEmail: String?,
    val tags: Map<String, String> = emptyMap(),
    val extra: Map<String, JsonElement> = emptyMap(),
)

@Serializable
internal data class ReportApp(
    val id: String = LOGBOOK_APP_ID,
    val platform: String,
    val version: String,
    val build: String,
    val channel: String,
    val packageId: String,
)

@Serializable
internal data class ReportDevice(
    val os: String? = null,
    val osVersion: String? = null,
    val arch: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val cpu: String? = null,
    val cpuCores: Int? = null,
    val memoryTotalMb: Long? = null,
    val memoryFreeMb: Long? = null,
    val storageFreeMb: Long? = null,
    val gpu: String? = null,
    val displays: List<ReportDisplay> = emptyList(),
    val locale: String? = null,
    val timezone: String? = null,
)

@Serializable
internal data class ReportDisplay(
    val width: Int,
    val height: Int,
    val scale: Double? = null,
    val refreshHz: Double? = null,
)

@Serializable
internal data class ReportUser(
    val id: String,
    val email: String?,
    val displayName: String?,
)

internal val LogbookJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}
