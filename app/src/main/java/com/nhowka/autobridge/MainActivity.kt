package com.nhowka.autobridge

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nhowka.autobridge.car.BridgeService
import com.nhowka.autobridge.phone.CarFinder
import com.nhowka.autobridge.phone.CarSession
import com.nhowka.autobridge.setup.SetupCarActivity
import com.nhowka.autobridge.setup.UsbAdb
import com.nhowka.autobridge.share.PhoneShareService
import com.nhowka.autobridge.share.ShareVpnService
import java.util.concurrent.Executors

/**
 * AutoBridge: one app, two roles. On the phone it shares its mobile data with the car over the Wi-Fi link Android
 * Auto already has (no hotspot), optionally starting by itself when the car's Bluetooth connects, and it can install
 * itself on the head unit over USB. On the head unit it takes the phone's internet through a VPN.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        /** Same steps as the README's "Set up the car over USB". */
        private val USB_GUIDE_EN = """
            1. Turn on ADB on the head unit. On Chery / Jaecoo / Omoda (Desay SV) units it's a switch in the engineering / debug menu; its USB data port then works in ADB (device) mode.

            2. Connect the phone to that port so the PHONE is the USB host: a USB-C OTG adapter on the phone plus a USB-A to USB-A cable (or a USB-C cable if the car's port is USB-C). If the phone only starts charging, it isn't the host – use the OTG adapter.

            3. Android asks to open AutoBridge for the USB device: OK (or tap "Set up car (USB)", which appears here while the car is connected). Allow USB access.

            4. Tap "Set up car". It installs AutoBridge on the head unit (also where "pm install" is blocked), allows its VPN, makes it the car side and opens it. If the head unit asks "Allow USB debugging?", tick "Always allow".

            5. Turn ADB off again on the head unit, so USB drives work and nobody else can use ADB.

            6. In the car, with Android Auto connected: tap "Share internet" here (tick "start automatically").

            Updating: update AutoBridge on the phone, then repeat steps 1–5 – "Set up car" updates the car to the phone's version.
        """.trimIndent()
        private val USB_GUIDE_PT = """
            1. Ative o ADB na central multimídia. Nas centrais Chery / Jaecoo / Omoda (Desay SV) é uma chave no menu de engenharia / depuração; a porta USB de dados passa a funcionar no modo ADB (dispositivo).

            2. Conecte o celular nessa porta de modo que o CELULAR seja o host USB: um adaptador OTG USB-C no celular mais um cabo USB-A para USB-A (ou um cabo USB-C se a porta do carro for USB-C). Se o celular só começar a carregar, ele não é o host – use o adaptador OTG.

            3. O Android pergunta se quer abrir o AutoBridge para o dispositivo USB: OK (ou toque em "Configurar carro (USB)", que aparece aqui enquanto o carro está conectado). Permita o acesso ao USB.

            4. Toque em "Configurar carro". Ele instala o AutoBridge na central (também onde o "pm install" é bloqueado), libera a VPN, define a central como o lado do carro e o abre. Se a central perguntar "Permitir depuração USB?", marque "Sempre permitir".

            5. Desative o ADB na central de novo, para os pendrives voltarem a funcionar e ninguém mais usar o ADB.

            6. No carro, com o Android Auto conectado: toque em "Compartilhar internet" aqui (marque "iniciar automaticamente").

            Atualizar: atualize o AutoBridge no celular e repita os passos 1–5 – "Configurar carro" atualiza o carro para a versão do celular.
        """.trimIndent()
        val USB_GUIDE: String get() = T.s(USB_GUIDE_EN, USB_GUIDE_PT)
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var roleText: TextView
    private lateinit var status: TextView
    private lateinit var carBox: LinearLayout
    private lateinit var phoneBox: LinearLayout
    private lateinit var shareBtn: Button
    private lateinit var setupBtn: Button
    private var carStatus = T.s("Looking for the car…", "Procurando o carro…")

    private val tick = object : Runnable { override fun run() { render(); ui.postDelayed(this, 1_000) } }
    private val usbWatch = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt()) }
        roleText = TextView(this)
        val roles = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("auto" to T.s("Auto", "Auto"), "phone" to T.s("Phone", "Celular"), "car" to T.s("Car", "Carro")).forEach { (r, label) ->
            roles.addView(RadioButton(this).apply { text = label; tag = r; id = View.generateViewId(); isChecked = prefs.role == r })
        }
        roles.setOnCheckedChangeListener { g, id -> prefs.role = g.findViewById<View>(id).tag as String; applyRole() }
        status = TextView(this).apply { textSize = 17f; setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt()) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }

        // Car role: status only.
        carBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Phone role
        phoneBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shareBtn = button(T.s("Share internet", "Compartilhar internet")) { toggleShare() }
        setupBtn = button(T.s("Set up car (USB)", "Configurar carro (USB)")) { startActivity(Intent(this, SetupCarActivity::class.java)) }
        phoneBox.addView(shareBtn)
        phoneBox.addView(button(T.s("Find car", "Procurar carro")) { findCar() })
        phoneBox.addView(setupBtn)
        // USB set-up tutorial (collapsed by default).
        val guide = TextView(this).apply { text = USB_GUIDE; visibility = View.GONE; setPadding(0, (8 * d).toInt(), 0, 0); setTextIsSelectable(true) }
        phoneBox.addView(button(T.s("How to set up the car (USB) ▸", "Como configurar o carro (USB) ▸")) {
            guide.visibility = if (guide.visibility == View.GONE) View.VISIBLE else View.GONE
        })
        phoneBox.addView(guide)

        root.addView(roleText); root.addView(roles); root.addView(status); root.addView(carBox); root.addView(phoneBox)
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (intent.getBooleanExtra("startShare", false)) { PhoneShareService.setEnabled(this, true); PhoneShareService.start(this) }
        applyRole()
    }

    private fun applyRole() {
        val car = prefs.isCar(this)
        carBox.visibility = if (car) View.VISIBLE else View.GONE
        phoneBox.visibility = if (car) View.GONE else View.VISIBLE
        if (car) BridgeService.start(this) else findCar()
        render()
    }

    private fun render() {
        val car = prefs.isCar(this)
        roleText.text = T.s("Role: ", "Papel: ") + (if (car) T.s("car (head unit)", "carro (central multimídia)") else T.s("phone", "celular")) +
            if (prefs.role == "auto") T.s(" – detected", " – detectado") else ""
        if (car) {
            val s = BridgeService.current
            val v = ShareVpnService.running
            status.text = when {
                s == null -> T.s("Starting…", "Iniciando…")
                s.error != null -> T.s("Error: ", "Erro: ") + s.error
                v != null -> { val st = v.stats(); T.s("Internet: from your phone (%.1f MB)", "Internet: do seu celular (%.1f MB)").format((st.getOrElse(1) { 0 } + st.getOrElse(3) { 0 }) / 1e6) }
                s.share.ownInternet() -> T.s("This head unit has its own internet.", "Esta central já tem internet própria.")
                else -> T.s("Waiting for a phone to share its internet.", "Aguardando um celular compartilhar a internet.")
            }
        } else {
            val share = PhoneShareService.current
            status.text = carStatus + (share?.let { T.s("\nSharing: ${it.state} · car used %.1f MB", "\nCompartilhando: ${it.state} · carro usou %.1f MB").format((it.bytesUp + it.bytesDown) / 1e6) } ?: "")
            shareBtn.text = if (PhoneShareService.enabled(this)) T.s("Stop sharing internet", "Parar de compartilhar") else T.s("Share internet", "Compartilhar internet")
            setupBtn.visibility = if (UsbAdb.findDevice(this) != null) View.VISIBLE else View.GONE
        }
    }

    private fun findCar() {
        carStatus = T.s("Looking for the car…", "Procurando o carro…")
        io.execute {
            val r = runCatching { CarFinder(this).locate() }
            carStatus = r.fold({ c ->
                CarSession.connection = c
                if (c == null) T.s("Car not found (connect Android Auto, or join the car's Wi-Fi).", "Carro não encontrado (conecte o Android Auto ou entre no Wi-Fi do carro).")
                else {
                    if (PhoneShareService.enabled(this) && PhoneShareService.current == null) runOnUiThread { PhoneShareService.start(this) }
                    T.s("Car connected (${c.mode.label}).", "Carro conectado (${c.mode.label}).")
                }
            }, { e -> T.s("Error: ", "Erro: ") + e.message })
        }
    }

    private fun toggleShare() {
        if (PhoneShareService.enabled(this)) {
            PhoneShareService.setEnabled(this, false); PhoneShareService.setAuto(this, false); PhoneShareService.stop(this); render(); return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(TextView(this).apply { text = T.s("While connected to the car, the head unit uses this phone's mobile data (its apps too). " +
            "No hotspot needed. The notification shows how much the car used.",
            "Enquanto conectado ao carro, a central usa os dados móveis deste celular (os apps dela também). " +
            "Sem roteador. A notificação mostra quanto o carro usou.") })
        val auto = CheckBox(this).apply { text = T.s("Start automatically when the car connects (Bluetooth – wireless Android Auto)", "Iniciar automaticamente quando o carro conectar (Bluetooth – Android Auto sem fio)"); isChecked = true }
        box.addView(auto)
        AlertDialog.Builder(this).setTitle(T.s("Share mobile data with the car?", "Compartilhar dados móveis com o carro?")).setView(box).setPositiveButton(T.s("Share", "Compartilhar")) { _, _ ->
            PhoneShareService.setEnabled(this, true)
            PhoneShareService.setAuto(this, auto.isChecked)
            if (auto.isChecked) {
                val pm = getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(packageName)) runCatching {
                    @Suppress("BatteryLife")
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
                if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 3)
            }
            PhoneShareService.start(this)
            render()
        }.setNegativeButton(T.s("Cancel", "Cancelar"), null).show()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
        ContextCompat.registerReceiver(this, usbWatch, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() { ui.removeCallbacks(tick); runCatching { unregisterReceiver(usbWatch) }; super.onPause() }
}
