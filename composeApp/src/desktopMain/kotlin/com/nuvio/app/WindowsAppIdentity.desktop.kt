package com.nuvio.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString

internal const val KINO_WINDOWS_APP_USER_MODEL_ID = "Kino.Kino"

private interface WindowsShellLibrary : Library {
    fun SetCurrentProcessExplicitAppUserModelID(appUserModelId: WString): Int
}

internal fun configureWindowsAppUserModelId() {
    if (!System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) return
    runCatching {
        Native.load("shell32", WindowsShellLibrary::class.java)
            .SetCurrentProcessExplicitAppUserModelID(WString(KINO_WINDOWS_APP_USER_MODEL_ID))
    }
}
