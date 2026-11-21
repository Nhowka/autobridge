package com.nhowka.autobridge.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** TCP transport so the protocol can be tested against an emulator's adbd (port 5555). */
class TcpTransport(host: String, port: Int) : AdbTransport {
    private val s = Socket(host, port).apply { soTimeout = 15_000 }
    override fun write(data: ByteArray) { s.getOutputStream().write(data); s.getOutputStream().flush() }
    override fun read(n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = s.getInputStream().read(b, off, n - off); if (r < 0) throw java.io.EOFException(); off += r }
        return b
    }
    override fun close() = s.close()
}

class AdbClientTest {
    /** Our public-key encoding must equal what the official adb tool wrote for the same key (~/.android/adbkey.pub). */
    @Test fun publicKeyMatchesOfficialAdb() {
        val priv = File(System.getProperty("user.home"), ".android/adbkey")
        val pub = File(System.getProperty("user.home"), ".android/adbkey.pub")
        assumeTrue(priv.exists() && pub.exists())
        val der = Base64.getMimeDecoder().decode(priv.readText().lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(k.modulus, k.publicExponent)) as java.security.interfaces.RSAPublicKey
        val expected = pub.readText().trim().substringBefore(' ')
        assertEquals(expected, Base64.getEncoder().encodeToString(AdbClient.publicKeyBlob(publicKey)))
    }

    /** Integration test against the emulator: RDVR_ADB_TCP=127.0.0.1:5555 ./gradlew testDebugUnitTest */
    @Test fun shellAndPushOnEmulator() {
        val target = System.getenv("RDVR_ADB_TCP")
        assumeTrue(target != null)
        val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        AdbClient(TcpTransport(target!!.substringBefore(':'), target.substringAfter(':').toInt()), keys).use { adb ->
            adb.connect()
            assertTrue(adb.banner, adb.banner.startsWith("device::"))
            assertEquals("hello", adb.shell("echo hello").trim())
            val data = ByteArray(300_000) { (it * 31).toByte() }
            adb.push("/data/local/tmp/rdvr_adbtest.bin", data)
            assertEquals("300000", adb.shell("stat -c %s /data/local/tmp/rdvr_adbtest.bin").trim())
            val sum = java.security.MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
            assertTrue(adb.shell("md5sum /data/local/tmp/rdvr_adbtest.bin; rm /data/local/tmp/rdvr_adbtest.bin").startsWith(sum))
        }
    }
}

/** End-to-end set-up against the emulator (RDVR_ADB_TCP + RDVR_APK = debug APK path + RDVR_DEX = helper dex). */
class CarSetupTest {
    @Test fun installGrantOpenOnEmulator() {
        val target = System.getenv("RDVR_ADB_TCP"); val apkPath = System.getenv("RDVR_APK"); val dexPath = System.getenv("RDVR_DEX")
        assumeTrue(target != null && apkPath != null && dexPath != null)
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val lines = ArrayList<String>()
        AdbClient(TcpTransport(target!!.substringBefore(':'), target.substringAfter(':').toInt()), keys).use { adb ->
            adb.connect()
            val setup = CarSetup(adb) { lines += it }
            val dev = setup.device()
            assertTrue(dev.toString(), dev.sdk == 30)
            // Pretend to be a head unit so the helper path (first on Desay SV) is exercised.
            val asHeadUnit = dev.copy(fingerprint = "DesaySV/test")
            val version = System.getenv("RDVR_VERSION")?.toLong() ?: 1L
            assertTrue(lines.joinToString("\n"), setup.install(File(apkPath!!).readBytes(), version, File(dexPath!!).readBytes(), asHeadUnit))
            // Downgrade (older build over a newer one) must work too, like `pm install -d`.
            System.getenv("RDVR_OLD_APK")?.let { old ->
                assertTrue(lines.joinToString("\n"), setup.install(File(old).readBytes(), 1, File(dexPath).readBytes(), asHeadUnit))
                assertEquals(1L, setup.installedVersion())
                assertTrue(setup.install(File(apkPath).readBytes(), version, File(dexPath).readBytes(), asHeadUnit))
            }
            assertTrue(lines.joinToString("\n"), lines.any { it.contains("RDVR_INSTALLED") })
            setup.grantAndSetUp(dev)
            assertTrue(lines.joinToString("\n"), lines.any { it.startsWith("Ready:") })
            assertTrue(setup.open())
        }
        println(lines.joinToString("\n"))
    }
}
