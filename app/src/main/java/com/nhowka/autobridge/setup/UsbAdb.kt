package com.nhowka.autobridge.setup

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/** USB devices that expose an ADB interface (class 255, subclass 66, protocol 1) – e.g. the head unit in ADB mode. */
object UsbAdb {
    fun adbInterface(device: UsbDevice): UsbInterface? = (0 until device.interfaceCount).map(device::getInterface)
        .firstOrNull { it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 1 }

    fun findDevice(context: Context): UsbDevice? =
        context.getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull { adbInterface(it) != null }

    /** This phone's ADB key (kept so the car's "Always allow from this computer" sticks). */
    fun keys(context: Context): KeyPair {
        val f = File(context.filesDir, "adbkey")
        val kf = KeyFactory.getInstance("RSA")
        if (f.exists()) runCatching {
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(f.readBytes())) as RSAPrivateCrtKey
            return KeyPair(kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)), priv)
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        f.writeBytes(kp.private.encoded)
        return kp
    }
}

/** ADB packets over the USB bulk endpoints (with a zero-length packet after exact multiples of the packet size). */
class UsbAdbTransport(manager: UsbManager, device: UsbDevice, private val readTimeoutMs: Int = 90_000) : AdbTransport {
    private val conn: UsbDeviceConnection = manager.openDevice(device) ?: throw IOException("can't open the USB device (permission?)")
    private val iface = UsbAdb.adbInterface(device) ?: throw IOException("no ADB interface")
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private val buf = ByteArray(64 * 1024)
    private var bufLen = 0
    private var bufPos = 0

    init {
        if (!conn.claimInterface(iface, true)) { conn.close(); throw IOException("can't claim the ADB interface (another app using it?)") }
        val eps = (0 until iface.endpointCount).map(iface::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        epIn = eps.first { it.direction == UsbConstants.USB_DIR_IN }
        epOut = eps.first { it.direction == UsbConstants.USB_DIR_OUT }
    }

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = conn.bulkTransfer(epOut, data, off, minOf(16 * 1024, data.size - off), 10_000)
            if (n < 0) throw IOException("USB write failed")
            off += n
        }
        if (data.isNotEmpty() && data.size % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, ByteArray(0), 0, 10_000)
    }

    override fun read(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        val deadline = System.currentTimeMillis() + readTimeoutMs
        while (off < n) {
            if (bufPos == bufLen) {
                val r = conn.bulkTransfer(epIn, buf, buf.size, 1_000)
                if (r < 0) { if (System.currentTimeMillis() > deadline) throw IOException("USB read timed out"); continue }
                bufLen = r; bufPos = 0
            }
            val k = minOf(n - off, bufLen - bufPos)
            System.arraycopy(buf, bufPos, out, off, k)
            bufPos += k; off += k
        }
        return out
    }

    override fun close() {
        runCatching { conn.releaseInterface(iface) }
        conn.close()
    }
}
