package com.sukis.assistant.tools

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.sukis.assistant.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** Una acción que el modelo puede ejecutar ("function calling"). */
interface Tool {
    val name: String
    val description: String
    val parameters: JSONObject
    /** Devuelve un texto (o JSON) con el resultado, que se le pasa al modelo. */
    suspend fun run(args: JSONObject): String

    fun spec(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject().put("name", name).put("description", description).put("parameters", parameters),
        )
}

class ToolEnv(val context: Context, val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())

    /** Abre una actividad desde el servicio (requiere el permiso "mostrar sobre otras apps"). */
    fun startActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post { context.startActivity(intent) }
    }
}

// ---- Pequeño DSL para escribir los JSON Schema de los parámetros ----

fun params(required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>): JSONObject {
    val p = JSONObject()
    props.forEach { (k, v) -> p.put(k, v) }
    return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
}

fun pStr(desc: String, enum: List<String>? = null): JSONObject {
    val o = JSONObject().put("type", "string").put("description", desc)
    if (enum != null) o.put("enum", JSONArray(enum))
    return o
}

fun pInt(desc: String) = JSONObject().put("type", "integer").put("description", desc)
fun pBool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
fun pStrArray(desc: String) = JSONObject().put("type", "array").put("description", desc)
    .put("items", JSONObject().put("type", "string"))
fun pIntArray(desc: String) = JSONObject().put("type", "array").put("description", desc)
    .put("items", JSONObject().put("type", "integer"))

fun ok(msg: String) = JSONObject().put("ok", true).put("result", msg).toString()
fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

/** Normaliza texto para comparaciones: minúsculas, sin tildes ni signos. */
fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^a-z0-9ñ ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
