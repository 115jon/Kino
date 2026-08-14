package com.nuvio.app

import java.awt.Frame
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {
    @Test
    fun `activation requested during startup is delivered when the window attaches`() {
        val relay = DesktopActivationRelay()
        var activations = 0

        relay.requestActivation()
        relay.attach { activations += 1 }.use {
            assertEquals(1, activations)
        }
    }

    @Test
    fun `startup activation requests are coalesced`() {
        val relay = DesktopActivationRelay()
        var activations = 0

        relay.requestActivation()
        relay.requestActivation()
        relay.attach { activations += 1 }.use {
            assertEquals(1, activations)
        }
    }

    @Test
    fun `activation protocol accepts only the matching token and command`() {
        val token = SingleInstanceProtocol.createToken()
        val otherToken = SingleInstanceProtocol.createToken()

        assertNotEquals(token, otherToken)
        assertTrue(SingleInstanceProtocol.isValidActivationRequest("KINO/1 ACTIVATE $token", token))
        assertFalse(SingleInstanceProtocol.isValidActivationRequest("KINO/1 ACTIVATE $otherToken", token))
        assertFalse(SingleInstanceProtocol.isValidActivationRequest("KINO/1 OPEN $token", token))
    }

    @Test
    fun `coordination failure does not fail open into another app instance`() {
        assertFalse(shouldStartDesktopApplication(DesktopInstanceLaunch.Unavailable("unavailable")))
        assertFalse(shouldStartDesktopApplication(DesktopInstanceLaunch.Secondary(false)))
        assertTrue(shouldStartDesktopApplication(null))
    }

    @Test
    fun `restoring an iconified window preserves its maximized state`() {
        val state = Frame.ICONIFIED or Frame.MAXIMIZED_BOTH

        assertEquals(Frame.MAXIMIZED_BOTH, restoredDesktopWindowState(state))
    }

    @Test
    fun `second coordinator signals the primary and does not acquire ownership`() {
        val directory = createTempDirectory("kino-single-instance-test")
        val activation = CountDownLatch(1)
        val primaryLaunch = DesktopSingleInstanceCoordinator.acquire(directory) {
            activation.countDown()
        }
        val primary = assertIs<DesktopInstanceLaunch.Primary>(primaryLaunch)

        try {
            val secondary = assertIs<DesktopInstanceLaunch.Secondary>(
                DesktopSingleInstanceCoordinator.acquire(directory) {},
            )

            assertTrue(secondary.activationDelivered)
            assertTrue(activation.await(2, TimeUnit.SECONDS))
        } finally {
            primary.coordinator.close()
            Files.deleteIfExists(directory.resolve("instance.endpoint"))
            Files.deleteIfExists(directory.resolve("instance.lock"))
            Files.deleteIfExists(directory)
        }
    }
}
