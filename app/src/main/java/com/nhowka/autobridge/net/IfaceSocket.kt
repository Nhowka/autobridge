package com.nhowka.autobridge.net

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/** TCP sockets bound to a specific interface (SO_BINDTODEVICE), bypassing ConnectivityManager. */
object IfaceSocket {
    init { System.loadLibrary("autobridge") }

    @JvmStatic external fun bindToDevice(fd: Int, iface: String): Int

    /** Connects to [host]:[port] through [iface]. Timeout applies to connect (via SO_SNDTIMEO) and reads. */
    fun connect(host: String, port: Int, iface: String, timeoutMs: Long): FileDescriptor {
        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        try {
            ParcelFileDescriptor.dup(fd).use { dup ->
                val err = bindToDevice(dup.fd, iface)
                if (err != 0) throw IOException("SO_BINDTODEVICE($iface) failed: errno $err")
            }
            val tv = StructTimeval.fromMillis(timeoutMs)
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, tv)
            Os.connect(fd, InetAddress.getByName(host), port)
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(30_000))
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(60_000))
            return fd
        } catch (e: Exception) {
            runCatching { Os.close(fd) }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }
}

/** An outgoing connection used by [LocalForwarder]. */
class Upstream(val input: InputStream, val output: OutputStream, val shutdownOutput: () -> Unit, val close: () -> Unit)

/** How a [LocalForwarder] opens its outgoing connections. */
fun interface UpstreamConnector {
    fun connect(host: String, port: Int, timeoutMs: Int): Upstream
}

/** Through a specific interface with SO_BINDTODEVICE (Android Auto holds the car Wi-Fi privately). */
fun ifaceConnector(iface: String) = UpstreamConnector { host, port, timeout ->
    val fd = IfaceSocket.connect(host, port, iface, timeout.toLong())
    Upstream(FileInputStream(fd), FileOutputStream(fd),
        { runCatching { Os.shutdown(fd, OsConstants.SHUT_WR) } }, { runCatching { Os.close(fd) } })
}

/** Through an Android [android.net.Network] (car hotspot visible to apps), without binding the whole process. */
fun networkConnector(network: android.net.Network) = UpstreamConnector { host, port, timeout ->
    val s = network.socketFactory.createSocket()
    s.connect(java.net.InetSocketAddress(host, port), timeout)
    Upstream(s.getInputStream(), s.getOutputStream(), { runCatching { s.shutdownOutput() } }, { runCatching { s.close() } })
}

/**
 * In-app relay (like dvr_relay.py): listens on 127.0.0.1 and forwards each connection to [targetHost]:[targetPort]
 * through [connector]. Player, thumbnails, downloads and the hub API then just use 127.0.0.1 while the rest of the
 * app (map tiles) keeps using the phone's normal internet connection.
 */
class LocalForwarder(
    private val targetHost: String,
    private val targetPort: Int,
    private val connector: UpstreamConnector,
    private val connectTimeoutMs: Int = 2_500,
) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var running = true

    init {
        Thread({
            while (running) {
                val client = try { server.accept() } catch (_: SocketException) { break }
                Thread({ handle(client) }, "fwd-$targetPort").apply { isDaemon = true; start() }
            }
        }, "fwd-accept-$targetPort").apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket) {
        val up = try {
            connector.connect(targetHost, targetPort, connectTimeoutMs)
        } catch (_: Exception) {
            runCatching { client.close() }; return
        }
        val t = Thread({
            pump(client.getInputStream(), up.output)
            up.shutdownOutput()
        }, "fwd-up").apply { isDaemon = true; start() }
        pump(up.input, client.getOutputStream())
        runCatching { client.shutdownOutput() }
        t.join(5_000)
        runCatching { client.close() }
        up.close()
    }

    private fun pump(input: InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n); out.flush()
            }
        } catch (_: Exception) {
        }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
    }
}
