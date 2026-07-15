package com.nuvio.app.features.updater

import kotlinx.serialization.Serializable

@Serializable
data class ReleaseManifestAsset(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val abi: String? = null,
)

@Serializable
data class ReleaseManifest(
    val schemaVersion: Int = 1,
    val product: String = "kino",
    val platform: String,
    val channel: String = "stable",
    val version: String,
    val versionCode: Int? = null,
    val minimumSupportedVersionCode: Int? = null,
    val mandatory: Boolean = false,
    val assets: List<ReleaseManifestAsset> = emptyList(),
) {
    fun appliesTo(platform: String, channel: String): Boolean =
        schemaVersion == 1 &&
            product == "kino" &&
            this.platform == platform &&
            this.channel == channel &&
            (platform != "android" || versionCode != null)

    fun isNewerThan(currentVersion: String, currentVersionCode: Int): Boolean =
        if (platform == "android" && versionCode != null) {
            versionCode > currentVersionCode
        } else {
            compareVersions(version, currentVersion) > 0
        }

    fun isMandatoryFor(currentVersionCode: Int): Boolean =
        minimumSupportedVersionCode?.let { currentVersionCode < it } == true

    fun selectAsset(
        candidates: List<ReleaseAssetCandidate>,
        supportedAbis: List<String>,
    ): ReleaseAssetCandidate? {
        val declaredAssets = assets.associateBy { it.name }
        val compatible = candidates.filter { it.name in declaredAssets }
        if (compatible.isEmpty()) return null
        if (compatible.size == 1) return compatible.first()

        for (abi in supportedAbis) {
            val candidate = compatible.firstOrNull { declaredAssets[it.name]?.abi == abi }
                ?: compatible.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            if (candidate != null) return candidate
        }

        return compatible.firstOrNull { declaredAssets[it.name]?.abi == null }
            ?: compatible.first()
    }
}

data class ReleaseAssetCandidate(
    val name: String,
    val url: String,
    val sizeBytes: Long?,
)

internal fun compareVersions(left: String, right: String): Int {
    val leftVersion = parseVersion(left)
    val rightVersion = parseVersion(right)
    for (index in 0 until 3) {
        val comparison = leftVersion.core[index].compareTo(rightVersion.core[index])
        if (comparison != 0) return comparison
    }

    if (leftVersion.preRelease == null && rightVersion.preRelease == null) return 0
    if (leftVersion.preRelease == null) return 1
    if (rightVersion.preRelease == null) return -1

    val maxSize = maxOf(leftVersion.preRelease.size, rightVersion.preRelease.size)
    for (index in 0 until maxSize) {
        val leftPart = leftVersion.preRelease.getOrNull(index)
        val rightPart = rightVersion.preRelease.getOrNull(index)
        if (leftPart == null) return -1
        if (rightPart == null) return 1
        if (leftPart == rightPart) continue

        val leftNumber = leftPart.toIntOrNull()
        val rightNumber = rightPart.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> leftPart.compareTo(rightPart)
        }
    }
    return 0
}

private data class ParsedVersion(
    val core: List<Int>,
    val preRelease: List<String>?,
)

private fun parseVersion(raw: String): ParsedVersion {
    val normalized = raw.trim().removePrefix("v").removePrefix("V")
    val segments = normalized.split('-', limit = 2)
    val core = segments.first().split('.').map { it.toIntOrNull() ?: 0 }.let { parts ->
        List(3) { index -> parts.getOrElse(index) { 0 } }
    }
    val preRelease = segments.getOrNull(1)?.split('.')?.filter { it.isNotBlank() }
    return ParsedVersion(core, preRelease)
}
