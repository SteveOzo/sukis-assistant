package com.sukis.assistant

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sukis.assistant.ui.MainActivity

class SukisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Sukis escuchando", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Recordatorios y avisos", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIF_SERVICE = 1
        const val NOTIF_REACTIVATE = 2

        /** Android no deja encender el micrófono en segundo plano: se pide al usuario que toque. */
        fun notifyReactivate(context: Context) {
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOSTART, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sukis está en pausa")
                .setContentText("Toca para volver a activar \"Oye Sukis\"")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)!!.notify(NOTIF_REACTIVATE, n)
        }
    }
}
