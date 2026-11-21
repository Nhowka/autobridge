package com.nhowka.autobridge.share

import com.nhowka.autobridge.T

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Wireless Android Auto starts with the phone connecting to the car over Bluetooth. With "start automatically" on,
 * that connection starts [PhoneShareService] (a Bluetooth broadcast may start a foreground service from the
 * background). Until a share has succeeded once, any device triggers it; afterwards only the learned car(s) do, so
 * earbuds or a watch don't. When that device disconnects, an automatic session ends.
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    private fun tapToShare(context: Context) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("share-offer", T.s("Car connected", "Carro conectado"), android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(context, 0,
            Intent(context, com.nhowka.autobridge.MainActivity::class.java).putExtra("startShare", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(5, android.app.Notification.Builder(context, "share-offer").setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(T.s("Car connected", "Carro conectado")).setContentText(T.s("Tap to share mobile data with the car", "Toque para compartilhar dados móveis com o carro"))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!PhoneShareService.autoEnabled(context)) return
        @Suppress("DEPRECATION")
        val address = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let { runCatching { it.address }.getOrNull() }
        val learned = PhoneShareService.learnedCars(context)
        if (learned.isNotEmpty() && address != null && address !in learned) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> if (PhoneShareService.current == null) {
                Log.i("AutoBridge", "car Bluetooth connected – starting internet sharing")
                runCatching { PhoneShareService.startAuto(context, address) }.onFailure {
                    // Android refused a background start (battery optimisation still on): offer it as a tap instead.
                    Log.w("AutoBridge", "can't start in the background: ${it.message}")
                    tapToShare(context)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> PhoneShareService.current?.let { s ->
                if (s.autoBt == null || s.autoBt == address) PhoneShareService.stop(context)
            }
        }
    }
}
