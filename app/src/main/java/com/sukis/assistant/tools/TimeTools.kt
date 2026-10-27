package com.sukis.assistant.tools

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.app.Notification
import com.sukis.assistant.Prefs
import com.sukis.assistant.R
import com.sukis.assistant.SukisApp
import com.sukis.assistant.voice.AssistantService
import com.sukis.assistant.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimerTool(private val env: ToolEnv) : Tool {
    override val name = "set_timer"
    override val description = "Pone un temporizador (cuenta regresiva) en el reloj de la tablet."
    override val parameters = params(
        listOf("seconds"),
        "seconds" to pInt("Duración total en segundos"),
        "label" to pStr("Etiqueta opcional, ej. 'arroz'"),
    )

    override suspend fun run(args: JSONObject): String {
        val secs = args.optInt("seconds")
        if (secs <= 0) return fail("Duración inválida")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optStringOrNull("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        env.startActivity(i)
        return ok("Temporizador de $secs segundos")
    }
}

class AlarmTool(private val env: ToolEnv) : Tool {
    override val name = "set_alarm"
    override val description = "Crea una alarma en el reloj de la tablet a una hora dada (formato 24 h)."
    override val parameters = params(
        listOf("hour", "minute"),
        "hour" to pInt("Hora 0-23"),
        "minute" to pInt("Minuto 0-59"),
        "label" to pStr("Etiqueta opcional"),
        "weekdays" to pIntArray("Días que se repite: 1=domingo, 2=lunes … 7=sábado. Vacío = una vez"),
    )

    override suspend fun run(args: JSONObject): String {
        val h = args.optInt("hour", -1)
        val m = args.optInt("minute", 0)
        if (h !in 0..23 || m !in 0..59) return fail("Hora inválida")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optStringOrNull("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        args.optJSONArray("weekdays")?.let { arr ->
            val days = ArrayList<Int>()
            for (k in 0 until arr.length()) arr.optInt(k).takeIf { it in 1..7 }?.let(days::add)
            if (days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, days)
        }
        env.startActivity(i)
        return ok("Alarma a las %02d:%02d".format(h, m))
    }
}

/** Recordatorios propios: Sukis los dice en voz alta y muestra una notificación. */
object Reminders {
    private const val KEY = "reminders"

    fun all(prefs: Prefs): JSONArray = JSONArray(prefs.sp.getString(KEY, "[]"))
    private fun save(prefs: Prefs, arr: JSONArray) = prefs.sp.edit().putString(KEY, arr.toString()).apply()

    fun add(context: Context, prefs: Prefs, text: String, at: Long): Int {
        val arr = all(prefs)
        val id = (System.currentTimeMillis() / 1000 % 1_000_000).toInt()
        arr.put(JSONObject().put("id", id).put("text", text).put("at", at))
        save(prefs, arr)
        schedule(context, id, text, at)
        return id
    }

    fun remove(context: Context, prefs: Prefs, id: Int): Boolean {
        val arr = all(prefs)
        val out = JSONArray()
        var found = false
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optInt("id") == id) found = true else out.put(r)
        }
        save(prefs, out)
        val am = context.getSystemService(AlarmManager::class.java)!!
        am.cancel(pending(context, id, ""))
        return found
    }

    fun rescheduleAll(context: Context, prefs: Prefs) {
        val arr = all(prefs)
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            schedule(context, r.optInt("id"), r.optString("text"), maxOf(r.optLong("at"), now + 5000))
        }
    }

    private fun pending(context: Context, id: Int, text: String): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text)
        return PendingIntent.getBroadcast(context, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(context: Context, id: Int, text: String, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)!!
        val pi = pending(context, id, text)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val id = intent.getIntExtra("id", 0)
        val prefs = Prefs(context)
        Reminders.remove(context, prefs, id)
        val n = Notification.Builder(context, SukisApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recordatorio")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)!!.notify(id, n)
        AssistantService.instance?.announce("Te recuerdo: $text")
    }
}

class ReminderTool(private val env: ToolEnv) : Tool {
    override val name = "reminders"
    override val description = "Crea, lista o borra recordatorios hablados. Para crear usa in_minutes o datetime."
    override val parameters = params(
        listOf("action"),
        "action" to pStr("Acción", listOf("create", "list", "delete")),
        "text" to pStr("Qué recordar (para create)"),
        "in_minutes" to pInt("Dentro de cuántos minutos (para create)"),
        "datetime" to pStr("Fecha y hora local ISO, ej. 2026-10-27T18:30 (para create)"),
        "id" to pInt("Id del recordatorio (para delete)"),
    )

    override suspend fun run(args: JSONObject): String {
        val fmt = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'a las' HH:mm", Locale.forLanguageTag("es-EC"))
        val zone = ZoneId.systemDefault()
        return when (args.optString("action")) {
            "create" -> {
                val text = args.optStringOrNull("text") ?: return fail("Falta el texto")
                val at = when {
                    args.optInt("in_minutes", 0) > 0 -> System.currentTimeMillis() + args.optInt("in_minutes") * 60_000L
                    args.optStringOrNull("datetime") != null -> try {
                        LocalDateTime.parse(args.getString("datetime").take(16)).atZone(zone).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        return fail("Fecha inválida, usa formato 2026-10-27T18:30")
                    }
                    else -> return fail("Indica in_minutes o datetime")
                }
                if (at < System.currentTimeMillis()) return fail("Esa hora ya pasó")
                val id = Reminders.add(env.context, env.prefs, text, at)
                ok("Recordatorio $id creado para el " + fmt.format(Instant.ofEpochMilli(at).atZone(zone)))
            }
            "list" -> {
                val arr = Reminders.all(env.prefs)
                if (arr.length() == 0) return ok("No hay recordatorios pendientes")
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    out.put(
                        JSONObject().put("id", r.optInt("id")).put("text", r.optString("text"))
                            .put("when", fmt.format(Instant.ofEpochMilli(r.optLong("at")).atZone(zone))),
                    )
                }
                out.toString()
            }
            "delete" -> if (Reminders.remove(env.context, env.prefs, args.optInt("id"))) ok("Borrado") else fail("No existe ese recordatorio")
            else -> fail("Acción desconocida")
        }
    }
}
