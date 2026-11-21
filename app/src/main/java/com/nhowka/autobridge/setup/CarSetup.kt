package com.nhowka.autobridge.setup

import com.nhowka.autobridge.T

import java.io.IOException

/**
 * Installs and configures AutoBridge on the head unit over ADB – the same steps as the web installer
 * (setup-pwa/src/main.js), learned on the real car:
 * - the head unit's adbd closes any command line containing "install" → such commands run from a pushed script;
 * - `pm install` is refused ("Restriction prevents installing", also on SOP4) → our PackageInstaller-session helper
 *   (`rdvr-installer.dex` via app_process) goes first on Desay SV head units, then `pm install`, then a pm session;
 * - permissions newer than the device's Android are skipped; vendor camera libraries are copied only if missing.
 */
class CarSetup(private val adb: AdbClient, private val log: (String) -> Unit) {
    companion object {
        const val PKG = "com.nhowka.autobridge"
        private const val TMP = "/data/local/tmp"
        private val NEEDS_SCRIPT = Regex("install", RegexOption.IGNORE_CASE)
        private val MIN_SDK = mapOf("POST_NOTIFICATIONS" to 33, "ACCESS_BACKGROUND_LOCATION" to 29)

    }

    data class Device(val model: String, val release: String, val sdk: Int, val fingerprint: String, val displayId: String) {
        val headUnit: Boolean get() = fingerprint.contains("DesaySV", ignoreCase = true)
        val variant: String? get() = Regex("SOP\\d+", RegexOption.IGNORE_CASE).find(displayId)?.value?.uppercase()
        override fun toString() = "$model · Android $release (API $sdk)" + if (headUnit) " · Desay SV head unit ${variant ?: ""}" else ""
    }

    private var scriptNo = 0

    /** Shell command; ones containing "install" run from a pushed script (the head unit's adbd drops them otherwise). */
    fun sh(cmd: String, quiet: Boolean = false): String {
        if (!quiet) log("$ $cmd")
        val out = if (NEEDS_SCRIPT.containsMatchIn(cmd)) {
            val path = "$TMP/rdvr_cmd_${System.currentTimeMillis()}_${scriptNo++}.sh"
            adb.push(path, "$cmd\nrc=\$?\nrm -f \"\$0\"\nexit \$rc\n".toByteArray())
            adb.shell("sh $path")
        } else adb.shell(cmd)
        val t = out.trim()
        if (!quiet && t.isNotEmpty()) log(t)
        return t
    }

    fun device(): Device {
        fun p(name: String) = sh("getprop $name", quiet = true)
        return Device(p("ro.product.model"), p("ro.build.version.release"), p("ro.build.version.sdk").toIntOrNull() ?: 0,
            p("ro.build.fingerprint"), p("ro.build.display.id"))
    }

