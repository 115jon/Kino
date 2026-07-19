package com.nuvio.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString

private const val DisableVulkanObsCapture = "DISABLE_VULKAN_OBS_CAPTURE"

private interface WindowsEnvironmentLibrary : Library {
    fun SetEnvironmentVariableW(name: WString, value: WString): Boolean

    companion object {
        val INSTANCE: WindowsEnvironmentLibrary by lazy {
            Native.load("kernel32", WindowsEnvironmentLibrary::class.java)
        }
    }
}

internal fun configureWindowsCaptureCompatibility(): Boolean {
    if (!System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) return false
    return runCatching {
        WindowsEnvironmentLibrary.INSTANCE.SetEnvironmentVariableW(
            WString(DisableVulkanObsCapture),
            WString("1"),
        )
    }.getOrDefault(false)
}
