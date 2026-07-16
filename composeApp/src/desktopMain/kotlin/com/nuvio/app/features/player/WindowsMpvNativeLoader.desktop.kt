package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.sun.jna.Native
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val windowsMpvLog = Logger.withTag("DesktopPlayerTrace")

private fun windowsMpvTrace(message: String) {
    windowsMpvLog.d { message }
}

internal object WindowsMpvNativeLoader {
    private val nativeResources = listOf(
        "win32-x86-64/libmpv-2.dll",
        "win32-x86-64/mpv.dll",
        "win32-x86-64/kino-media-session.dll",
        "app_logo.png",
    )

    @Volatile
    private var loadedLibrary: WindowsMpvLibrary? = null

    @Volatile
    private var loadFailure: RuntimeException? = null

    fun load(): WindowsMpvLibrary {
        loadFailure?.let { throw it }
        loadedLibrary?.let { return it }
        synchronized(this) {
            loadFailure?.let { throw it }
            loadedLibrary?.let { return it }
            return try {
                val nativeDirectory = prepareNativeDirectory()
                configureSearchPaths(nativeDirectory)
                val library = loadFrom(nativeDirectory)
                loadedLibrary = library
                windowsMpvTrace("windows mpv natives prepared at ${nativeDirectory.absolutePath}")
                library
            } catch (cause: Throwable) {
                val failure = IllegalStateException("Failed to prepare Windows mpv native libraries", cause)
                loadFailure = failure
                throw failure
            }
        }
    }

    fun loadMediaSession(): WindowsMediaSessionLibrary {
        val nativeDirectory = prepareNativeDirectory()
        configureSearchPaths(nativeDirectory)
        return Native.load(nativeDirectory.resolve("kino-media-session.dll").absolutePath, WindowsMediaSessionLibrary::class.java)
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
            .resolve(nativeDirectoryName())
        if (!nativeDirectory.exists() && !nativeDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create mpv native directory at ${nativeDirectory.absolutePath}")
        }
        nativeResources.forEach { resourcePath ->
            val targetFile = nativeDirectory.resolve(resourcePath.substringAfterLast('/'))
            extractResource(resourcePath, targetFile)
        }
        return nativeDirectory
    }

    private fun nativeDirectoryName(): String {
        val fingerprint = nativeResources
            .joinToString(separator = "|", transform = ::resourceFingerprint)
            .hashCode()
            .toUInt()
            .toString(16)
        return "mpv-windows-amd64-$fingerprint"
    }

    private fun resourceFingerprint(resourcePath: String): String {
        val resource = WindowsMpvNativeLoader::class.java.classLoader.getResource(resourcePath)
            ?: throw IllegalStateException("Missing mpv native resource $resourcePath on the desktop runtime classpath")
        val connection = resource.openConnection()
        return "$resourcePath:${connection.contentLengthLong}:${connection.lastModified}"
    }

    private fun extractResource(resourcePath: String, targetFile: File) {
        val classLoader = WindowsMpvNativeLoader::class.java.classLoader
        val resource = classLoader.getResource(resourcePath)
            ?: throw IllegalStateException("Missing mpv native resource $resourcePath on the desktop runtime classpath")
        val expectedLength = resource.openConnection().contentLengthLong
        if (expectedLength > 0L && targetFile.exists() && targetFile.length() == expectedLength) {
            return
        }
        resource.openStream().use { input ->
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
        System.setProperty("jna.nosys", "true")
        prependDirectoryToProperty("jna.library.path", nativeDirectory)
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

    private fun loadFrom(nativeDirectory: File): WindowsMpvLibrary {
        val failures = mutableListOf<Throwable>()
        listOf("libmpv-2.dll", "mpv.dll").forEach { libraryName ->
            val libraryFile = nativeDirectory.resolve(libraryName)
            if (!libraryFile.exists()) {
                return@forEach
            }
            try {
                return Native.load(libraryFile.absolutePath, WindowsMpvLibrary::class.java)
            } catch (cause: Throwable) {
                failures += cause
            }
        }
        listOf("libmpv-2", "mpv").forEach { libraryName ->
            try {
                return Native.load(libraryName, WindowsMpvLibrary::class.java)
            } catch (cause: Throwable) {
                failures += cause
            }
        }
        throw IllegalStateException(
            "Failed to load libmpv from ${nativeDirectory.absolutePath}",
            failures.lastOrNull(),
        )
    }
}
