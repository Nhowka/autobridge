package com.nhowka.autobridge.share

import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone side of "share mobile data with the car": a SOCKS5 server (RFC 1928, username/password RFC 1929) listening
 * only on the car's network. Each connection the car opens is made by the phone over [outbound] – the mobile-data
 * network – so the head unit gets internet over the Wi-Fi link it already has with the phone (no phone hotspot).
 * UDP ASSOCIATE carries DNS and other UDP.
 */
class SocksServer(
    bind: InetAddress,
    private val user: String,
    private val pass: String,
    private val outbound: Outbound,
    port: Int = 0,
) : Closeable {
    /** How outgoing traffic leaves the phone (mobile data in the app; plain sockets in tests). */
    interface Outbound {
        fun connect(host: String, port: Int): Socket
        fun datagram(): DatagramSocket
        fun resolve(host: String): InetAddress
    }

    private val server = ServerSocket(port, 50, bind)
    val port: Int get() = server.localPort
    val bytesUp = AtomicLong()   // car → internet
    val bytesDown = AtomicLong() // internet → car
    @Volatile var connections = 0
        private set
    @Volatile private var running = true

    init {
        Thread({
            while (running) {
                val s = try { server.accept() } catch (_: SocketException) { break }
                Thread({ runCatching { handle(s) }; runCatching { s.close() } }, "socks-client").apply { isDaemon = true; start() }
            }
        }, "socks-accept").apply { isDaemon = true; start() }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 30_000
        val input = DataInputStream(c.getInputStream())
        val out = c.getOutputStream()
        // Greeting: only username/password (0x02) is accepted.
        if (input.readUnsignedByte() != 5) return
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (methods.none { it.toInt() == 2 }) { out.write(byteArrayOf(5, 0xff.toByte())); return }
        out.write(byteArrayOf(5, 2))
        if (input.readUnsignedByte() != 1) return
        val u = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
        val p = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
        if (u != user || p != pass) { out.write(byteArrayOf(1, 1)); return }
        out.write(byteArrayOf(1, 0))
        // Request.
        if (input.readUnsignedByte() != 5) return
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte()
        val host = readAddress(input)
        val port = input.readUnsignedShort()
        when (cmd) {
            1 -> connect(c, input, out, host, port)
            3 -> udpAssociate(c, input, out)
            else -> reply(out, 7)
        }
    }

    private fun readAddress(input: DataInputStream): String = when (input.readUnsignedByte()) {
        1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress!!
        3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
        4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress!!
        else -> throw SocketException("bad address type")
    }

    private fun reply(out: OutputStream, code: Int, bindAddr: InetAddress = InetAddress.getByAddress(ByteArray(4)), bindPort: Int = 0) {
        val a = bindAddr.address
        out.write(byteArrayOf(5, code.toByte(), 0, if (a.size == 4) 1 else 4) + a + byteArrayOf((bindPort shr 8).toByte(), bindPort.toByte()))
        out.flush()
    }

    private fun connect(c: Socket, input: InputStream, out: OutputStream, host: String, port: Int) {
        val remote = try { outbound.connect(host, port) } catch (e: Exception) { reply(out, 5); return }
        connections++
        try {
            reply(out, 0, remote.localAddress, remote.localPort)
            c.soTimeout = 0
            val up = Thread({ pump(input, remote.getOutputStream(), bytesUp); runCatching { remote.shutdownOutput() } }, "socks-up")
                .apply { isDaemon = true; start() }
            pump(remote.getInputStream(), out, bytesDown)
            runCatching { c.shutdownOutput() }
            up.join(10_000)
        } finally {
            connections--
            runCatching { remote.close() }
        }
    }

    /**
     * UDP ASSOCIATE: the car sends SOCKS-wrapped datagrams to our relay port; we send them out over mobile data and
     * wrap the replies. Lives as long as the control TCP connection.
     */
    private fun udpAssociate(c: Socket, input: InputStream, out: OutputStream) {
        val relay = DatagramSocket(InetSocketAddress(c.localAddress, 0))
        val ext = outbound.datagram()
        var client: InetSocketAddress? = null
        try {
            reply(out, 0, c.localAddress, relay.localPort)
            val fromCar = Thread({
                val buf = ByteArray(65_535)
                while (!relay.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { relay.receive(pkt) } catch (_: Exception) { break }
                    if (pkt.address != c.inetAddress) continue // only the car that opened this association
                    client = InetSocketAddress(pkt.address, pkt.port)
                    val d = DataInputStream(pkt.data.inputStream(pkt.offset, pkt.length))
                    d.readUnsignedShort(); if (d.readUnsignedByte() != 0) continue // no fragmentation
                    val host = readAddress(d); val port = d.readUnsignedShort()
                    val payload = d.readBytes()
                    runCatching { ext.send(DatagramPacket(payload, payload.size, outbound.resolve(host), port)) }
                    bytesUp.addAndGet(payload.size.toLong())
                }
            }, "socks-udp-up").apply { isDaemon = true; start() }
            val toCar = Thread({
                val buf = ByteArray(65_535)
                while (!ext.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { ext.receive(pkt) } catch (_: Exception) { break }
                    val to = client ?: continue
                    val a = pkt.address.address
                    val head = byteArrayOf(0, 0, 0, if (a.size == 4) 1 else 4) + a + byteArrayOf((pkt.port shr 8).toByte(), pkt.port.toByte())
                    val data = head + pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    runCatching { relay.send(DatagramPacket(data, data.size, to)) }
                    bytesDown.addAndGet(pkt.length.toLong())
                }
            }, "socks-udp-down").apply { isDaemon = true; start() }
            c.soTimeout = 0
            while (input.read() >= 0) { /* the association ends when the control connection closes */ }
            fromCar.interrupt(); toCar.interrupt()
        } finally {
            relay.close(); ext.close()
        }
    }

    private fun pump(input: InputStream, out: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n); out.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
    }
}
