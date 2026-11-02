package com.nuvio.app.core.diagnostics

import com.nuvio.app.features.player.WindowsMpvLibrary
import com.nuvio.app.isWindowsPlatform

internal object DesktopMpvVersion {
    @Volatile
    var mpv: String? = null
        private set

    @Volatile
    var ffmpeg: String? = null
        private set

    @Volatile
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded || !isWindowsPlatform()) return
        loaded = true
        runCatching {
            val library = WindowsMpvLibrary.INSTANCE
            val handle = library.mpv_create() ?: return
            try {
                mpv = library.readProperty(handle, "mpv-version")
                ffmpeg = library.readProperty(handle, "ffmpeg-version")
            } finally {
                library.mpv_terminate_destroy(handle)
            }
        }
    }

    private fun WindowsMpvLibrary.readProperty(handle: com.sun.jna.Pointer, name: String): String? {
        val value = mpv_get_property_string(handle, name) ?: return null
        return try {
            value.getString(0, "UTF-8")
        } finally {
            mpv_free(value)
        }
    }
}