    /** Installed versionCode of AutoBridge on the device, or null. */
    fun installedVersion(): Long? {
        if (!sh("pm path $PKG", quiet = true).startsWith("package:")) return null
        return Regex("versionCode=(\\d+)").find(sh("dumpsys package $PKG | grep -m 1 versionCode=", quiet = true))?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Installs [apk] (this phone's own AutoBridge, so both run the same version). Returns true when the device reports
     * [versionCode] afterwards. On failure the APK is left in /sdcard/Download for a manual install.
     */
    fun install(apk: ByteArray, versionCode: Long, helperDex: ByteArray?, device: Device): Boolean {
        log(T.s("Copying AutoBridge (${apk.size / 1_048_576} MB) to the device…", "Copiando o AutoBridge (${apk.size / 1_048_576} MB) para o aparelho…"))
        val remote = "$TMP/rdvr_pkg_${System.currentTimeMillis()}.apk"
        var lastPct = -1
        adb.push(remote, apk) { sent -> val pct = (sent * 100 / apk.size).toInt(); if (pct / 20 != lastPct / 20) { lastPct = pct; log("  $pct%") } }
        val dex = "$TMP/rdvr_installer.dex"
        val haveDex = helperDex != null && runCatching { adb.push(dex, helperDex) }.isSuccess
        val helper = "session API (app_process)" to "CLASSPATH=$dex app_process /system/bin rdvr.Installer $remote 2>&1"
        val pm = "pm install" to "pm install -r -d -g $remote 2>&1"
        val session = "install session" to ("s=\$(pm install-create -r -g -S ${apk.size} 2>&1); echo \"\$s\"; id=\$(echo \"\$s\" | grep -o '\\[[0-9]*\\]' | tr -d '[]'); " +
            "[ -n \"\$id\" ] && pm install-write -S ${apk.size} \$id base.apk $remote 2>&1 && pm install-commit \$id 2>&1")
        val methods = when {
            !haveDex -> listOf(pm, session)
            device.headUnit -> listOf(helper, pm, session) // Desay SV: pm install is restricted
            else -> listOf(pm, helper, session)
        }
        var ok = false
        for ((label, cmd) in methods) {
            log("— $label")
            val out = runCatching { sh(cmd) }.getOrElse { "error: ${it.message}" }
            if (Regex("Success|RDVR_INSTALLED").containsMatchIn(out) && installedVersion() == versionCode) { ok = true; break }
            if (Regex("UPDATE_INCOMPATIBLE|signatures do not match", RegexOption.IGNORE_CASE).containsMatchIn(out)) {
                log(T.s("A AutoBridge signed with another key is installed – uninstall it on the car first.", "Há um AutoBridge assinado com outra chave instalado – desinstale-o no carro primeiro.")); break
            }
        }
        if (!ok) {
            log(T.s("Install failed. Device log:", "A instalação falhou. Log do aparelho:"))
            runCatching { sh("logcat -d -b all -t 600 2>/dev/null | grep -i -E \"Restriction|PackageManager|PackageInstaller|app_process|INSTALL_\" | tail -n 25") }
            runCatching { adb.push("/sdcard/Download/AutoBridge.apk", apk); log(T.s("Copied to /sdcard/Download/AutoBridge.apk for a manual install.", "Copiado para /sdcard/Download/AutoBridge.apk para instalar manualmente.")) }
        }
        sh("rm -f $remote $dex", quiet = true)
        if (ok) log(T.s("Installed AutoBridge (version $versionCode).", "AutoBridge instalado (versão $versionCode)."))
        return ok
    }

    /**
     * Marks the device as the car, keeps it out of battery optimisation and pre-approves its VPN (so the head unit
     * never shows the VPN consent dialog).
     */
    fun grantAndSetUp(device: Device) {
        if (installedVersion() == null) throw IOException(T.s("AutoBridge isn't installed on the device yet", "O AutoBridge ainda não está instalado no aparelho"))
        if (device.sdk >= 33) runCatching { sh("pm grant $PKG android.permission.POST_NOTIFICATIONS") }
        log(T.s("— Exclude from battery optimization", "— Excluir da otimização de bateria")); runCatching { sh("dumpsys deviceidle whitelist +$PKG") }
        log(T.s("— Allow its VPN without the consent dialog", "— Permitir a VPN sem o aviso de consentimento")); runCatching { sh("appops set $PKG ACTIVATE_VPN allow") }
        log(T.s("— Car role", "— Papel de carro"))
        val r = sh("am broadcast -f 0x20 -a com.nhowka.autobridge.SETUP -p $PKG", quiet = true) // 0x20: include a just-installed (stopped) app
        log(if (r.contains("data=\"car\"")) T.s("Ready: the head unit takes internet from a phone sharing it.", "Pronto: a central multimídia usa a internet do celular que compartilhar.") else T.s("Car role step: $r", "Etapa de papel do carro: $r"))
    }

    /** Starts AutoBridge's launcher activity (am start; `monkey` doesn't work everywhere). */
    fun open(): Boolean {
        val comp = sh("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PKG | tail -n 1", quiet = true)
        val ok = comp.contains('/') && !sh("am start -n $comp 2>&1", quiet = true).contains("Error")
        log(if (ok) T.s("Opened AutoBridge on the device.", "AutoBridge aberto no aparelho.") else T.s("Couldn't open AutoBridge ($comp).", "Não consegui abrir o AutoBridge ($comp)."))
        return ok
    }
}
