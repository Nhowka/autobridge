package com.nhowka.autobridge.car

import com.nhowka.autobridge.T

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import com.nhowka.autobridge.MainActivity
import com.nhowka.autobridge.Prefs
import com.nhowka.autobridge.net.BridgeServer
import com.nhowka.autobridge.net.Response
import com.nhowka.autobridge.net.Route
import com.nhowka.autobridge.share.ShareController
import com.nhowka.autobridge.share.ShareRoutes
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Car role: the small control API on the car Wi-Fi (port 8091, announced as `_autobridge._tcp`) and the VPN that takes the head unit's internet from a phone that offers it.
 */
class BridgeService : Service() {
    companion object {
        const val NSD_TYPE = "_autobridge._tcp"
        private const val TAG = "AutoBridge"
        @Volatile var current: BridgeService? = null
            private set
        fun start(c: Context) = c.startForegroundService(Intent(c, BridgeService::class.java))
    }

    lateinit var share: ShareController
        private set
    private var server: BridgeServer? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile var error: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bridge", "AutoBridge", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "bridge").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("AutoBridge").setContentText(T.s("Ready to use a phone's internet", "Pronto para usar a internet de um celular")).setContentIntent(open).build())
        share = ShareController(this)
        val info = Route { req ->
            if (req.path == "/api/info" && req.method == "GET")
                Response(200, JSONObject().put("app", "AutoBridge").put("role", "car")
                    .put("version", runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L))
                    .put("share", share.status()))
            else null
        }
        try {
            server = BridgeServer(listOf(info, ShareRoutes(share).route()))
        } catch (e: Exception) {
            error = "port ${BridgeServer.DEFAULT_PORT} in use? ${e.message}"; Log.e(TAG, "server", e)
        }
        registerNsd()
        timer.scheduleWithFixedDelay({ runCatching { share.tick() } }, 10, 10, TimeUnit.SECONDS)
        current = this
    }

    private fun registerNsd() {
        val nsd = getSystemService(NsdManager::class.java) ?: return
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD failed $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching {
            nsd.registerService(NsdServiceInfo().apply { serviceName = "AutoBridge"; serviceType = NSD_TYPE; port = BridgeServer.DEFAULT_PORT },
                NsdManager.PROTOCOL_DNS_SD, l)
            nsdListener = l
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        current = null
        timer.shutdownNow()
        runCatching { share.stop() }
        nsdListener?.let { l -> runCatching { getSystemService(NsdManager::class.java)?.unregisterService(l) } }
        runCatching { server?.close() }
        super.onDestroy()
    }
}

/** Starts the car role after boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && Prefs(c).isCar(c)) runCatching { BridgeService.start(c) }
    }
}

/**
 * From the phone's USB set-up (adb shell holds DUMP, other apps can't): `am broadcast -a com.nhowka.autobridge.SETUP
 * -p com.nhowka.autobridge` → this device is the car; service started.
 */
class SetupReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs(c).role = "car"
        runCatching { BridgeService.start(c) }
        resultData = "car"
    }
}
