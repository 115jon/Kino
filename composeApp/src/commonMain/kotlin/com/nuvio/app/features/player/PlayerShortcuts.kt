package com.nuvio.app.features.player

import androidx.compose.ui.input.key.Key

sealed interface PlayerShortcut {
    data object TogglePlayback : PlayerShortcut
    data class Seek(val offsetMs: Long) : PlayerShortcut
    data class SeekToTenth(val tenth: Int) : PlayerShortcut
    data class Volume(val delta: Float) : PlayerShortcut
    data object ToggleMute : PlayerShortcut
    data object ToggleFullscreen : PlayerShortcut
    data object Back : PlayerShortcut
    data object NextEpisode : PlayerShortcut
    data object SubtitleMenu : PlayerShortcut
    data object AudioMenu : PlayerShortcut
    data object ToggleSubtitles : PlayerShortcut
    data class Speed(val step: Int) : PlayerShortcut
    data object ShortcutHelp : PlayerShortcut
}

internal const val PlayerShortcutSeekMs = 10_000L
internal const val PlayerShortcutLongSeekMs = 30_000L
internal const val PlayerShortcutVolumeStep = 0.05f
internal val PlayerSpeedSteps = listOf(1f, 1.25f, 1.5f, 2f)

private val DigitKeys = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four,
    Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
)

private val NumPadDigitKeys = listOf(
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
    Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)

internal val PlayerShortcut.repeatable: Boolean
    get() = this is PlayerShortcut.Seek || this is PlayerShortcut.Volume

internal val PlayerShortcut.allowedWhileTyping: Boolean
    get() = this == PlayerShortcut.Back

internal val PlayerShortcut.revealsControls: Boolean
    get() = this != PlayerShortcut.TogglePlayback && this != PlayerShortcut.Back && this != PlayerShortcut.ShortcutHelp

internal fun playerShortcutFor(
    key: Key,
    isShiftPressed: Boolean = false,
    isCtrlPressed: Boolean = false,
    isAltPressed: Boolean = false,
    isMetaPressed: Boolean = false,
): PlayerShortcut? {
    if (isCtrlPressed || isAltPressed || isMetaPressed) return null
    val digit = DigitKeys.indexOf(key).takeIf { it >= 0 } ?: NumPadDigitKeys.indexOf(key)
    if (digit >= 0) return if (isShiftPressed) null else PlayerShortcut.SeekToTenth(digit)
    val seekMs = if (isShiftPressed) PlayerShortcutLongSeekMs else PlayerShortcutSeekMs
    return when (key) {
        Key.Spacebar, Key.K -> PlayerShortcut.TogglePlayback
        Key.DirectionLeft -> PlayerShortcut.Seek(-seekMs)
        Key.DirectionRight -> PlayerShortcut.Seek(seekMs)
        Key.J -> PlayerShortcut.Seek(-PlayerShortcutSeekMs)
        Key.L -> PlayerShortcut.Seek(PlayerShortcutSeekMs)
        Key.DirectionUp -> PlayerShortcut.Volume(PlayerShortcutVolumeStep)
        Key.DirectionDown -> PlayerShortcut.Volume(-PlayerShortcutVolumeStep)
        Key.M -> PlayerShortcut.ToggleMute
        Key.F, Key.F11 -> PlayerShortcut.ToggleFullscreen
        Key.Escape -> PlayerShortcut.Back
        Key.N -> PlayerShortcut.NextEpisode
        Key.S -> PlayerShortcut.SubtitleMenu
        Key.A -> PlayerShortcut.AudioMenu
        Key.C -> PlayerShortcut.ToggleSubtitles
        Key.LeftBracket -> PlayerShortcut.Speed(-1)
        Key.RightBracket -> PlayerShortcut.Speed(1)
        Key.Slash -> if (isShiftPressed) PlayerShortcut.ShortcutHelp else null
        else -> null
    }
}

internal fun steppedPlaybackSpeed(current: Float, step: Int): Float = if (step > 0) {
    PlayerSpeedSteps.firstOrNull { it > current + 0.01f } ?: PlayerSpeedSteps.last()
} else {
    PlayerSpeedSteps.lastOrNull { it < current - 0.01f } ?: PlayerSpeedSteps.first()
}

internal fun shortcutSeekTarget(tenth: Int, durationMs: Long): Long? =
    durationMs.takeIf { it > 0L }?.let { it * tenth.coerceIn(0, 9) / 10L }

internal enum class PlayerBackAction {
    ExitFullscreen,
    CloseOverlay,
    LeavePlayer,
}

internal fun playerBackAction(isFullscreen: Boolean, hasOpenOverlay: Boolean): PlayerBackAction = when {
    isFullscreen -> PlayerBackAction.ExitFullscreen
    hasOpenOverlay -> PlayerBackAction.CloseOverlay
    else -> PlayerBackAction.LeavePlayer
}
