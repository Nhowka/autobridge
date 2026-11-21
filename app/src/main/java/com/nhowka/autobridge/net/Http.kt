package com.nhowka.autobridge.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder

class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
    /** Client address (the phone's IP on the car network). */
    val remote: String = "",
) {
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

class Response(val code: Int, val body: JSONObject)

/** A route returns null when the request isn't for it. */
fun interface Route {
    fun handle(req: Request): Response?
}

/**
 * The car's small control API on its Wi-Fi (JSON over HTTP): `/api/info`, `/api/share`. No pairing by design – the
 * car's Wi-Fi is password-protected and the only phone on it is the one running Android Auto.
 */
class BridgeServer(
    private val routes: List<Route>,
    port: Int = DEFAULT_PORT,
    bind: InetAddress? = null,
) : Closeable {
    companion object {
        const val DEFAULT_PORT = 8091
        private const val MAX_BODY = 64 * 1024
    }

    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, port)) }
    val port: Int get() = server.localPort
    @Volatile private var running = true

    init {
        Thread({
            while (running) {
                val s = try { server.accept() } catch (_: SocketException) { break }
                Thread({ s.use(::handle) }, "bridge-http").apply { isDaemon = true; start() }
            }
        }, "bridge-accept").apply { isDaemon = true; start() }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 15_000
        val out = s.getOutputStream()
        val req = try { read(s.getInputStream(), s.inetAddress?.hostAddress ?: "") } catch (_: Exception) { null }
            ?: return send(out, Response(400, JSONObject().put("error", "bad request")))
        for (r in routes) r.handle(req)?.let { return send(out, it) }
        send(out, Response(404, JSONObject().put("error", "not found")))
    }

    private fun read(input: InputStream, remote: String): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16 * 1024) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size < 3) return null
        val target = parts[1]
        val q = target.indexOf('?')
        val path = URLDecoder.decode(if (q >= 0) target.substring(0, q) else target, "UTF-8")
        val query = if (q < 0) emptyMap() else target.substring(q + 1).split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else URLDecoder.decode(it.substring(0, eq), "UTF-8") to URLDecoder.decode(it.substring(eq + 1), "UTF-8")
        }.toMap()
        val headers = lines.drop(1).mapNotNull { l ->
            val c = l.indexOf(':'); if (c <= 0) null else l.substring(0, c).trim().lowercase() to l.substring(c + 1).trim()
        }.toMap()
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) return null
        val body = ByteArray(len)
        var n = 0
        while (n < len) { val r = input.read(body, n, len - n); if (r < 0) return null; n += r }
        return Request(parts[0].uppercase(), path, query, headers, body, remote)
    }

    private fun send(out: OutputStream, r: Response) {
        val body = r.body.toString().toByteArray()
        out.write(("HTTP/1.1 ${r.code} OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    override fun close() { running = false; runCatching { server.close() } }
}
