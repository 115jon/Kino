package com.nuvio.app.features.updater

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import java.util.concurrent.TimeUnit

private val launcherLogger = Logger.withTag("DesktopInstallerLauncher")

private interface WindowsUser32Library : Library {
    fun AllowSetForegroundWindow(processId: Int): Boolean
}

internal object DesktopInstallerLauncher {
    private const val installerPathVariable = "KINO_UPDATE_INSTALLER"
    private const val anyProcess = -1
    private const val launchTimeoutSeconds = 60L

    fun launch(installer: File) {
        if (shouldLaunchWindowsInstallerDetached(System.getProperty("os.name"))) {
            launchOutsideAppJob(installer)
        } else {
            ProcessBuilder(installer.absolutePath)
                .directory(installer.parentFile)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }

    private fun launchOutsideAppJob(installer: File) {
        runCatching {
            Native.load("user32", WindowsUser32Library::class.java).AllowSetForegroundWindow(anyProcess)
        }
        val process = ProcessBuilder(windowsDetachedInstallerLaunchCommand())
            .directory(installer.parentFile)
            .redirectErrorStream(true)
            .also { it.environment()[installerPathVariable] = installer.absolutePath }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor(launchTimeoutSeconds, TimeUnit.SECONDS)) { "Timed out starting the update installer." }
        check(process.exitValue() == 0) { "Could not start the update installer (code ${process.exitValue()})." }
        launcherLogger.i { "Started installer ${installer.absolutePath} outside the app job as pid $output" }
    }
}

internal fun shouldLaunchWindowsInstallerDetached(osName: String?): Boolean =
    osName.orEmpty().contains("win", ignoreCase = true)

internal fun windowsDetachedInstallerLaunchCommand(): List<String> = listOf(
    "powershell.exe",
    "-NoProfile",
    "-NonInteractive",
    "-ExecutionPolicy",
    "Bypass",
    "-Command",
    "\$path = \$env:KINO_UPDATE_INSTALLER; " +
        "\$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create " +
        "-Arguments @{ CommandLine = '\"' + \$path + '\" --update';CurrentDirectory = (Split-Path -Parent \$path) }; " +
        "if (\$result.ReturnValue -ne 0) { exit 10 + [int]\$result.ReturnValue }; " +
        "[Console]::Out.Write(\$result.ProcessId)",
)
