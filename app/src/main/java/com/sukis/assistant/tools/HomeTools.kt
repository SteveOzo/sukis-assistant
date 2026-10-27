package com.sukis.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import com.sukis.assistant.net.Http
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class WhatsAppTool(private val env: ToolEnv) : Tool {
    override val name = "send_whatsapp"
    override val description = "Prepara un mensaje de WhatsApp para un contacto (el usuario confirma el envío en pantalla)."
    override val parameters = params(
        listOf("contact", "message"),
        "contact" to pStr("Nombre del contacto o número con código de país"),
        "message" to pStr("Texto del mensaje"),
    )

    override suspend fun run(args: JSONObject): String {
        val contact = args.optString("contact").trim()
        val message = args.optString("message").trim()
        var phone = contact.filter { it.isDigit() }
        var display = contact
        if (phone.length < 7) {
            val found = findContact(contact) ?: return fail("No encontré a '$contact' en los contactos")
            display = found.first
            phone = found.second
        }
        if (phone.startsWith("0")) phone = "593" + phone.drop(1) // número local de Ecuador
        val pm = env.context.packageManager
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(pm, it) }
            ?: return fail("WhatsApp no está instalado en la tablet")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=" + Uri.encode(message))).setPackage(pkg)
        env.startActivity(i)
        return ok("Mensaje para $display listo en WhatsApp; falta tocar enviar")
    }

    private fun findContact(name: String): Pair<String, String>? {
        val ctx = env.context
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val target = norm(name)
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            var best: Pair<String, String>? = null
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = (c.getString(1) ?: "").filter { it.isDigit() }
                val nn = norm(n)
                if (nn == target) return n to num
                if (best == null && (nn.startsWith(target) || nn.contains(target))) best = n to num
            }
            return best
        }
        return null
    }
}

class DeviceStatusTool(private val env: ToolEnv) : Tool {
    override val name = "device_status"
    override val description = "Estado de la tablet: batería y si está cargando."
    override val parameters = params()

    override suspend fun run(args: JSONObject): String {
        val bm = env.context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return JSONObject()
            .put("battery_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging)
            .toString()
    }
}

/** Control de casa inteligente vía Home Assistant (opcional, si el usuario lo configura). */
class HomeAssistantTool(private val env: ToolEnv) : Tool {
    override val name = "smart_home"
    override val description = "Controla dispositivos de la casa conectados a Home Assistant (luces, enchufes, ventiladores…) " +
        "o consulta su estado. Usa list para ver los dispositivos disponibles."
    override val parameters = params(
        listOf("action"),
        "action" to pStr("Acción", listOf("turn_on", "turn_off", "toggle", "status", "list")),
        "device" to pStr("Nombre del dispositivo, ej. 'luz de la sala'"),
        "brightness_pct" to pInt("Brillo 0-100 para luces (opcional)"),
    )

    private fun req(path: String) = Request.Builder().url(env.prefs.haUrl + path)
        .header("Authorization", "Bearer " + env.prefs.haToken)

    override suspend fun run(args: JSONObject): String {
        val states = JSONArray(Http.call(req("/api/states").build()))
        val controllable = setOf("light", "switch", "fan", "cover", "media_player", "climate", "input_boolean", "scene", "script")
        val entities = (0 until states.length()).map { states.getJSONObject(it) }
            .filter { it.getString("entity_id").substringBefore('.') in controllable }
        val action = args.optString("action")
        if (action == "list") {
            return entities.take(60).joinToString("; ") {
                (it.optJSONObject("attributes")?.optString("friendly_name") ?: it.getString("entity_id")) + "=" + it.optString("state")
            }
        }
        val q = norm(args.optString("device"))
        if (q.isEmpty()) return fail("Indica el dispositivo")
        val entity = entities.maxByOrNull { e ->
            val fn = norm(e.optJSONObject("attributes")?.optString("friendly_name") ?: "")
            val id = norm(e.getString("entity_id").replace('_', ' ').replace('.', ' '))
            when {
                fn == q -> 100
                fn.contains(q) || q.contains(fn) && fn.isNotEmpty() -> 70
                id.contains(q) -> 60
                else -> q.split(" ").count { it.length > 2 && (fn.contains(it) || id.contains(it)) } * 10
            }
        }?.takeIf { e ->
            val fn = norm(e.optJSONObject("attributes")?.optString("friendly_name") ?: "")
            q.split(" ").any { it.length > 2 && (fn.contains(it) || e.getString("entity_id").contains(it)) }
        } ?: return fail("No encontré el dispositivo '${args.optString("device")}'")
        val id = entity.getString("entity_id")
        val friendly = entity.optJSONObject("attributes")?.optString("friendly_name") ?: id
        if (action == "status") return ok("$friendly está ${entity.optString("state")}")
        val domain = id.substringBefore('.')
        val service = when {
            domain in setOf("scene", "script") -> "turn_on"
            else -> action
        }
        val body = JSONObject().put("entity_id", id)
        if (domain == "light" && args.has("brightness_pct") && service == "turn_on") body.put("brightness_pct", args.optInt("brightness_pct"))
        val svcDomain = if (domain in setOf("scene", "script", "light", "switch", "fan", "media_player", "input_boolean", "cover", "climate")) domain else "homeassistant"
        val realService = if (svcDomain == "cover") mapOf("turn_on" to "open_cover", "turn_off" to "close_cover", "toggle" to "toggle")[service] ?: service else service
        Http.call(req("/api/services/$svcDomain/$realService").post(Http.jsonBody(body)).build())
        return ok("$friendly: $action")
    }
}
