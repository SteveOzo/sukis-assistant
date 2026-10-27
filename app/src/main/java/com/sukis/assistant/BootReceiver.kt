package com.sukis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sukis.assistant.tools.Reminders
import com.sukis.assistant.voice.AssistantService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        Reminders.rescheduleAll(context, prefs)
        if (!prefs.serviceEnabled) return
        // Desde Android 14 un servicio de micrófono no puede arrancar solo al encender la tablet,
        // así que se intenta y, si no se puede, se muestra una notificación para activarlo con un toque.
        try {
            AssistantService.start(context)
        } catch (e: Exception) {
            SukisApp.notifyReactivate(context)
        }
    }
}
