package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsPlayerInteractionPolicyTest {
    @Test
    fun `overlay pointer counts as inside the player`() {
        assertEquals(true, isWindowsPlayerPointerInside(playerInside = false, overlayInside = true))
        assertEquals(true, isWindowsPlayerPointerInside(playerInside = true, overlayInside = false))
        assertEquals(false, isWindowsPlayerPointerInside(playerInside = false, overlayInside = false))
    }

    @Test
    fun `controls hide only after pointer leaves the full player surface`() {
        assertEquals(false, shouldHideWindowsPlayerControls(pointerInside = true))
        assertEquals(true, shouldHideWindowsPlayerControls(pointerInside = false))
    }

    @Test
    fun `overlay clicks do not toggle the player surface controls`() {
        assertEquals(true, shouldForwardWindowsPlayerClick(WindowsPlayerPointerTarget.Video))
        assertEquals(false, shouldForwardWindowsPlayerClick(WindowsPlayerPointerTarget.Overlay))
    }

    @Test
    fun `iconified fullscreen windows are restored without leaving fullscreen`() {
        assertEquals(true, shouldRestoreWindowsFullscreen(isFullscreen = true, isIconified = true))
        assertEquals(false, shouldRestoreWindowsFullscreen(isFullscreen = true, isIconified = false))
        assertEquals(false, shouldRestoreWindowsFullscreen(isFullscreen = false, isIconified = true))
    }

    @Test
    fun `fullscreen style removes decorated frame styles`() {
        val decoratedStyle = 0x00C40000L or 0x00080000L
        val borderlessStyle = toWindowsBorderlessStyle(decoratedStyle)

        assertEquals(0L, borderlessStyle and 0x00C40000L)
        assertEquals(0x80000000L, borderlessStyle and 0x80000000L)
    }

    @Test
    fun `normal control exit keeps playback controls mounted during the shared fade`() {
        assertEquals(true, shouldRenderPlayerPlaybackControls(controlsVisible = false, showParentalGuide = false))
        assertEquals(false, shouldRenderPlayerPlaybackControls(controlsVisible = false, showParentalGuide = true))
    }

    @Test
    fun `native DirectX surface is the default and embedded surface is explicit`() {
        assertEquals(true, shouldUseNativeWindowsVideoSurface(null))
        assertEquals(false, shouldUseNativeWindowsVideoSurface("embedded"))
        assertEquals(false, shouldUseNativeWindowsVideoSurface("opengl"))
        assertEquals(true, shouldUseNativeWindowsVideoSurface("native"))
        assertEquals(true, shouldUseNativeWindowsVideoSurface("d3d11"))
    }

    @Test
    fun `native player interop surface uses the desktop window clear color`() {
        assertEquals(0xFF0C0C0CL, windowsPlayerInteropBackgroundArgb())
    }

    @Test
    fun `native player canvas is shown only after video output is configured`() {
        assertEquals(true, shouldShowNativeWindowsVideoSurface("yes", 1280, 720))
        assertEquals(false, shouldShowNativeWindowsVideoSurface("no", 1280, 720))
        assertEquals(false, shouldShowNativeWindowsVideoSurface("yes", 0, 720))
        assertEquals(false, shouldShowNativeWindowsVideoSurface(null, 1280, 720))
    }

    @Test
    fun `native player overlay stays opaque until video output is ready`() {
        assertEquals(false, shouldUseTransparentWindowsPlayerOverlay("no", 1280, 720))
        assertEquals(false, shouldUseTransparentWindowsPlayerOverlay("yes", 0, 720))
        assertEquals(true, shouldUseTransparentWindowsPlayerOverlay("yes", 1280, 720))
    }

}
