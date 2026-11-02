package com.nuvio.app.core.diagnostics

import java.awt.GraphicsEnvironment
import java.awt.DisplayMode
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.TimeZone

internal data class DesktopStaticFacts(
    val os: String,
    val osVersion: String,
    val manufacturer: String?,
    val model: String?,
    val cpu: String?,
)

internal object DesktopDeviceFacts {
    private const val MB = 1024L * 1024L

    val staticFacts: DesktopStaticFacts by lazy { readStaticFacts() }

    fun collect(): ReportDevice {
        val facts = staticFacts
        val memory = memoryBytes()
        return ReportDevice(
            os = facts.os,
            osVersion = facts.osVersion,
            arch = System.getProperty("os.arch"),
            manufacturer = facts.manufacturer,
            model = facts.model,
            cpu = facts.cpu,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            memoryTotalMb = memory?.first?.div(MB),
            memoryFreeMb = memory?.second?.div(MB),
            storageFreeMb = runCatching { File(System.getProperty("user.home")).usableSpace / MB }.getOrNull(),
            gpu = DesktopRendererInfo.adapter ?: DesktopRendererInfo.renderInfo,
            displays = displays(),
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
        )
    }

    private fun readStaticFacts(): DesktopStaticFacts {
        val osName = System.getProperty("os.name").orEmpty()
        val osVersion = System.getProperty("os.version").orEmpty()
        return when (desktopOsFamily()) {
            "windows" -> {
                val version = windowsRegistry("""HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion""")
                val bios = windowsRegistry("""HKLM\HARDWARE\DESCRIPTION\System\BIOS""")
                val cpu = windowsRegistry("""HKLM\HARDWARE\DESCRIPTION\System\CentralProcessor\0""")
                val build = listOfNotNull(version["CurrentBuild"], version["UBR"]?.let(::parseRegistryNumber)).joinToString(".")
                DesktopStaticFacts(
                    os = osName,
                    osVersion = listOfNotNull(version["DisplayVersion"], build.ifBlank { null }).joinToString(" ").ifBlank { osVersion },
                    manufacturer = bios["SystemManufacturer"],
                    model = bios["SystemProductName"],
                    cpu = cpu["ProcessorNameString"]?.trim(),
                )
            }
            "macos" -> DesktopStaticFacts(
                os = osName,
                osVersion = osVersion,
                manufacturer = "Apple",
                model = command("sysctl", "-n", "hw.model"),
                cpu = command("sysctl", "-n", "machdep.cpu.brand_string"),
            )
            else -> DesktopStaticFacts(
                os = osName,
                osVersion = linuxPrettyName() ?: osVersion,
                manufacturer = readFirstLine("/sys/class/dmi/id/sys_vendor"),
                model = readFirstLine("/sys/class/dmi/id/product_name"),
                cpu = runCatching {
                    File("/proc/cpuinfo").useLines { lines ->
                        lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
                    }
                }.getOrNull(),
            )
        }
    }

    private fun memoryBytes(): Pair<Long, Long>? = runCatching {
        val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        bean?.let { it.totalMemorySize to it.freeMemorySize }
    }.getOrNull()

    private fun displays(): List<ReportDisplay> = runCatching {
        if (GraphicsEnvironment.isHeadless()) return@runCatching emptyList()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            val mode = device.displayMode
            ReportDisplay(
                width = mode.width,
                height = mode.height,
                scale = device.defaultConfiguration.defaultTransform.scaleX,
                refreshHz = mode.refreshRate.takeIf { it != DisplayMode.REFRESH_RATE_UNKNOWN }?.toDouble(),
            )
        }
    }.getOrDefault(emptyList())

    private fun windowsRegistry(key: String): Map<String, String> {
        val output = command("reg", "query", key) ?: return emptyMap()
        return output.lineSequence()
            .map(String::trim)
            .mapNotNull { line ->
                val parts = line.split(Regex("""\s{2,}"""), limit = 3)
                if (parts.size == 3 && parts[1].startsWith("REG_")) parts[0] to parts[2].trim() else null
            }
            .toMap()
    }

    private fun parseRegistryNumber(value: String): String =
        if (value.startsWith("0x")) value.removePrefix("0x").toLongOrNull(16)?.toString() ?: value else value

    private fun linuxPrettyName(): String? = runCatching {
        File("/etc/os-release").useLines { lines ->
            lines.firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
        }
    }.getOrNull()

    private fun readFirstLine(path: String): String? =
        runCatching { File(path).takeIf(File::isFile)?.useLines { it.firstOrNull()?.trim() } }.getOrNull()?.ifBlank { null }

    private fun command(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            output.trim().ifBlank { null }
        }
    }.getOrNull()
}
