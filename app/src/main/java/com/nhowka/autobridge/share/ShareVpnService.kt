package com.nhowka.autobridge.share

import com.nhowka.autobridge.T

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/** JNI of hev-socks5-tunnel (built with PKGNAME=com/nhowka/autobridge/share, CLSNAME=Tun2Socks; see jniLibs/README.md). */
object Tun2Socks {
    init { System.loadLibrary("hev-socks5-tunnel") }
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    /** [txPackets, txBytes, rxPackets, rxBytes] */
    @JvmStatic external fun TProxyGetStats(): LongArray?
}

/**
 * Car side of "share the phone's mobile data": a VPN that sends all of the head unit's internet traffic to the phone's
 * SOCKS5 proxy (SocksServer) over the Wi-Fi link they already share. Local networks (the car hotspot with the DVR and
 * the phone, the QNX side, …) are left out of the VPN, so they keep working directly.
 * Started by the hub when a phone offers sharing (`POST /api/share`), stopped when the offer lapses.
 */
class ShareVpnService : VpnService() {
    companion object {
        private const val TAG = "AutoBridge"
        private const val CHANNEL = "share"
        const val EXTRA_HOST = "host"; const val EXTRA_PORT = "port"; const val EXTRA_USER = "user"; const val EXTRA_PASS = "pass"
        const val ACTION_STOP = "com.nhowka.autobridge.share.STOP"
        @Volatile var running: ShareVpnService? = null
            private set

        /** Public IPv4 space = everything minus private, loopback, link-local, CGNAT and multicast ranges. */
        val ROUTES: List<Pair<String, Int>> = publicRoutes()

        private fun publicRoutes(): List<Pair<String, Int>> {
            val excluded = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
                "192.168.0.0/16", "224.0.0.0/3").map { val (a, p) = it.split('/'); ip(a) to p.toInt() }
            val out = ArrayList<Pair<String, Int>>()
            fun add(base: Long, len: Int) {
                val size = 1L shl (32 - len)
                val end = base + size - 1
                if (excluded.any { (e, el) -> val es = 1L shl (32 - el); base >= e && end < e + es }) return
                if (excluded.none { (e, el) -> val es = 1L shl (32 - el); e < base + size && e + es > base }) {
                    out += "%d.%d.%d.%d".format(base shr 24 and 255, base shr 16 and 255, base shr 8 and 255, base and 255) to len
                    return
                }
                add(base, len + 1); add(base + size / 2, len + 1)
            }
            add(0, 0)
            return out
        }

        private fun ip(s: String): Long = s.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile var phone: String = ""
        private set

