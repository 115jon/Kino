package com.nuvio.app.features.downloads

import java.io.File
import java.net.URI

internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val directory = File(
        runCatching { File(URI(localVideoUri)) }.getOrElse { File(localVideoUri) }.path + ".subtitles",
    )

    actual fun read(fileName: String): String? =
        file(fileName).takeIf(File::isFile)?.readText()

    actual fun write(fileName: String, text: String) {
        directory.mkdirs()
        file(fileName).writeText(text)
    }

    actual fun localFileUri(fileName: String): String? =
        file(fileName).takeIf(File::isFile)?.toURI()?.toString()

    actual fun remove() {
        directory.deleteRecursively()
    }

    private fun file(fileName: String): File {
        require(
            fileName.isNotBlank() &&
                fileName.none { it == '/' || it == '\\' } &&
                fileName != "." &&
                fileName != "..",
        )
        return File(directory, fileName)
    }
}
