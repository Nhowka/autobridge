package com.nhowka.autobridge.setup

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

/** Raw ADB packets over USB bulk endpoints (or TCP for tests). */
interface AdbTransport : Closeable {
    fun write(data: ByteArray)
    /** Reads exactly [n] bytes. */
    fun read(n: Int): ByteArray
}

/**
 * Minimal ADB host (protocol as in AOSP `adb/protocol.txt`): connect + RSA auth, `shell:` commands and `sync:` push.
 * Single-threaded: one stream at a time, which is all the car set-up needs. The same steps the web installer does with
 * Tango, so the phone app can set up / update the car over a USB cable without a browser.
 */
class AdbClient(private val transport: AdbTransport, private val keys: KeyPair, private val keyName: String = "RemoteDVR phone") : Closeable {
    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val VERSION = 0x01000001
        private const val MAX_DATA = 256 * 1024

        /** ADB's public key format: base64 of the "mincrypt" RSA struct, then " name". */
        fun publicKeyBlob(pub: RSAPublicKey): ByteArray {
            val words = 64 // 2048-bit
            val n = pub.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
            val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
            val b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(words); b.putInt(n0inv)
            putLittleWords(b, n, words); putLittleWords(b, rr, words)
            b.putInt(pub.publicExponent.toInt())
            return b.array()
        }

        private fun putLittleWords(b: ByteBuffer, v: BigInteger, words: Int) {
            val mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)
            var x = v
            repeat(words) { b.putInt(x.and(mask).toInt()); x = x.shiftRight(32) }
        }

        fun publicKeyString(pub: RSAPublicKey, name: String): String = Base64.getEncoder().encodeToString(publicKeyBlob(pub)) + " " + name

        /** SHA-1 DigestInfo prefix: ADB signs the 20-byte token as if it were a SHA-1 hash (PKCS#1 v1.5). */
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }

    private var maxData = 4096
    private var nextLocalId = 1
    var banner: String = ""
        private set

    private class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd); h.putInt(arg0); h.putInt(arg1); h.putInt(data.size)
        h.putInt(data.sumOf { it.toInt() and 0xff }) // checksum (ignored by newer adbd, required by old ones)
        h.putInt(cmd.inv())
        transport.write(h.array())
        if (data.isNotEmpty()) transport.write(data)
    }

    private fun receive(): Packet {
        val h = ByteBuffer.wrap(transport.read(24)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int; h.int; val magic = h.int
        if (magic != cmd.inv()) throw IOException("bad ADB packet")
        return Packet(cmd, a0, a1, if (len > 0) transport.read(len) else ByteArray(0))
    }

    /**
     * Connects. If the device doesn't know our key, it shows "Allow USB debugging?" – [onAuthPrompt] is called and
     * this waits (up to the transport's read timeout) for the user to accept.
     */
    fun connect(onAuthPrompt: () -> Unit = {}) {
        send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var sentKey = false
        var signed = false
        while (true) {
            val p = receive()
            when (p.cmd) {
                CNXN -> { maxData = minOf(p.arg1, MAX_DATA).coerceAtLeast(4096); banner = String(p.data).trimEnd('\u0000'); return }
                AUTH -> if (p.arg0 == 1) { // TOKEN
                    if (!signed) {
                        signed = true
                        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                        c.init(Cipher.ENCRYPT_MODE, keys.private)
                        send(AUTH, 2, 0, c.doFinal(SHA1_PREFIX + p.data)) // SIGNATURE
                    } else if (!sentKey) {
                        sentKey = true
                        send(AUTH, 3, 0, (publicKeyString(keys.public as RSAPublicKey, keyName) + "\u0000").toByteArray()) // RSAPUBLICKEY
                        onAuthPrompt()
                    } else throw IOException("USB debugging was not allowed on the device")
                }
                else -> {} // ignore anything else before CNXN
            }
        }
    }

    /** Runs a shell command and returns its output (stdout+stderr, "shell:" v1 protocol). */
    fun shell(command: String): String {
        val out = ByteArrayOutputStream()
        stream("shell:$command") { _, data -> out.write(data) }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Opens a service stream; [onData] gets everything the device writes until it closes. */
    private fun stream(service: String, afterOpen: ((Int, Int) -> Unit)? = null, onData: (Int, ByteArray) -> Unit) {
        val local = nextLocalId++
        send(OPEN, local, 0, "$service\u0000".toByteArray())
        var remote = 0
        while (true) {
            val p = receive()
            if (p.arg1 != local && p.cmd != CNXN) continue
            when (p.cmd) {
                OKAY -> if (remote == 0) { remote = p.arg0; afterOpen?.invoke(local, remote) }
                WRTE -> { onData(remote, p.data); send(OKAY, local, p.arg0) }
                CLSE -> { if (remote != 0) send(CLSE, local, remote); if (remote == 0) throw IOException("device refused $service"); return }
            }
        }
    }

    /** Writes [data] on an open stream, waiting for the device's OKAY after each packet (ADB flow control). */
    private fun writeStream(local: Int, remote: Int, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(maxData, data.size - off)
            send(WRTE, local, remote, data.copyOfRange(off, off + n))
            off += n
            while (true) {
                val p = receive()
                if (p.cmd == OKAY && p.arg1 == local) break
                if (p.cmd == CLSE && p.arg1 == local) throw IOException("device closed the stream")
                if (p.cmd == WRTE && p.arg1 == local) throw IOException("unexpected data: ${String(p.data)}")
            }
        }
    }

    /** Pushes a file with the sync protocol (SEND/DATA/DONE). [progress] gets bytes sent. */
    fun push(remotePath: String, bytes: ByteArray, mode: Int = 420 /* 0644 */, progress: (Long) -> Unit = {}) {
        fun le(cmd: String, n: Int) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(cmd.toByteArray()).putInt(n).array()
        val local = nextLocalId++
        send(OPEN, local, 0, "sync:\u0000".toByteArray())
        var remote = 0
        while (remote == 0) {
            val p = receive()
            if (p.cmd == OKAY && p.arg1 == local) remote = p.arg0
            else if (p.cmd == CLSE && p.arg1 == local) throw IOException("device refused sync") // (CLSE of an earlier stream: ignore)
        }
        val spec = "$remotePath,$mode".toByteArray()
        val buf = ByteArrayOutputStream()
        buf.write(le("SEND", spec.size)); buf.write(spec)
        writeStream(local, remote, buf.toByteArray())
        val chunk = 64 * 1024
        var off = 0
        while (off < bytes.size) {
            val n = minOf(chunk, bytes.size - off)
            writeStream(local, remote, le("DATA", n) + bytes.copyOfRange(off, off + n))
            off += n
            progress(off.toLong())
        }
        writeStream(local, remote, le("DONE", (System.currentTimeMillis() / 1000).toInt()))
        // Result: OKAY or FAIL <len> <message>.
        val reply = ByteArrayOutputStream()
        while (reply.size() < 8) {
            val p = receive()
            if (p.cmd == WRTE && p.arg1 == local) { reply.write(p.data); send(OKAY, local, remote) }
            else if (p.cmd == CLSE && p.arg1 == local) break
        }
        val r = reply.toByteArray()
        send(CLSE, local, remote)
        if (r.size < 4 || String(r, 0, 4) != "OKAY") throw IOException("push failed: " + String(r).drop(8))
    }

    override fun close() { runCatching { transport.close() } }
}
