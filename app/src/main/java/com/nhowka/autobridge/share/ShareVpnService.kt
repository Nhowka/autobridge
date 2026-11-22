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
        netCallback?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCallback = null; declared = null; decidedFor = null; underlying = "follow default"
        runCatching { if (Tun2Socks.TProxyIsRunning()) Tun2Socks.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null; phone = ""; running = null
    }

    override fun onRevoke() { stopSharing(); stopSelf() }
    /**
     * What apps are told about the connection. Android 11 answers getActiveNetworkInfo() (and "is it metered?", "is it
     * Wi-Fi?") from the VPN's *underlying* networks, never from the VPN itself; with none, apps hear "offline" and the
     * VPN is forced metered. Re-decided whenever the head unit's other networks change (telematics modem, QNX link,
     * Wi-Fi client… come and go):
     *  1. a validated network of its own → null: follow the system default, Android gets it right;
     *  2. otherwise try, in order, its connected networks without internet (unmetered, Wi-Fi/Ethernet first), then the
     *     VPN itself, and keep the first one apps are told is *connected*. Checked, not assumed: on a Jaecoo the
     *     car's own modem (eth0_101, "Ethernet" with a cellular transport) reads CONNECTED by itself, yet declared
     *     as underlying, apps got "Ethernet DISCONNECTED" – the offline YouTube saw. This app sits inside its own VPN,
     *     so what it reads is what YouTube reads. Traffic follows the VPN's routes whatever is declared.
     */
    @Volatile private var underlying: String = "follow default"
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var declared: List<android.net.Network>? = null
    /** The other networks (and whether validated) the last decision was made for: our own declarations change the
     *  VPN's capabilities, which calls back here, and must not start a new round. */
    private var decidedFor: String? = null
    private val decider = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun reportAsOnline() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: android.net.Network) = later(cm)
            override fun onLost(n: android.net.Network) = later(cm)
            override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) = later(cm)
        }
        // A default request only lists non-VPN networks; drop NOT_VPN so our own VPN shows up too.
        val req = android.net.NetworkRequest.Builder().removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { cm.registerNetworkCallback(req, cb); netCallback = cb }
    }

    /** Off the connectivity thread: deciding waits a moment after each declaration. */
    private fun later(cm: android.net.ConnectivityManager) { runCatching { decider.execute { decide(cm) } } }

    @Synchronized private fun decide(cm: android.net.ConnectivityManager) {
        if (tun == null) return
        val VPN = android.net.NetworkCapabilities.TRANSPORT_VPN
        val VALIDATED = android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
        val own = nets.firstOrNull { (_, c) -> c.hasTransport(VPN) && c.ownerUid == android.os.Process.myUid() }?.first ?: return
        val others = nets.filter { (_, c) -> !c.hasTransport(VPN) }
        val key = others.joinToString { (n, c) -> "$n:${c.hasCapability(VALIDATED)}" }
        if (key == decidedFor) return
        if (others.any { (_, c) -> c.hasCapability(VALIDATED) }) {
            if (declare(null)) { underlying = "follow default (head unit has its own internet)"; decidedFor = key }
            return
        }
        @Suppress("DEPRECATION")
        val candidates = others.filter { (n, _) -> cm.getNetworkInfo(n)?.isConnected == true }
            .sortedByDescending { (_, c) ->
                (if (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) 4 else 0) +
                    (if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) 2 else 0)
            }.map { it.first } + own
        val log = ArrayList<String>()
        for (n in candidates) {
            if (!declare(listOf(n))) { log += "$n: refused"; continue }
            Thread.sleep(400)
            @Suppress("DEPRECATION")
            val seen = cm.activeNetworkInfo
            log += "$n: apps see ${seen?.let { "${it.typeName} ${it.detailedState}" }}"
            if (seen?.isConnected == true) {
                underlying = if (n == own) "declared the VPN itself" else "declared $n (a local network without internet)"
                decidedFor = key
                Log.i(TAG, "connection reported to apps: $underlying ($log)")
                return
            }
        }
        // Nothing reads as connected: stay on the VPN itself (the least bad) and try again on the next change.
        declare(listOf(own)); underlying = "nothing reads as connected; declared the VPN itself"; decidedFor = key
        Log.w(TAG, "no declaration reads as connected: $log")
    }

    private fun declare(pick: List<android.net.Network>?): Boolean {
        if (pick == declared) return true
        return runCatching { setUnderlyingNetworks(pick?.toTypedArray()) }.getOrDefault(false).also { if (it) declared = pick }
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
