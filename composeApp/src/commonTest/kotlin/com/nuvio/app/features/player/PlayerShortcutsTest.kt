package com.nuvio.app.features.player

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerShortcutsTest {
    @Test
    fun playbackKeysToggle() {
        assertEquals(PlayerShortcut.TogglePlayback, playerShortcutFor(Key.Spacebar))
        assertEquals(PlayerShortcut.TogglePlayback, playerShortcutFor(Key.K))
    }

    @Test
    fun seekKeysUseTenSecondsAndShiftArrowsUseThirty() {
        assertEquals(PlayerShortcut.Seek(-10_000L), playerShortcutFor(Key.DirectionLeft))
        assertEquals(PlayerShortcut.Seek(10_000L), playerShortcutFor(Key.DirectionRight))
        assertEquals(PlayerShortcut.Seek(-10_000L), playerShortcutFor(Key.J))
        assertEquals(PlayerShortcut.Seek(10_000L), playerShortcutFor(Key.L))
        assertEquals(PlayerShortcut.Seek(-30_000L), playerShortcutFor(Key.DirectionLeft, isShiftPressed = true))
        assertEquals(PlayerShortcut.Seek(30_000L), playerShortcutFor(Key.DirectionRight, isShiftPressed = true))
    }

    @Test
    fun volumeAndMuteKeys() {
        assertEquals(PlayerShortcut.Volume(0.05f), playerShortcutFor(Key.DirectionUp))
        assertEquals(PlayerShortcut.Volume(-0.05f), playerShortcutFor(Key.DirectionDown))
        assertEquals(PlayerShortcut.ToggleMute, playerShortcutFor(Key.M))
    }

    @Test
    fun fullscreenAndBackKeys() {
        assertEquals(PlayerShortcut.ToggleFullscreen, playerShortcutFor(Key.F))
        assertEquals(PlayerShortcut.ToggleFullscreen, playerShortcutFor(Key.F11))
        assertEquals(PlayerShortcut.Back, playerShortcutFor(Key.Escape))
    }

    @Test
    fun menuAndEpisodeKeys() {
        assertEquals(PlayerShortcut.NextEpisode, playerShortcutFor(Key.N))
        assertEquals(PlayerShortcut.SubtitleMenu, playerShortcutFor(Key.S))
        assertEquals(PlayerShortcut.AudioMenu, playerShortcutFor(Key.A))
        assertEquals(PlayerShortcut.ToggleSubtitles, playerShortcutFor(Key.C))
        assertEquals(PlayerShortcut.Speed(-1), playerShortcutFor(Key.LeftBracket))
        assertEquals(PlayerShortcut.Speed(1), playerShortcutFor(Key.RightBracket))
        assertEquals(PlayerShortcut.ShortcutHelp, playerShortcutFor(Key.Slash, isShiftPressed = true))
        assertNull(playerShortcutFor(Key.Slash))
    }

    @Test
    fun digitsJumpToTenths() {
        assertEquals(PlayerShortcut.SeekToTenth(0), playerShortcutFor(Key.Zero))
        assertEquals(PlayerShortcut.SeekToTenth(5), playerShortcutFor(Key.Five))
        assertEquals(PlayerShortcut.SeekToTenth(9), playerShortcutFor(Key.NumPad9))
        assertNull(playerShortcutFor(Key.One, isShiftPressed = true))
    }

    @Test
    fun modifierChordsAndUnmappedKeysAreIgnored() {
        assertNull(playerShortcutFor(Key.C, isCtrlPressed = true))
        assertNull(playerShortcutFor(Key.F, isAltPressed = true))
        assertNull(playerShortcutFor(Key.K, isMetaPressed = true))
        assertNull(playerShortcutFor(Key.Enter))
        assertNull(playerShortcutFor(Key.Tab))
    }

    @Test
    fun onlySeekAndVolumeRepeatWhileHeld() {
        assertTrue(PlayerShortcut.Seek(10_000L).repeatable)
        assertTrue(PlayerShortcut.Volume(0.05f).repeatable)
        assertFalse(PlayerShortcut.ToggleFullscreen.repeatable)
        assertFalse(PlayerShortcut.ToggleMute.repeatable)
        assertFalse(PlayerShortcut.TogglePlayback.repeatable)
    }

    @Test
    fun onlyBackWorksWhileTypingAndPlaybackDoesNotRevealControls() {
        assertTrue(PlayerShortcut.Back.allowedWhileTyping)
        assertFalse(PlayerShortcut.TogglePlayback.allowedWhileTyping)
        assertFalse(PlayerShortcut.Seek(10_000L).allowedWhileTyping)
        assertFalse(PlayerShortcut.TogglePlayback.revealsControls)
        assertTrue(PlayerShortcut.Seek(10_000L).revealsControls)
        assertTrue(PlayerShortcut.Volume(0.05f).revealsControls)
    }

    @Test
    fun speedStepsStayWithinExistingSteps() {
        assertEquals(1.25f, steppedPlaybackSpeed(1f, 1))
        assertEquals(2f, steppedPlaybackSpeed(2f, 1))
        assertEquals(1.5f, steppedPlaybackSpeed(2f, -1))
        assertEquals(1f, steppedPlaybackSpeed(1f, -1))
        assertEquals(1.25f, steppedPlaybackSpeed(1.4f, -1))
    }

    @Test
    fun digitSeekTargetsScaleWithDuration() {
        assertEquals(0L, shortcutSeekTarget(0, 600_000L))
        assertEquals(300_000L, shortcutSeekTarget(5, 600_000L))
        assertEquals(540_000L, shortcutSeekTarget(9, 600_000L))
        assertNull(shortcutSeekTarget(5, 0L))
    }

    @Test
    fun backExitsFullscreenBeforeClosingOverlaysOrLeaving() {
        assertEquals(PlayerBackAction.ExitFullscreen, playerBackAction(isFullscreen = true, hasOpenOverlay = true))
        assertEquals(PlayerBackAction.ExitFullscreen, playerBackAction(isFullscreen = true, hasOpenOverlay = false))
        assertEquals(PlayerBackAction.CloseOverlay, playerBackAction(isFullscreen = false, hasOpenOverlay = true))
        assertEquals(PlayerBackAction.LeavePlayer, playerBackAction(isFullscreen = false, hasOpenOverlay = false))
    }
}
