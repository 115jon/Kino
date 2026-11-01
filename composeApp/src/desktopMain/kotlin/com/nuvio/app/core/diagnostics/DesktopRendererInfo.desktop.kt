package com.nuvio.app.core.diagnostics

import com.nuvio.app.features.player.compose.findSkiaLayer
import java.awt.Window

internal object DesktopRendererInfo {
    private val adapterLabels = listOf("adapter", "renderer", "model", "device", "gpu")

    @Volatile
    var renderApi: String? = null
        private set

    @Volatile
    var renderInfo: String? = null
        private set

    @Volatile
    var adapter: String? = null
        private set

    fun capture(window: Window) {
        val layer = findSkiaLayer(window) ?: return
        renderApi = runCatching { layer.renderApi.name }.getOrNull()
        val info = runCatching { layer.renderInfo }.getOrNull()?.trim().orEmpty()
        if (info.isEmpty()) return
        val lines = info.lines().map(String::trim).filter(String::isNotEmpty)
        renderInfo = lines.joinToString("; ")
        adapter = lines.firstNotNullOfOrNull { line ->
            val label = line.substringBefore(':').trim().lowercase()
            line.substringAfter(':', "").trim().takeIf { value -> value.isNotEmpty() && adapterLabels.any { it in label } }
        }
    }
}
