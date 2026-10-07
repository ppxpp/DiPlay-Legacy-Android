package com.shilapi.xcertplay.network.userspace

import com.shilapi.xcertplay.network.AirPlayNetwork
import com.shilapi.xcertplay.transport.EthernetFramePipe
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** One USB attachment owns its sockets and frame pumps. Only one attachment may exist at a time. */
class UserSpaceNetwork(
    private val ncm: EthernetFramePipe,
    address: InetAddress,
    mac: ByteArray,
    private val onFrame: (ByteArray) -> Unit = {},
    private val onError: (Throwable) -> Unit,
) : AirPlayNetwork, Closeable {
    private val closed = AtomicBoolean(false)
    private val endpoints = java.util.Collections.newSetFromMap(ConcurrentHashMap<Closeable, Boolean>())
    private val workers = mutableListOf<Thread>()
    override val supportsIpv4 = false

    init {
        require(address is Inet6Address && address.isLinkLocalAddress)
        UserSpaceNative.start(address.address, mac)
        try {
            worker("userspace-usb-in") {
                while (!closed.get()) {
                    val frame = ncm.recv(1000) ?: continue
                    UserSpaceNative.input(frame)
                    onFrame(frame)
                }
            }
            worker("userspace-usb-out") {
                while (!closed.get()) {
                    val frame = UserSpaceNative.output()
                    if (frame != null) ncm.send(frame, 1000)
                    else LockSupport.parkNanos(1_000_000)
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun worker(name: String, block: () -> Unit) {
        workers += Thread({
            try { block() } catch (error: Throwable) { if (!closed.get()) onError(error) }
        }, name).apply { isDaemon = true; start() }
    }

    @Synchronized private fun <T : Closeable> own(endpoint: T): T {
        if (closed.get()) { endpoint.close(); throw SocketException("USB network detached") }
        endpoints.add(endpoint)
        return endpoint
    }
    private fun forget(endpoint: Closeable) { endpoints.remove(endpoint) }
    override fun server(): ServerSocket = own(UsbServerSocket(::own, ::forget))
    override fun datagram(): DatagramSocket = own(UsbDatagramSocket(::forget))

    override fun close() {
        val owned = synchronized(this) {
            if (!closed.compareAndSet(false, true)) return
            endpoints.toList().also { endpoints.clear() }
        }
        owned.forEach { runCatching { it.close() } }
        runCatching { ncm.close() }
        workers.forEach { it.interrupt() }
        var interrupted = false
        workers.filter { it !== Thread.currentThread() }.forEach {
            try { it.join(2000) } catch (_: InterruptedException) { interrupted = true }
        }
        try { UserSpaceNative.stop() } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}

private fun ipv6(endpoint: SocketAddress?): InetSocketAddress {
    val a = endpoint as? InetSocketAddress ?: throw SocketException("IPv6 socket address required")
    if (a.address !is Inet6Address) throw SocketException("USB backend accepts IPv6 only")
    return a
}
private fun decodeEndpoint(bytes: ByteArray): InetSocketAddress = InetSocketAddress(
    InetAddress.getByAddress(bytes.copyOfRange(0, 16)),
    ((bytes[16].toInt() and 255) shl 8) or (bytes[17].toInt() and 255),
)

private class Descriptor(udp: Boolean, supplied: Int? = null) {
    val fd = supplied ?: UserSpaceNative.socket(udp)
    private val closed = AtomicBoolean(false)
    fun requireOpen(): Int { if (closed.get()) throw SocketException("USB socket closed"); return fd }
    fun close() { if (closed.compareAndSet(false, true)) UserSpaceNative.close(fd) }
    fun isClosed() = closed.get()
}

private class UsbServerSocket(
    private val own: (Closeable) -> Closeable,
    private val forget: (Closeable) -> Unit,
) : ServerSocket() {
    private val descriptor = Descriptor(false)
    private var bound: InetSocketAddress? = null
    private var reuse = false
    private var timeout = 0
    override fun bind(endpoint: SocketAddress?) = bind(endpoint, 8)
    override fun bind(endpoint: SocketAddress?, backlog: Int) {
        if (bound != null) throw SocketException("USB listener already bound")
        val a = ipv6(endpoint)
        UserSpaceNative.bind(descriptor.requireOpen(), a.address.address, a.port, true)
        bound = decodeEndpoint(UserSpaceNative.endpoint(descriptor.fd, false))
    }
    override fun accept(): Socket {
        val accepted = UserSpaceNative.accept(descriptor.requireOpen())
        return try { own(UsbSocket(accepted, forget)) as Socket } catch (error: Throwable) {
            // UsbSocket takes ownership immediately, including endpoint lookup failures.
            throw error
        }
    }
    override fun getLocalPort() = bound?.port ?: -1
    override fun getInetAddress(): InetAddress? = bound?.address
    override fun getLocalSocketAddress(): SocketAddress? = bound
    override fun isBound() = bound != null
    override fun isClosed() = descriptor.isClosed()
    override fun setReuseAddress(on: Boolean) { UserSpaceNative.option(descriptor.requireOpen(), 1, if (on) 1 else 0); reuse = on }
    override fun getReuseAddress() = reuse
    override fun setSoTimeout(ms: Int) { require(ms >= 0); UserSpaceNative.option(descriptor.requireOpen(), 0, ms); timeout = ms }
    override fun getSoTimeout() = timeout
    override fun close() { descriptor.close(); forget(this); super.close() }
}

private class UsbSocket(fd: Int, private val forget: (Closeable) -> Unit) : Socket() {
    private val descriptor = Descriptor(false, fd)
    private val local: InetSocketAddress
    private val peer: InetSocketAddress
    init {
        try {
            local = decodeEndpoint(UserSpaceNative.endpoint(fd, false))
            peer = decodeEndpoint(UserSpaceNative.endpoint(fd, true))
        } catch (error: Throwable) { descriptor.close(); throw error }
    }
    private var timeout = 0
    private var noDelay = false
    private var keepAliveValue = false
    private var lingerValue = -1
    override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b) < 0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            require(off >= 0 && len >= 0 && off <= b.size - len)
            if (len == 0) return 0
            return UserSpaceNative.read(descriptor.requireOpen(), b, off, len)
        }
        override fun close() = this@UsbSocket.close()
    }
    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            require(off >= 0 && len >= 0 && off <= b.size - len)
            UserSpaceNative.write(descriptor.requireOpen(), b, off, len)
        }
        override fun close() = this@UsbSocket.close()
    }
    override fun getInetAddress(): InetAddress = peer.address
    override fun getLocalAddress(): InetAddress = local.address
    override fun getPort() = peer.port
    override fun getLocalPort() = local.port
    override fun getRemoteSocketAddress(): SocketAddress = peer
    override fun getLocalSocketAddress(): SocketAddress = local
    override fun isConnected() = true
    override fun isBound() = true
    override fun isClosed() = descriptor.isClosed()
    override fun setSoTimeout(ms: Int) { require(ms >= 0); UserSpaceNative.option(descriptor.requireOpen(), 0, ms); timeout = ms }
    override fun getSoTimeout() = timeout
    override fun setTcpNoDelay(on: Boolean) { UserSpaceNative.option(descriptor.requireOpen(), 2, if (on) 1 else 0); noDelay = on }
    override fun getTcpNoDelay() = noDelay
    override fun setKeepAlive(on: Boolean) { UserSpaceNative.option(descriptor.requireOpen(), 3, if (on) 1 else 0); keepAliveValue = on }
    override fun getKeepAlive() = keepAliveValue
    override fun setSoLinger(on: Boolean, seconds: Int) { require(!on || seconds >= 0); lingerValue = if (on) seconds else -1; UserSpaceNative.option(descriptor.requireOpen(), 4, lingerValue) }
    override fun getSoLinger() = lingerValue
    override fun close() { descriptor.close(); forget(this); super.close() }
}

