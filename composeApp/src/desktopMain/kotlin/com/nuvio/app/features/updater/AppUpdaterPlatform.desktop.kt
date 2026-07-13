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
    actual val isDesktop: Boolean = true

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
        ProcessBuilder(installer.absolutePath, "/S")
            .directory(installer.parentFile)
            .start()
        exitProcess(0)
    }
}
