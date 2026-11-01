package com.nuvio.app.core.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class ReportPreviewGroup { Report, App, Device, Runtime, Account, Settings, Logs }

internal data class ReportPreviewSection(
    val group: ReportPreviewGroup,
    val rows: List<Pair<String, String>>,
)

internal fun PreparedReport.previewSections(): List<ReportPreviewSection> {
    val json = LogbookJson.encodeToJsonElement(LogbookReport.serializer(), report) as JsonObject
    val reportRows = listOf("schemaVersion", "reportId", "kind", "createdAt", "installId", "sessionId", "description", "contactEmail", "tags")
        .flatMap { key -> flattenPreview(key, json[key] ?: JsonNull) }
    val settings = (json["extra"] as? JsonObject)?.get("settings") as? JsonObject
    return listOfNotNull(
        ReportPreviewSection(ReportPreviewGroup.Report, reportRows),
        section(ReportPreviewGroup.App, "app", json),
        section(ReportPreviewGroup.Device, "device", json),
        section(ReportPreviewGroup.Runtime, "runtime", json),
        ReportPreviewSection(ReportPreviewGroup.Account, flattenPreview("user", json["user"] ?: JsonNull)),
        settings?.let { ReportPreviewSection(ReportPreviewGroup.Settings, it.map { (key, value) -> key to value.previewText() }) },
        gzippedLog?.let { ReportPreviewSection(ReportPreviewGroup.Logs, listOf(LOGBOOK_LOG_FILE_NAME to formatBytes(it.size.toLong()))) },
    )
}

private fun section(group: ReportPreviewGroup, key: String, json: JsonObject): ReportPreviewSection =
    ReportPreviewSection(group, (json[key] as? JsonObject)?.flatMap { (child, value) -> flattenPreview(child, value) }.orEmpty())

private fun flattenPreview(key: String, value: JsonElement): List<Pair<String, String>> = when (value) {
    is JsonObject -> if (value.isEmpty()) listOf(key to "{}") else value.flatMap { (child, nested) -> flattenPreview("$key.$child", nested) }
    is JsonArray -> if (value.isEmpty()) listOf(key to "[]") else value.mapIndexed { index, item -> "$key[$index]" to item.previewText() }
    else -> listOf(key to value.previewText())
}

private fun JsonElement.previewText(): String = when (this) {
    is JsonNull -> "null"
    is JsonPrimitive -> content
    is JsonObject -> entries.joinToString(", ") { (key, value) -> "$key=${value.previewText()}" }
    is JsonArray -> joinToString(", ") { it.previewText() }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