    fun stats(): LongArray = runCatching { Tun2Socks.TProxyGetStats() }.getOrNull() ?: LongArray(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSharing(); stopSelf(); return START_NOT_STICKY }
        val host = intent?.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val user = intent.getStringExtra(EXTRA_USER) ?: ""; val pass = intent.getStringExtra(EXTRA_PASS) ?: ""
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, T.s("Internet from phone", "Internet do celular"), NotificationManager.IMPORTANCE_LOW))
        startForeground(3, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(T.s("Internet from your phone", "Internet do seu celular")).setContentText(T.s("Using the phone's mobile data ($host)", "Usando os dados móveis do celular ($host)")).build())
        if (running != null && phone == "$host:$port") return START_NOT_STICKY // same phone: already sharing
        stopSharing()
        try {
            // Android 10+ treats a VPN as metered unless told otherwise → apps with "Wi-Fi only" downloads/streaming
            // refused to use it ("no Wi-Fi"). The head unit has no other internet, so report the VPN as unmetered.
            val b = Builder().setSession("Internet from phone").setMtu(1500).setMetered(false)
                .addAddress("10.255.77.1", 32).addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            ROUTES.forEach { (a, len) -> b.addRoute(a, len) }
            val fd = b.establish() ?: error("VPN not allowed (appops ACTIVATE_VPN)")
            tun = fd
            reportAsOnline()
            val cfg = File(filesDir, "tun2socks.yml")
            cfg.writeText("""
                tunnel:
                  mtu: 1500
                socks5:
                  port: $port
                  address: '$host'
                  udp: 'udp'
                  username: '$user'
                  password: '$pass'
                misc:
                  task-stack-size: 24576
                  log-level: warn
            """.trimIndent())
            if (!Tun2Socks.TProxyStartService(cfg.absolutePath, fd.fd)) error("tun2socks didn't start")
            phone = "$host:$port"
            running = this
            Log.i(TAG, "sharing the phone's internet via $host:$port (${ROUTES.size} routes)")
        } catch (e: Exception) {
            Log.w(TAG, "can't start internet sharing: ${e.message}")
            stopSharing(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopSharing() {
        runCatching { if (Tun2Socks.TProxyIsRunning()) Tun2Socks.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null; phone = ""; running = null
    }

    override fun onRevoke() { stopSharing(); stopSelf() }
    /**
     * The head unit's only internet is this VPN, so Android reports "no active network" to apps that ask the classic
     * way (getActiveNetworkInfo → null) and some (YouTube) go offline without trying. Android 11 answers that question
     * from the VPN's underlying networks: when there is no real one, declare the VPN itself, so the answer is "VPN,
     * connected". When the head unit does have its own network, leave the default (follow the system default).
     */
    private fun reportAsOnline() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        Thread {
            repeat(20) {
                @Suppress("DEPRECATION")
                val nets = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
                val own = nets.firstOrNull { (_, c) -> c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && c.ownerUid == android.os.Process.myUid() }?.first
                val real = nets.any { (_, c) -> !c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                if (own != null) {
                    if (!real && tun != null) runCatching { setUnderlyingNetworks(arrayOf(own)) }
                        .onSuccess { Log.i(TAG, "no other network: VPN reported as the active network") }
                    return@Thread
                }
                Thread.sleep(250)
            }
        }.start()
    }

    override fun onDestroy() { stopSharing(); super.onDestroy() }
}

/** Hub-side bookkeeping: the phone re-offers sharing every ~20 s; without a fresh offer for 60 s the VPN stops. */
class ShareController(private val context: Context) {
    @Volatile private var lastOffer = 0L
    @Volatile var offer: String = ""
        private set

    fun offer(host: String, port: Int, user: String, pass: String) {
        lastOffer = System.currentTimeMillis()
        offer = "$host:$port"
        if (VpnService.prepare(context) != null) { Log.w("AutoBridge", "VPN consent missing (installer grants ACTIVATE_VPN)"); return }
        context.startForegroundService(Intent(context, ShareVpnService::class.java)
            .putExtra(ShareVpnService.EXTRA_HOST, host).putExtra(ShareVpnService.EXTRA_PORT, port)
            .putExtra(ShareVpnService.EXTRA_USER, user).putExtra(ShareVpnService.EXTRA_PASS, pass))
    }

    fun stop() {
        offer = ""
        if (ShareVpnService.running != null) context.startService(Intent(context, ShareVpnService::class.java).setAction(ShareVpnService.ACTION_STOP))
    }

    /** Called every few seconds by the hub. */
    fun tick() { if (offer.isNotEmpty() && System.currentTimeMillis() - lastOffer > 60_000) stop() }

    fun status(): org.json.JSONObject {
        val s = ShareVpnService.running
        val st = s?.stats() ?: LongArray(4)
        return org.json.JSONObject().put("active", s != null).put("phone", s?.phone ?: offer)
            .put("txBytes", st.getOrElse(1) { 0 }).put("rxBytes", st.getOrElse(3) { 0 })
            .put("consent", VpnService.prepare(context) == null)
            .put("carOnline", ownInternet())
    }

    /** Does the head unit have working internet of its own (a validated network that isn't our VPN)? */
    fun ownInternet(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}

/**
 * `POST /api/share` {"port","user","pass"[,"host"]} – a phone offers its mobile data (re-sent every ~20 s);
 * `DELETE /api/share` – it stops; `GET /api/share` – status. Host defaults to the caller's address on the car network.
 */
class ShareRoutes(private val share: ShareController) {
    fun route() = com.nhowka.autobridge.net.Route { req ->
        if (req.path != "/api/share") return@Route null
        when (req.method) {
            "GET" -> com.nhowka.autobridge.net.Response(200, share.status())
            "DELETE" -> { share.stop(); com.nhowka.autobridge.net.Response(200, share.status()) }
            "POST" -> {
                val o = runCatching { org.json.JSONObject(req.bodyText) }.getOrNull()
                    ?: return@Route com.nhowka.autobridge.net.Response(400, org.json.JSONObject().put("error", "JSON body required"))
                val host = o.optString("host").ifEmpty { req.remote }
                val port = o.optInt("port")
                if (host.isEmpty() || port !in 1..65535) return@Route com.nhowka.autobridge.net.Response(400, org.json.JSONObject().put("error", "host/port"))
                share.offer(host, port, o.optString("user"), o.optString("pass"))
                com.nhowka.autobridge.net.Response(200, share.status())
            }
            else -> null
        }
    }
}
