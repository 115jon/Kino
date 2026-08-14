package com.nuvio.app

import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

internal class DesktopActivationRelay {
    private var handler: (() -> Unit)? = null
    private var pending = false

    fun requestActivation() {
        val callback = synchronized(this) {
            handler.also {
                if (it == null) pending = true
            }
        }
        callback?.invoke()
    }

    fun attach(callback: () -> Unit): AutoCloseable {
        val deliverPending = synchronized(this) {
            handler = callback
            pending.also { pending = false }
        }
        if (deliverPending) callback()

        return AutoCloseable {
            synchronized(this) {
                if (handler === callback) handler = null
            }
        }
    }
}

internal data class SingleInstanceEndpoint(
    val port: Int,
    val token: String,
)

internal object SingleInstanceProtocol {
    private const val PREFIX = "KINO/1"
    private val secureRandom = SecureRandom()
    private val tokenPattern = Regex("[0-9a-f]{64}")

    fun createToken(): String = ByteArray(32)
        .also(secureRandom::nextBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun encodeDescriptor(endpoint: SingleInstanceEndpoint): String =
        "$PREFIX ${endpoint.port} ${endpoint.token}\n"

    fun parseDescriptor(value: String): SingleInstanceEndpoint? {
        val parts = value.trim().split(' ')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val token = parts[2].takeIf(tokenPattern::matches) ?: return null
        return SingleInstanceEndpoint(port, token)
    }

    fun activationRequest(token: String): String = "$PREFIX ACTIVATE $token"

    fun isValidActivationRequest(request: String, token: String): Boolean =
        request == activationRequest(token)
}

internal sealed interface DesktopInstanceLaunch {
    data class Primary(val coordinator: DesktopSingleInstanceCoordinator) : DesktopInstanceLaunch
    data class Secondary(val activationDelivered: Boolean) : DesktopInstanceLaunch
    data class Unavailable(val reason: String) : DesktopInstanceLaunch
}

internal class DesktopSingleInstanceCoordinator private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val descriptorPath: Path,
    private val token: String,
    private val onActivation: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val acceptThread = thread(
        start = true,
        isDaemon = true,
        name = "kino-single-instance",
    ) {
        acceptRequests()
    }

    private fun acceptRequests() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                if (closed.get()) return
                continue
            }
            socket.use(::handleRequest)
        }
    }

    private fun handleRequest(socket: Socket) {
        socket.soTimeout = CLIENT_TIMEOUT_MILLIS
        val request = runCatching { socket.getInputStream().readBoundedLine(MAX_REQUEST_BYTES) }
            .getOrNull()
        val accepted = request != null && SingleInstanceProtocol.isValidActivationRequest(request, token)
        if (accepted) runCatching(onActivation)

        runCatching {
            BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)).use { writer ->
                writer.write(if (accepted) ACK else REJECTED)
                writer.newLine()
                writer.flush()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(descriptorPath) }
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { acceptThread.join(CLOSE_JOIN_MILLIS) }
    }

    companion object {
        private const val LOCK_FILE = "instance.lock"
        private const val ENDPOINT_FILE = "instance.endpoint"
        private const val ACK = "ACK"
        private const val REJECTED = "REJECTED"
        private const val MAX_REQUEST_BYTES = 256
        private const val CLIENT_TIMEOUT_MILLIS = 500
        private const val CONNECT_ATTEMPT_MILLIS = 200
        private const val CONNECT_RETRY_MILLIS = 40L
        private const val CONNECT_DEADLINE_MILLIS = 1_500L
        private const val CLOSE_JOIN_MILLIS = 1_000L

        fun defaultDirectory(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            return if (localAppData != null) {
                Path.of(localAppData, "Kino", "instance")
            } else {
                Path.of(System.getProperty("user.home"), ".kino", "instance")
            }
        }

        fun acquire(
            directory: Path = defaultDirectory(),
            onActivation: () -> Unit,
        ): DesktopInstanceLaunch {
            return runCatching {
                Files.createDirectories(directory)
                restrictToOwner(directory, isDirectory = true)
                val lockPath = directory.resolve(LOCK_FILE)
                val descriptorPath = directory.resolve(ENDPOINT_FILE)
                val channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
                restrictToOwner(lockPath, isDirectory = false)

                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }

                if (lock == null) {
                    channel.close()
                    DesktopInstanceLaunch.Secondary(signalPrimary(descriptorPath))
                } else {
                    createPrimary(channel, lock, descriptorPath, onActivation)
                }
            }.getOrElse { error ->
                DesktopInstanceLaunch.Unavailable(error.message ?: error::class.simpleName.orEmpty())
            }
        }

        private fun createPrimary(
            channel: FileChannel,
            lock: FileLock,
            descriptorPath: Path,
            onActivation: () -> Unit,
        ): DesktopInstanceLaunch.Primary {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8)
                }
                val token = SingleInstanceProtocol.createToken()
                val descriptor = SingleInstanceProtocol.encodeDescriptor(
                    SingleInstanceEndpoint(server.localPort, token),
                )
                Files.writeString(
                    descriptorPath,
                    descriptor,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                restrictToOwner(descriptorPath, isDirectory = false)
                return DesktopInstanceLaunch.Primary(
                    DesktopSingleInstanceCoordinator(
                        channel,
                        lock,
                        server,
                        descriptorPath,
                        token,
                        onActivation,
                    ),
                )
            } catch (error: Throwable) {
                runCatching { lock.release() }
                runCatching { channel.close() }
                throw error
            }
        }

        private fun signalPrimary(descriptorPath: Path): Boolean {
            val deadline = System.nanoTime() + CONNECT_DEADLINE_MILLIS * 1_000_000
            while (System.nanoTime() < deadline) {
                val endpoint = runCatching {
                    SingleInstanceProtocol.parseDescriptor(Files.readString(descriptorPath))
                }.getOrNull()
                if (endpoint != null && sendActivation(endpoint, deadline)) return true
                Thread.sleep(CONNECT_RETRY_MILLIS)
            }
            return false
        }

        private fun sendActivation(endpoint: SingleInstanceEndpoint, deadline: Long): Boolean {
            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000)
                .coerceAtLeast(1)
                .toInt()
            return runCatching {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port),
                        min(CONNECT_ATTEMPT_MILLIS, remainingMillis),
                    )
                    socket.soTimeout = min(CLIENT_TIMEOUT_MILLIS, remainingMillis)
                    BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)).apply {
                        write(SingleInstanceProtocol.activationRequest(endpoint.token))
                        newLine()
                        flush()
                    }
                    socket.getInputStream().readBoundedLine(MAX_REQUEST_BYTES) == ACK
                }
            }.getOrDefault(false)
        }

        private fun restrictToOwner(path: Path, isDirectory: Boolean) {
            val permissions = if (isDirectory) "rwx------" else "rw-------"
            runCatching {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
            }
        }
    }
}

private fun InputStream.readBoundedLine(maxBytes: Int): String {
    val bytes = ArrayList<Byte>(min(maxBytes, 64))
    while (true) {
        val value = read()
        if (value == -1 || value == '\n'.code) break
        if (value != '\r'.code) {
            require(bytes.size < maxBytes) { "Single-instance request is too large" }
            bytes += value.toByte()
        }
    }
    return bytes.toByteArray().toString(StandardCharsets.UTF_8)
}
