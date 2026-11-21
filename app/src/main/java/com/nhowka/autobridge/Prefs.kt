package com.nhowka.autobridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Settings for both roles. Role, and the car's address if discovery can't see it. */
class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("autobridge", Context.MODE_PRIVATE)

    /** "auto", "car" or "phone". The phone's USB set-up marks the head unit as "car". */
    var role: String
        get() = p.getString("role", "auto") ?: "auto"
        set(v) = p.edit().putString("role", v).apply()

    /** Phone: car address if discovery can't see it (e.g. "192.168.43.1"). */
    var carManualHost: String
        get() = p.getString("car_host", "") ?: ""
        set(v) = p.edit().putString("car_host", v).apply()

    fun isCar(context: Context): Boolean = when (role) {
        "car" -> true
        "phone" -> false
        else -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            Build.MANUFACTURER.startsWith("DesaySV", true) || Build.FINGERPRINT.startsWith("DesaySV/", true)
    }
}
