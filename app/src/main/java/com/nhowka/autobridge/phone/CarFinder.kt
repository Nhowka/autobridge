package com.nhowka.autobridge.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.nhowka.autobridge.Prefs
import com.nhowka.autobridge.car.BridgeService
import com.nhowka.autobridge.net.BridgeServer
import com.nhowka.autobridge.net.LocalForwarder
import com.nhowka.autobridge.net.UpstreamConnector
import com.nhowka.autobridge.net.ifaceConnector
import com.nhowka.autobridge.net.networkConnector
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class ConnectionMode(private val en: String, private val pt: String) {
    CAR_HOTSPOT("Car Wi-Fi", "Wi-Fi do carro"), SAME_WIFI("Same Wi-Fi", "Mesmo Wi-Fi"), MANUAL("Manual address", "Endereço manual");
    val label: String get() = com.nhowka.autobridge.T.s(en, pt)
}

class HttpException(val code: Int, message: String) : IOException(message)

/** Calls to the car's AutoBridge ([BridgeServer]). */
class CarClient(val host: String, val port: Int) {
    val baseUrl: String get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"

    fun request(method: String, path: String, body: String? = null, timeout: Int = 5_000): JSONObject {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = timeout; c.readTimeout = timeout
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code >= 400) throw HttpException(code, json.optString("error", "HTTP $code"))
            return json
        } finally { c.disconnect() }
    }

    fun info(timeout: Int = 2_500) = request("GET", "/api/info", timeout = timeout)
    fun shareOffer(body: JSONObject): JSONObject = request("POST", "/api/share", body.toString())
    fun shareStop(): JSONObject = request("DELETE", "/api/share")
    fun shareStatus(): JSONObject = request("GET", "/api/share")
}

data class CarConnection(val mode: ConnectionMode, val client: CarClient, val network: Network?, val info: JSONObject, val viaInterface: String? = null)

/** The connection the phone screen and the sharing service use. */
object CarSession {
    @Volatile var connection: CarConnection? = null
}

/**
 * Finds the car's AutoBridge, in order: Wi-Fi networks whose gateway is the head unit (through forwarders bound to
 * that network), the Wi-Fi interface directly (wireless Android Auto keeps the car Wi-Fi private: SO_BINDTODEVICE),
 * NSD `_autobridge._tcp`, then a manual address. Same approach as RemoteDVR's CarLocator.
 */
class CarFinder(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val prefs = Prefs(app)

    companion object {
        private val active = ArrayList<LocalForwarder>()
        private fun closeActive() = synchronized(active) { active.forEach { runCatching { it.close() } }; active.clear() }
    }

    private class Spot(val mode: ConnectionMode, val host: String, val port: Int, val network: Network?, val via: String?,
                       val forwarders: List<LocalForwarder> = emptyList())

    /** The car's AutoBridge on the local network, or null. */
    fun locate(): CarConnection? {
        val (spot, info) = search { s -> runCatching { CarClient(s.host, s.port).info() }.getOrNull() } ?: return null
        return CarConnection(spot.mode, CarClient(spot.host, spot.port), spot.network, info, spot.via)
    }

    private fun <T> search(check: (Spot) -> T?): Pair<Spot, T>? {
        closeActive()
        val port = BridgeServer.DEFAULT_PORT
        fun viaForwarder(mode: ConnectionMode, gw: String, connector: UpstreamConnector, via: String, network: Network?): Pair<Spot, T>? {
            val f = runCatching { LocalForwarder(gw, port, connector) }.getOrNull() ?: return null
            val spot = Spot(mode, "127.0.0.1", f.port, network, via, listOf(f))
            val r = check(spot)
            if (r != null) { synchronized(active) { active += f }; return spot to r }
            f.close(); return null
        }
        for ((network, gw, internet) in wifiGateways())
            viaForwarder(if (internet) ConnectionMode.SAME_WIFI else ConnectionMode.CAR_HOTSPOT, gw, networkConnector(network), "wifi → $gw", network)?.let { return it }
        for ((iface, gw) in interfaceGateways())
            viaForwarder(ConnectionMode.CAR_HOTSPOT, gw, ifaceConnector(iface), "$iface → $gw", null)?.let { return it }
        nsdHost()?.let { (h, p) -> Spot(ConnectionMode.SAME_WIFI, h, p, null, null).let { s -> check(s)?.let { return s to it } } }
        prefs.carManualHost.takeIf { it.isNotBlank() }?.let { h ->
            val s = Spot(ConnectionMode.MANUAL, h.substringBefore(':'), h.substringAfter(':', "$port").toIntOrNull() ?: port, null, null)
            check(s)?.let { return s to it }
        }
        return null
    }

    private fun interfaceGateways(): List<Pair<String, String>> {
        @Suppress("DEPRECATION")
        val dhcpGw = app.getSystemService(WifiManager::class.java)?.dhcpInfo?.gateway?.takeIf { it != 0 }?.let { g ->
            "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
        }
        return runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { it.isUp && it.name.startsWith("wlan") }
            .flatMap { nif ->
                val addr = nif.inetAddresses.toList().firstOrNull { it is Inet4Address }?.hostAddress ?: return@flatMap emptyList()
                val prefix = addr.substringBeforeLast('.')
                listOfNotNull(dhcpGw?.takeIf { it.startsWith("$prefix.") }, "$prefix.1").distinct().map { nif.name to it }
            }
    }

    @Suppress("DEPRECATION")
    private fun wifiGateways(): List<Triple<Network, String, Boolean>> = cm.allNetworks.mapNotNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
        val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            ?: lp.dhcpServerAddress?.hostAddress ?: return@mapNotNull null
        Triple(n, gw, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }.sortedBy { it.third }

    private fun nsdHost(): Pair<String, Int>? {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return null
        val found = CountDownLatch(1)
        var result: Pair<String, Int>? = null
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val h = r.host?.hostAddress ?: return
                        if (result == null) { result = h to r.port; found.countDown() }
                    }
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { found.countDown() }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        return try {
            nsd.discoverServices(BridgeService.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            found.await(3, TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            Log.d("AutoBridge", "NSD: ${e.message}"); null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }
}
