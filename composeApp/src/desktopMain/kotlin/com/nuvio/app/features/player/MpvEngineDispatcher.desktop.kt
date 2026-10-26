package com.nuvio.app.features.player

import javax.swing.SwingUtilities

internal interface MpvEngineDispatcher {
    fun post(action: () -> Unit)

    fun postDelayed(delayMs: Long, action: () -> Unit)
}

internal object SwingMpvEngineDispatcher : MpvEngineDispatcher {
    override fun post(action: () -> Unit) {
        SwingUtilities.invokeLater(action)
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        scheduleWindowsSwingAction(delayMs.toInt(), action)
    }
}
