package com.nuvio.app.core.diagnostics

import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice

internal actual object DiagnosticsPlatform {
    private const val MB = 1024uL * 1024uL

    actual val crashReportsSupported: Boolean = false

    actual val files: ReportFileStore? = null

    actual val platformName: String = "ios"

    actual val packageId: String
        get() = NSBundle.mainBundle.bundleIdentifier.orEmpty()

    @OptIn(ExperimentalNativeApi::class)
    actual val isDebugBuild: Boolean
        get() = kotlin.native.Platform.isDebugBinary

    actual fun warmUp() = Unit

    actual fun device(): ReportDevice {
        val device = UIDevice.currentDevice
        val process = NSProcessInfo.processInfo
        return ReportDevice(
            os = device.systemName(),
            osVersion = device.systemVersion,
            manufacturer = "Apple",
            model = device.model,
            cpuCores = process.activeProcessorCount.toInt(),
            memoryTotalMb = (process.physicalMemory / MB).toLong(),
        )
    }

    actual fun runtime(): Map<String, String> = mapOf(
        "systemUptimeSeconds" to NSProcessInfo.processInfo.systemUptime.toLong().toString(),
    )
}
