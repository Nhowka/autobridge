package com.nhowka.autobridge.share

import com.nhowka.autobridge.T

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import com.nhowka.autobridge.phone.CarSession
import com.nhowka.autobridge.phone.ConnectionMode
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Phone side of "share mobile data with the car" (switch on the phone screen, off by default): a SOCKS5 proxy on the
 * car network, outgoing traffic on the phone's **mobile data**, offered to the car every 20 s while connected. No
 * phone hotspot needed – it rides on the Wi-Fi link the phone already has with the car (also with Android Auto).
 */
class PhoneShareService : Service() {
    companion object {
        private const val TAG = "AutoBridge"
        private const val CHANNEL = "share-phone"
        const val ACTION_STOP = "com.nhowka.autobridge.share.PHONE_STOP"
        @Volatile var current: PhoneShareService? = null
            private set
        fun start(c: Context) = c.startForegroundService(Intent(c, PhoneShareService::class.java))
        /** Started by the car's Bluetooth connecting ([CarBluetoothReceiver]); gives up after 5 min without the car. */
        fun startAuto(c: Context, btDevice: String?) = c.startForegroundService(Intent(c, PhoneShareService::class.java)
            .putExtra("auto", true).putExtra("bt", btDevice))
        fun autoEnabled(c: Context) = prefs(c).getBoolean("auto", false)
        fun setAuto(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto", v).apply()
        /** Bluetooth devices (car head units) that started a successful share – only these trigger it afterwards. */
        fun learnedCars(c: Context): Set<String> = prefs(c).getStringSet("auto_devices", emptySet()) ?: emptySet()
        fun stop(c: Context) = c.startService(Intent(c, PhoneShareService::class.java).setAction(ACTION_STOP))
        private fun prefs(c: Context) = c.getSharedPreferences("share", Context.MODE_PRIVATE)
        fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
        fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()
    }

    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var server: SocksServer? = null
    private val user = "car"
    private val pass by lazy {
        getSharedPreferences("share", MODE_PRIVATE).getString("test_pass", null) // emulator tests only
            ?: ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
    @Volatile private var cellular: Network? = null
    private var cellCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile var state: String = T.s("starting", "iniciando")
        private set
    val bytesUp: Long get() = server?.bytesUp?.get() ?: 0
    val bytesDown: Long get() = server?.bytesDown?.get() ?: 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, T.s("Sharing internet with the car", "Compartilhando internet com o carro"), NotificationManager.IMPORTANCE_LOW))
        startForeground(4, notification())
        // Keep a handle on the mobile-data network: the car's Wi-Fi has no internet.
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cellular = network }
            override fun onLost(network: Network) { if (cellular == network) cellular = null }
        }
        cellCallback = cb
        runCatching {
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        }.onFailure { Log.w(TAG, "no mobile-data request (${it.message}); using the default network") }
        timer.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { state = "error: ${it.message}"; Log.w(TAG, "share", it) } }, 0, 20, TimeUnit.SECONDS)
    }

    /** Set when started by the car's Bluetooth: stop again if the car doesn't show up, remember it when it does. */
    @Volatile var autoBt: String? = null
        private set
    @Volatile private var auto = false
    private val startedAt = System.currentTimeMillis()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!auto) setEnabled(this, false) // the user's Stop; an automatic session just ends
            stopSelf()
        } else if (intent?.getBooleanExtra("auto", false) == true) {
            auto = true; autoBt = intent.getStringExtra("bt")
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, PhoneShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(T.s("Sharing mobile data with the car", "Compartilhando dados móveis com o carro"))
            .setContentText(T.s("$state · car used %.1f MB", "$state · carro usou %.1f MB").format((bytesUp + bytesDown) / 1e6))
            .addAction(Notification.Action.Builder(null, T.s("Stop", "Parar"), stop).build()).build()
    }

    /**
     * Our address on the network we share with the car, or null when sharing makes no sense (not connected, remote
     * VPN path, or that Wi-Fi already has verified internet of its own). [state] says why.
     */
    private fun carSideAddress(): InetAddress? {
        val conn = CarSession.connection ?: run { state = T.s("car not connected", "carro não conectado"); return null }
        val cm = getSystemService(ConnectivityManager::class.java)
        // Ask the car whether it is online by itself (the phone's own view of "this Wi-Fi has internet" is unreliable:
        // with Android Auto the phone can sit on two Wi-Fi networks). Older car versions don't say → share.
        val st = runCatching { conn.client.shareStatus() }.getOrNull()
        if (st != null && st.optBoolean("carOnline") && !st.optBoolean("active")) {
            state = T.s("the car already has its own internet – not sharing", "o carro já tem internet própria – sem compartilhar"); return null
        }
        conn.network?.let { n ->
            cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.let { return it }
        }
        conn.viaInterface?.let { iface -> // Android Auto: the Wi-Fi isn't visible as a Network to apps
            NetworkInterface.getByName(iface)?.inetAddresses?.toList()?.firstOrNull { it is Inet4Address }?.let { return it }
        }
        // Direct address (found via NSD or entered manually): the local address the system uses to reach the car.
        val host = conn.client.host
        return runCatching {
            DatagramSocket().use { d -> d.connect(InetAddress.getByName(host), 8080); d.localAddress.takeIf { !it.isAnyLocalAddress && !it.isLoopbackAddress } }
        }.getOrNull() ?: run { state = T.s("can't find this phone's address on the car network", "não achei o endereço deste celular na rede do carro"); null }
    }

    private val outbound = object : SocksServer.Outbound {
        private fun net(): Network? = cellular
        override fun connect(host: String, port: Int): Socket {
            val n = net()
            val s = n?.socketFactory?.createSocket() ?: Socket()
            s.connect(InetSocketAddress(resolve(host), port), 15_000)
            return s
        }
        override fun datagram(): DatagramSocket = DatagramSocket().also { d -> net()?.bindSocket(d) }
        override fun resolve(host: String): InetAddress = net()?.getByName(host) ?: InetAddress.getByName(host)
    }

    private fun tick() {
        // Background start: find the car ourselves (Android Auto brings its Wi-Fi up shortly after Bluetooth).
        if (CarSession.connection == null) {
            runCatching { com.nhowka.autobridge.phone.CarFinder(this).locate() }.getOrNull()?.let { CarSession.connection = it }
            if (CarSession.connection == null) {
                state = T.s("looking for the car…", "procurando o carro…")
                if (auto && System.currentTimeMillis() - startedAt > 5 * 60_000) { Log.i(TAG, "car not found – stopping"); stopSelf(); return }
                refreshNotification(); return
            }
        }
        val override = getSharedPreferences("share", MODE_PRIVATE).getString("test_override", null) // emulator tests: "host:port"
        val bind = if (override != null) InetAddress.getByName("0.0.0.0") else carSideAddress()
        if (bind == null) { refreshNotification(); return } // carSideAddress() set the reason
        if (server == null) {
            val port = getSharedPreferences("share", MODE_PRIVATE).getInt("test_port", 0)
            server = SocksServer(bind, user, pass, outbound, port)
            Log.i(TAG, "proxy on ${bind.hostAddress}:${server!!.port}")
        }
        val client = CarSession.connection?.client ?: run { state = T.s("car not connected", "carro não conectado"); refreshNotification(); return }
        val body = JSONObject().put("port", override?.substringAfter(':')?.toInt() ?: server!!.port).put("user", user).put("pass", pass)
        if (override != null) body.put("host", override.substringBefore(':')) else body.put("host", bind.hostAddress)
        val reply = client.shareOffer(body)
        if (reply.optBoolean("active") && auto) autoBt?.let { bt -> // learn this car's Bluetooth
            val p = getSharedPreferences("share", MODE_PRIVATE)
            p.edit().putStringSet("auto_devices", (p.getStringSet("auto_devices", emptySet()) ?: emptySet()) + bt).apply()
        }
        state = if (reply.optBoolean("active")) T.s("car online through this phone", "carro online por este celular") + (if (cellular == null) T.s(" (no mobile data!)", " (sem dados móveis!)") else "")
            else if (!reply.optBoolean("consent", true)) T.s("car needs the VPN permission (reinstall from the installer)", "o carro precisa da permissão de VPN (refaça \"Configurar carro\")") else T.s("car is starting…", "carro iniciando…")
        refreshNotification()
    }

    private fun refreshNotification() = getSystemService(NotificationManager::class.java).notify(4, notification())

    override fun onDestroy() {
        timer.shutdownNow()
        Thread { runCatching { CarSession.connection?.client?.shareStop() } }.start()
        runCatching { server?.close() }
        cellCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        current = null
        super.onDestroy()
    }
}
