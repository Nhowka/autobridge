package com.nhowka.autobridge.setup

import com.nhowka.autobridge.T

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * "Set up car (USB)": the phone (USB host, OTG cable) installs or updates AutoBridge on the head unit in ADB mode,
 * grants its permissions, imports the 360 camera libraries and opens it – the web installer's job, offline, with this
 * phone's own APK so car and phone run the same version. Shown only while an ADB device is attached.
 */
class SetupCarActivity : AppCompatActivity() {
    companion object { private const val ACTION_PERMISSION = "com.nhowka.autobridge.USB_PERMISSION" }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val buttons = ArrayList<Button>()
    private val permissionResult = LinkedBlockingQueue<Boolean>()
    private var adb: AdbClient? = null
    private var setup: CarSetup? = null
    private var device: CarSetup.Device? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERMISSION -> permissionResult.offer(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { status.text = T.s("USB device disconnected.", "Dispositivo USB desconectado."); disconnect() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { text = T.s("Looking for the car on USB…", "Procurando o carro no USB…"); textSize = 16f }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(row: LinearLayout, label: String, onClick: () -> Unit) =
            Button(this).apply { text = label; isEnabled = false; setOnClickListener { onClick() } }.also { row.addView(it); buttons += it }
        // One button: install/update if needed, then permissions + car role, then open it on the head unit.
        btn(row1, T.s("Set up car", "Configurar carro")) { run { s, d ->
            val installed = s.installedVersion()
            val mine = ownVersion()
            if (installed == null || installed < mine) {
                if (!s.install(ownApk(), mine, helperDex(), d)) { ui(T.s("Install failed – see the log (Copy log / Share log).", "A instalação falhou – veja o log (Copiar log / Compartilhar log).")); return@run }
            } else log(T.s("AutoBridge $installed is already installed – skipping the install.", "O AutoBridge $installed já está instalado – pulando a instalação."))
            s.grantAndSetUp(d)
            s.open()
            ui(T.s("Done. Switch the head unit's USB port back to normal (host) mode; on the phone, tap Share internet.", "Pronto. Volte a porta USB da central para o modo normal (host); no celular, toque em Compartilhar internet."))
        } }
        row2.addView(Button(this).apply { text = T.s("Copy log", "Copiar log"); setOnClickListener { copyLog() } })
        row2.addView(Button(this).apply { text = T.s("Share log", "Compartilhar log"); setOnClickListener { shareLog() } })
        logView = TextView(this).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(status); root.addView(row1); root.addView(row2)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        val filter = IntentFilter(ACTION_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        @Suppress("DEPRECATION")
        val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: UsbAdb.findDevice(this)
        if (dev == null) status.text = T.s("No car found on USB. Switch the head unit's USB port to ADB (device) mode and connect the cable.", "Nenhum carro no USB. Coloque a porta USB da central no modo ADB (dispositivo) e conecte o cabo.")
        else io.execute { connect(dev) }
    }

    private fun log(line: String) = runOnUiThread {
        logView.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun ui(text: String) = runOnUiThread { status.text = text }

    private fun connect(dev: UsbDevice) {
        val usb = getSystemService(UsbManager::class.java)
        if (!usb.hasPermission(dev)) {
            ui(T.s("Allow AutoBridge to access the USB device…", "Permita que o AutoBridge acesse o dispositivo USB…"))
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(dev, pi)
            if (permissionResult.poll(120, TimeUnit.SECONDS) != true) { ui(T.s("USB access not allowed.", "Acesso ao USB não permitido.")); return }
        }
        try {
            ui(T.s("Connecting… if the car asks \"Allow USB debugging?\", tick \"Always allow\" and accept.", "Conectando… se o carro perguntar \"Permitir depuração USB?\", marque \"Sempre permitir\" e aceite."))
            val client = AdbClient(UsbAdbTransport(usb, dev), UsbAdb.keys(this), "AutoBridge phone (${android.os.Build.MODEL})")
            client.connect { log(T.s("Waiting for \"Allow USB debugging\" on the car screen…", "Aguardando \"Permitir depuração USB\" na tela do carro…")) }
            val s = CarSetup(client, ::log)
            val d = s.device()
            adb = client; setup = s; device = d
            log(T.s("Connected: $d", "Conectado: $d"))
            if (d.headUnit) log(T.s("Firmware: ${d.displayId}", "Firmware: ${d.displayId}"))
            val installed = s.installedVersion()
            val mine = ownVersion()
            ui(when {
                !d.headUnit -> T.s("Connected to $d. If this is the car's head unit, tap Set up car.", "Conectado a $d. Se esta é a central do carro, toque em Configurar carro.")
                installed == null -> T.s("Car connected. Tap Set up car to install AutoBridge on it.", "Carro conectado. Toque em Configurar carro para instalar o AutoBridge nele.")
                installed < mine -> T.s("Car connected. The car has an older AutoBridge ($installed, this phone $mine): tap Set up car to update it.", "Carro conectado. O carro tem um AutoBridge mais antigo ($installed, este celular $mine): toque em Configurar carro para atualizar.")
                else -> T.s("Car connected. AutoBridge is up to date (version $installed); Set up car re-applies its settings.", "Carro conectado. O AutoBridge está atualizado (versão $installed); Configurar carro reaplica as configurações.")
            })
            runOnUiThread { buttons.forEach { it.isEnabled = true } }
        } catch (e: Exception) {
            ui(T.s("Couldn't connect: ${e.message}", "Não consegui conectar: ${e.message}"))
            log(T.s("Connection failed: $e", "Falha na conexão: $e"))
        }
    }

    /** Runs one step in the background (buttons disabled meanwhile). */
    private fun run(step: (CarSetup, CarSetup.Device) -> Unit) {
        val s = setup ?: return
        val d = device ?: return
        val go = {
            buttons.forEach { it.isEnabled = false }
            io.execute {
                runCatching { step(s, d) }.onFailure { log(T.s("Failed: ${it.message}", "Falhou: ${it.message}")) }
                runOnUiThread { buttons.forEach { it.isEnabled = true } }
            }
        }
        if (!d.headUnit) AlertDialog.Builder(this).setMessage(T.s("$d isn't a known car head unit (Desay SV). Use it as the car anyway?", "$d não é uma central conhecida (Desay SV). Usar como carro mesmo assim?"))
            .setPositiveButton(T.s("Continue", "Continuar")) { _, _ -> go() }.setNegativeButton(T.s("Cancel", "Cancelar"), null).show()
        else go()
    }

    private fun ownApk(): ByteArray = File(applicationInfo.sourceDir).readBytes()
    private fun ownVersion(): Long = packageManager.getPackageInfo(packageName, 0).longVersionCode
    private fun helperDex(): ByteArray? = runCatching { assets.open("installer.dex").use { it.readBytes() } }.getOrNull()

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AutoBridge set-up log", logView.text))
        Toast.makeText(this, T.s("Log copied", "Log copiado"), Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() = startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, logView.text.toString()), T.s("Share set-up log", "Compartilhar log da configuração")))

    private fun disconnect() { adb?.close(); adb = null; setup = null; runOnUiThread { buttons.forEach { it.isEnabled = false } } }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        io.execute { disconnect() }
        io.shutdown()
        super.onDestroy()
    }
}
