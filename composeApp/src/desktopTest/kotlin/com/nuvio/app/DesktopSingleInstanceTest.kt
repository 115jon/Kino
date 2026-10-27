package com.nuvio.app

import java.awt.Frame
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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

    @Test
    fun `instance takes ownership once an unreachable previous owner releases the lock`() {
        val directory = createTempDirectory("kino-single-instance-takeover")
        val lockPath = directory.resolve("instance.lock")
        val staleChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val staleLock = staleChannel.lock()
        Files.writeString(directory.resolve("instance.endpoint"), "KINO/1 1 ${"0".repeat(64)}\n")
        val releaser = thread {
            Thread.sleep(300)
            staleLock.release()
            staleChannel.close()
        }

        val launch = DesktopSingleInstanceCoordinator.acquire(directory, takeoverTimeoutMillis = 10_000) {}
        releaser.join()
        val primary = assertIs<DesktopInstanceLaunch.Primary>(launch)
        primary.coordinator.close()
        deleteInstanceDirectory(directory)
    }

    @Test
    fun `instance gives up when an unreachable owner keeps the lock`() {
        val directory = createTempDirectory("kino-single-instance-held")
        val staleChannel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val staleLock = staleChannel.lock()
        try {
            val launch = DesktopSingleInstanceCoordinator.acquire(directory, takeoverTimeoutMillis = 200) {}

            assertEquals(DesktopInstanceLaunch.Secondary(activationDelivered = false), launch)
        } finally {
            staleLock.release()
            staleChannel.close()
            deleteInstanceDirectory(directory)
        }
    }

    private fun deleteInstanceDirectory(directory: Path) {
        Files.deleteIfExists(directory.resolve("instance.endpoint"))
        Files.deleteIfExists(directory.resolve("instance.lock"))
        Files.deleteIfExists(directory)
    }
}