private class UsbDatagramSocket(private val forget: (Closeable) -> Unit) : DatagramSocket(null as SocketAddress?) {
    private val descriptor = Descriptor(true)
    private var bound: InetSocketAddress? = null
    private var timeout = 0
    private var reuse = false
    override fun bind(endpoint: SocketAddress?) {
        if (bound != null) throw SocketException("USB datagram socket already bound")
        val a = ipv6(endpoint)
        UserSpaceNative.bind(descriptor.requireOpen(), a.address.address, a.port, false)
        bound = decodeEndpoint(UserSpaceNative.endpoint(descriptor.fd, false))
    }
    override fun send(packet: DatagramPacket) {
        val a = ipv6(packet.socketAddress)
        UserSpaceNative.send(descriptor.requireOpen(), a.address.address, a.port, packet.data, packet.offset, packet.length)
    }
    override fun receive(packet: DatagramPacket) {
        val bytes = UserSpaceNative.receive(descriptor.requireOpen(), packet.length)
        val source = decodeEndpoint(bytes)
        val length = bytes.size - 18
        bytes.copyInto(packet.data, packet.offset, 18, bytes.size)
        packet.length = length
        packet.socketAddress = source
    }
    override fun getLocalPort() = bound?.port ?: -1
    override fun getLocalAddress(): InetAddress? = bound?.address
    override fun getLocalSocketAddress(): SocketAddress? = bound
    override fun isBound() = bound != null
    override fun isClosed() = descriptor.isClosed()
    override fun setReuseAddress(on: Boolean) { UserSpaceNative.option(descriptor.requireOpen(), 1, if (on) 1 else 0); reuse = on }
    override fun getReuseAddress() = reuse
    override fun setSoTimeout(ms: Int) { require(ms >= 0); UserSpaceNative.option(descriptor.requireOpen(), 0, ms); timeout = ms }
    override fun getSoTimeout() = timeout
    override fun close() { descriptor.close(); forget(this); super.close() }
}
