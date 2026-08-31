package com.nuvio.app.features.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.prefs.Preferences
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess

actual object AppUpdaterPlatform {
    private const val ignoredTagKey = "ignoredUpdateTag"
    private val preferences = Preferences.userNodeForPackage(AppUpdaterPlatform::class.java)

    actual val isSupported: Boolean = true
    actual val platform: String = "desktop"

    actual fun getSupportedAbis(): List<String> = emptyList()

    actual fun getIgnoredTag(): String? = preferences.get(ignoredTagKey, null)

    actual fun setIgnoredTag(tag: String?) {
        if (tag.isNullOrBlank()) {
            preferences.remove(ignoredTagKey)
        } else {
            preferences.put(ignoredTagKey, tag)
        }
    }

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        expectedSha256: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val connection = URL(assetUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("User-Agent", "Kino-Desktop")
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                if (connection.responseCode !in 200..299) {
                    error("Update download failed with HTTP ${connection.responseCode}")
                }

                val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val output = createTempDirectory("kino-update-").toFile().resolve(safeName)
                val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                var downloadedBytes = 0L
                connection.inputStream.use { input ->
                    output.outputStream().use { file ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            file.write(buffer, 0, read)
                            downloadedBytes += read
                            onProgress(downloadedBytes, totalBytes)
                        }
                    }
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                output.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                check(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    "Downloaded update checksum does not match the release manifest."
                }
                output.absolutePath
            } finally {
                connection.disconnect()
            }
        }
    }

    actual fun canRequestPackageInstalls(): Boolean = true

    actual fun openUnknownSourcesSettings() = Unit

    actual fun installDownloadedApk(path: String): Result<Unit> = runCatching {
        val installer = File(path)
        require(installer.isFile) { "Downloaded installer was not found." }
        val command = if (shouldLaunchWindowsInstallerDetached(System.getProperty("os.name"))) {
            windowsSilentInstallerLaunchCommand(installer.absolutePath)
        } else {
            listOf(installer.absolutePath)
        }
        ProcessBuilder(command)
            .directory(installer.parentFile)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        exitProcess(0)
    }
}

internal fun shouldLaunchWindowsInstallerDetached(osName: String?): Boolean =
    osName.orEmpty().contains("win", ignoreCase = true)

internal fun windowsSilentInstallerLaunchCommand(path: String): List<String> =
    listOf("cmd.exe", "/c", "start", "", path, "/S")
