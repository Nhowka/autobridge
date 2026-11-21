package com.nhowka.autobridge.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Plain sockets stand in for the phone's mobile-data network. */
private object Direct : SocksServer.Outbound {
    override fun connect(host: String, port: Int) = Socket(host, port)
    override fun datagram() = DatagramSocket()
    override fun resolve(host: String): InetAddress = InetAddress.getByName(host)
}

class SocksServerTest {
    private val lo = InetAddress.getByName("127.0.0.1")

    private fun handshake(s: Socket, user: String, pass: String): Int {
        val o = s.getOutputStream(); val i = DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 2))
        assertEquals(5, i.readUnsignedByte()); assertEquals(2, i.readUnsignedByte())
        o.write(byteArrayOf(1, user.length.toByte()) + user.toByteArray() + byteArrayOf(pass.length.toByte()) + pass.toByteArray())
        i.readUnsignedByte()
        return i.readUnsignedByte()
    }

    @Test fun tcpConnectThroughProxyWithPassword() {
        ServerSocket(0, 5, lo).use { echo ->
            Thread { echo.accept().use { c -> val b = ByteArray(5); DataInputStream(c.getInputStream()).readFully(b); c.getOutputStream().write(b.reversedArray()) } }.start()
            SocksServer(lo, "car", "secret", Direct).use { socks ->
                Socket(lo, socks.port).use { s -> assertEquals(1, handshake(s, "car", "wrong")) } // rejected
                Socket(lo, socks.port).use { s ->
                    assertEquals(0, handshake(s, "car", "secret"))
                    val o = s.getOutputStream(); val i = DataInputStream(s.getInputStream())
                    o.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (echo.localPort shr 8).toByte(), echo.localPort.toByte()))
                    val rep = ByteArray(10); i.readFully(rep)
                    assertEquals(0, rep[1].toInt())
                    o.write("hello".toByteArray())
                    val back = ByteArray(5); i.readFully(back)
                    assertEquals("olleh", String(back))
                }
                assertTrue(socks.bytesUp.get() >= 5 && socks.bytesDown.get() >= 5)
            }
        }
    }

    @Test fun udpAssociateRelaysDatagrams() {
        DatagramSocket(0, lo).use { echo ->
            Thread { val p = DatagramPacket(ByteArray(100), 100); echo.receive(p); echo.send(DatagramPacket(p.data.copyOf(p.length).reversedArray(), p.length, p.socketAddress)) }.start()
            SocksServer(lo, "car", "secret", Direct).use { socks ->
                Socket(lo, socks.port).use { ctl ->
                    assertEquals(0, handshake(ctl, "car", "secret"))
                    ctl.getOutputStream().write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                    val rep = ByteArray(10); DataInputStream(ctl.getInputStream()).readFully(rep)
                    val relayPort = ((rep[8].toInt() and 0xff) shl 8) or (rep[9].toInt() and 0xff)
                    DatagramSocket(0, lo).use { u ->
                        u.soTimeout = 3_000
                        val head = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (echo.localPort shr 8).toByte(), echo.localPort.toByte())
                        val msg = head + "ping".toByteArray()
                        u.send(DatagramPacket(msg, msg.size, InetSocketAddress(lo, relayPort)))
                        val p = DatagramPacket(ByteArray(100), 100); u.receive(p)
                        assertEquals("gnip", String(p.data, 10, p.length - 10))
                    }
                }
            }
        }
    }
}
