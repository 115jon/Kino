package com.nuvio.app.core.auth

import java.net.InetAddress

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata {
    val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull().orEmpty()
    val deviceName = hostName.trim()
        .ifBlank { System.getenv("COMPUTERNAME").orEmpty().trim() }
        .ifBlank { "Desktop device" }
    val osName = System.getProperty("os.name").orEmpty().trim()
    val osVersion = System.getProperty("os.version").orEmpty().trim()
    val platform = listOf(osName, osVersion)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { "Desktop" }

    return DeviceClientMetadata(
        deviceName = deviceName,
        platform = platform,
    )
}
