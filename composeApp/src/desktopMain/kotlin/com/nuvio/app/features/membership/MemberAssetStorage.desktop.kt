package com.nuvio.app.features.membership

import com.nuvio.app.desktop.DesktopPreferences
import java.io.File

internal actual object MemberAssetStorage {
    private const val preferencesName = "nuvio_member_access"
    private const val accessPayloadKey = "access_payload"

    private val cacheDirectory: File by lazy {
        File(System.getProperty("user.home"))
            .resolve("Library")
            .resolve("Application Support")
            .resolve("Nuvio")
            .resolve("cache")
    }
    private val backgroundDirectory: File get() = cacheDirectory.resolve("member_profile_backgrounds")
    private val avatarDirectory: File get() = cacheDirectory.resolve("member_profile_avatars")

    actual fun loadAccessPayload(): String? =
        DesktopPreferences.getString(preferencesName, accessPayloadKey)

    actual fun saveAccessPayload(payload: String) {
        DesktopPreferences.putString(preferencesName, accessPayloadKey, payload)
    }

    actual fun loadProfileBackground(cacheKey: String): ByteArray? =
        backgroundFile(cacheKey).takeIf { it.isFile && it.length() > 0L }?.readBytes()

    actual fun saveProfileBackground(cacheKey: String, bytes: ByteArray) {
        saveFile(backgroundFile(cacheKey), bytes)
    }

    actual fun loadProfileAvatar(cacheKey: String): String? =
        avatarFile(cacheKey).takeIf { it.isFile && it.length() > 0L }?.toURI()?.toString()

    actual fun saveProfileAvatar(cacheKey: String, bytes: ByteArray): String? {
        val file = avatarFile(cacheKey)
        saveFile(file, bytes)
        return file.takeIf { it.isFile && it.length() > 0L }?.toURI()?.toString()
    }

    actual fun clearAccess() {
        DesktopPreferences.remove(preferencesName, accessPayloadKey)
    }

    private fun backgroundFile(cacheKey: String): File = backgroundDirectory.resolve("${safeKey(cacheKey)}.png")

    private fun avatarFile(cacheKey: String): File = avatarDirectory.resolve(safeKey(cacheKey))

    private fun saveFile(file: File, bytes: ByteArray) {
        runCatching {
            val directory = file.parentFile ?: return
            directory.mkdirs()
            val temporary = directory.resolve(".${file.name}.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun safeKey(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
