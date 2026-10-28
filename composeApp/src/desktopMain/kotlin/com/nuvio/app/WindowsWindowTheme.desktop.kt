package com.nuvio.app

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Color
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean

private const val WindowsThemeRegistryPath = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
private const val AppsUseLightThemeValue = "AppsUseLightTheme"
private const val RrfRtRegDword = 0x00000018
private const val DwmwaUseImmersiveDarkMode = 20
private const val DwmwaUseImmersiveDarkModeLegacy = 19
private const val DwmwaCaptionColor = 35
private const val DwmwaTextColor = 36
private const val ErrorSuccess = 0
private const val KeyNotify = 0x0010
private const val RegNotifyChangeLastSet = 0x00000004
private const val WaitObject0 = 0
private const val Infinite = -1

private interface WindowsRegistryLibrary : Library {
    fun RegGetValueW(
        key: Pointer,
        subKey: WString?,
        value: WString?,
        flags: Int,
        valueType: IntByReference?,
        data: Pointer,
        dataSize: IntByReference,
    ): Int

    fun RegOpenKeyExW(
        key: Pointer,
        subKey: WString,
        options: Int,
        desiredAccess: Int,
        result: PointerByReference,
    ): Int

    fun RegNotifyChangeKeyValue(
        key: Pointer,
        watchSubtree: Boolean,
        notifyFilter: Int,
        event: Pointer,
        asynchronous: Boolean,
    ): Int

    fun RegCloseKey(key: Pointer): Int

    companion object {
        val INSTANCE: WindowsRegistryLibrary by lazy {
            Native.load("advapi32", WindowsRegistryLibrary::class.java)
        }
    }
}

private interface WindowsKernel32Library : Library {
    fun CreateEventW(
        attributes: Pointer?,
        manualReset: Boolean,
        initialState: Boolean,
        name: WString?,
    ): Pointer?

    fun SetEvent(event: Pointer): Boolean

    fun WaitForSingleObject(handle: Pointer, milliseconds: Int): Int

    fun CloseHandle(handle: Pointer): Boolean

    companion object {
        val INSTANCE: WindowsKernel32Library by lazy {
            Native.load("kernel32", WindowsKernel32Library::class.java)
        }
    }
}

private interface WindowsDwmLibrary : Library {
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: Pointer, valueSize: Int): Int

    companion object {
        val INSTANCE: WindowsDwmLibrary by lazy {
            Native.load("dwmapi", WindowsDwmLibrary::class.java)
        }
    }
}

private val CurrentUserRegistryKey = Pointer.createConstant(-2147483647L)

internal fun readWindowsDarkMode(): Boolean = runCatching {
    val data = Memory(4)
    val dataSize = IntByReference(4)
    val result = WindowsRegistryLibrary.INSTANCE.RegGetValueW(
        CurrentUserRegistryKey,
        WString(WindowsThemeRegistryPath),
        WString(AppsUseLightThemeValue),
        RrfRtRegDword,
        null,
        data,
        dataSize,
    )
    result == ErrorSuccess && data.getInt(0) == 0
}.getOrDefault(false)

private class WindowsThemeSubscription(
    private val registryKey: Pointer,
    private val event: Pointer,
    private val worker: Thread,
    private val closed: AtomicBoolean,
) : AutoCloseable {
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WindowsKernel32Library.INSTANCE.SetEvent(event)
            worker.join(2000L)
        }
    }
}

internal fun subscribeToWindowsThemeChanges(onChanged: () -> Unit): AutoCloseable? {
    if (!isWindowsPlatform()) return null
    val registry = WindowsRegistryLibrary.INSTANCE
    val kernel32 = WindowsKernel32Library.INSTANCE
    val registryKeyReference = PointerByReference()
    if (
        registry.RegOpenKeyExW(
            CurrentUserRegistryKey,
            WString(WindowsThemeRegistryPath),
            0,
            KeyNotify,
            registryKeyReference,
        ) != ErrorSuccess
    ) {
        return null
    }
    val registryKey = registryKeyReference.value
    val event = kernel32.CreateEventW(null, false, false, null)
    if (event == null) {
        registry.RegCloseKey(registryKey)
        return null
    }
    val closed = AtomicBoolean(false)
    val worker = Thread({
        try {
            while (!closed.get()) {
                if (
                    registry.RegNotifyChangeKeyValue(
                        registryKey,
                        false,
                        RegNotifyChangeLastSet,
                        event,
                        true,
                    ) != ErrorSuccess
                ) {
                    break
                }
                if (kernel32.WaitForSingleObject(event, Infinite) != WaitObject0 || closed.get()) {
                    break
                }
                onChanged()
            }
        } finally {
            registry.RegCloseKey(registryKey)
            kernel32.CloseHandle(event)
        }
    }, "Kino-WindowsThemeWatcher")
    worker.isDaemon = true
    worker.start()
    return WindowsThemeSubscription(registryKey, event, worker, closed)
}

internal fun applyWindowsTitleBarTheme(
    window: Window,
    darkMode: Boolean,
    captionColor: Color,
    textColor: Color,
): Boolean = runCatching {
    val windowHandle = Native.getComponentPointer(window) ?: return@runCatching false
    val dwm = WindowsDwmLibrary.INSTANCE
    fun setAttribute(attribute: Int, value: Int): Boolean {
        val memory = Memory(4)
        memory.setInt(0, value)
        return dwm.DwmSetWindowAttribute(windowHandle, attribute, memory, 4) == ErrorSuccess
    }
    val darkModeApplied = setAttribute(DwmwaUseImmersiveDarkMode, if (darkMode) 1 else 0) ||
        setAttribute(DwmwaUseImmersiveDarkModeLegacy, if (darkMode) 1 else 0)
    setAttribute(DwmwaCaptionColor, captionColor.toColorRef())
    setAttribute(DwmwaTextColor, textColor.toColorRef())
    darkModeApplied
}.getOrDefault(false)

private fun Color.toColorRef(): Int = red or (green shl 8) or (blue shl 16)
