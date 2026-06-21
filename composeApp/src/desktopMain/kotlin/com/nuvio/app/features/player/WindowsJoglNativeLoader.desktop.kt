package com.nuvio.app.features.player

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val windowsJoglPrimaryLibraryProperty = "jogamp.primary.library"

private fun windowsJoglTrace(message: String) {
    println("Debug: (DesktopPlayerTrace) $message")
}

internal object WindowsJoglNativeLoader {
    private val nativeResources = listOf(
        "natives/windows-amd64/gluegen_rt.dll",
        "natives/windows-amd64/jogl_mobile.dll",
        "natives/windows-amd64/nativewindow_win32.dll",
        "natives/windows-amd64/nativewindow_awt.dll",
        "natives/windows-amd64/newt_head.dll",
        "natives/windows-amd64/jogl_desktop.dll",
    )

    @Volatile
    private var configuredDirectoryPath: String? = null

    @Volatile
    private var configurationFailure: RuntimeException? = null

    fun ensureConfigured(): String {
        configurationFailure?.let { throw it }
        configuredDirectoryPath?.let { return it }
        synchronized(this) {
            configurationFailure?.let { throw it }
            configuredDirectoryPath?.let { return it }
            return try {
                val directory = prepareNativeDirectory()
                configureSearchPaths(directory)
                val path = directory.absolutePath
                configuredDirectoryPath = path
                windowsJoglTrace("windows jogl natives prepared at $path")
                path
            } catch (cause: Throwable) {
                val failure = IllegalStateException("Failed to prepare Windows JOGL native libraries", cause)
                configurationFailure = failure
                throw failure
            }
        }
    }

    private fun prepareNativeDirectory(): File {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        val baseDirectory = if (localAppData != null) {
            File(localAppData)
        } else {
            File(System.getProperty("java.io.tmpdir"))
        }
        val nativeDirectory = baseDirectory
            .resolve("Nuvio")
            .resolve("native-libs")
            .resolve("jogl-windows-amd64")
        if (!nativeDirectory.exists() && !nativeDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create JOGL native directory at ${nativeDirectory.absolutePath}")
        }
        nativeResources.forEach { resourcePath ->
            val targetFile = nativeDirectory.resolve(resourcePath.substringAfterLast('/'))
            extractResource(resourcePath, targetFile)
        }
        return nativeDirectory
    }

    private fun extractResource(resourcePath: String, targetFile: File) {
        val classLoader = WindowsJoglNativeLoader::class.java.classLoader
        val resourceStream = classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Missing JOGL native resource $resourcePath on the desktop runtime classpath")
        resourceStream.use { input ->
            val temporaryFile = Files.createTempFile(targetFile.parentFile.toPath(), targetFile.name, ".tmp")
            temporaryFile.toFile().outputStream().use { output ->
                input.copyTo(output)
            }
            Files.move(
                temporaryFile,
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun configureSearchPaths(nativeDirectory: File) {
        prependDirectoryToProperty(windowsJoglPrimaryLibraryProperty, nativeDirectory)
        prependDirectoryToProperty("java.library.path", nativeDirectory)
    }

    private fun prependDirectoryToProperty(propertyName: String, directory: File) {
        val normalizedDirectory = directory.absolutePath
        val updatedValue = buildList {
            add(normalizedDirectory)
            System.getProperty(propertyName)
                .orEmpty()
                .split(File.pathSeparatorChar)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filterNot { it.equals(normalizedDirectory, ignoreCase = true) }
                .forEach(::add)
        }.joinToString(File.pathSeparator)
        System.setProperty(propertyName, updatedValue)
    }
}
