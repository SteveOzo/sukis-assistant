package com.sukis.assistant.brain

import com.sukis.assistant.Bus
import com.sukis.assistant.Prefs
import com.sukis.assistant.net.Http
import com.sukis.assistant.net.HttpException
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Un proveedor con API compatible con OpenAI (chat/completions + tools). */
data class Provider(
    val id: String,
    val baseUrl: String,
    val key: (Prefs) -> String,
    val extraHeaders: Map<String, String> = emptyMap(),
)

data class Candidate(val provider: Provider, val model: String) {
    val label get() = "${provider.id}:$model"
}

class NoModelAvailable(msg: String) : IOException(msg)

/**
 * Prueba los modelos de la cadena configurada en orden. Si uno falla (límite de uso,
 * error del servidor, modelo inexistente…) lo pone en "enfriamiento" y salta al siguiente.
 */
class LlmRouter(private val prefs: Prefs) {

    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private var openRouterFree: List<String> = emptyList()
    private var openRouterFetchedAt = 0L

    /** Último candidato que funcionó: se prueba primero en el siguiente turno. */
    @Volatile private var sticky: String? = null

    fun parseChain(): List<Candidate> = parseChain(prefs.modelChain)

    suspend fun complete(messages: JSONArray, tools: JSONArray?): Pair<JSONObject, Candidate> {
        val candidates = expand(parseChain())
        if (candidates.isEmpty()) {
            throw NoModelAvailable("No hay modelos configurados con API key. Abre Ajustes y agrega al menos la key de Groq o Gemini.")
        }
        val ordered = sticky?.let { s -> candidates.filter { it.label == s } + candidates.filter { it.label != s } } ?: candidates
        val now = System.currentTimeMillis()
        val ready = ordered.filter { (cooldownUntil[it.label] ?: 0) <= now }
        // Si todos están en enfriamiento, igual se intenta con todos: mejor que no responder.
        val attempt = ready.ifEmpty { ordered }
        var lastError: Exception? = null
        for (c in attempt) {
            try {
                val msg = request(c, messages, tools)
                sticky = c.label
                return msg to c
            } catch (e: HttpException) {
                lastError = e
                val wait = when (e.code) {
                    429 -> (e.retryAfterSec ?: 60L).coerceIn(10, 3600) * 1000
                    401, 403 -> 30 * 60_000L
                    400, 404, 422 -> 60 * 60_000L
                    else -> 30_000L
                }
                cooldownUntil[c.label] = System.currentTimeMillis() + wait
                Bus.log("LLM ${c.label} falló (${e.code}): ${e.body.take(160)}")
                if (sticky == c.label) sticky = null
            } catch (e: Exception) {
                lastError = e
                cooldownUntil[c.label] = System.currentTimeMillis() + 20_000
                Bus.log("LLM ${c.label} falló: ${e.message}")
                if (sticky == c.label) sticky = null
            }
        }
        throw NoModelAvailable("Ningún modelo respondió. Último error: ${lastError?.message?.take(200)}")
    }

    private suspend fun request(c: Candidate, messages: JSONArray, tools: JSONArray?): JSONObject {
        val body = JSONObject()
            .put("model", c.model)
            .put("messages", sanitize(messages, c.provider.id))
            .put("temperature", 0.4)
            .put("max_tokens", 1024)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools).put("tool_choice", "auto")
        }
        val b = Request.Builder()
            .url(c.provider.baseUrl + "/chat/completions")
            .header("Authorization", "Bearer " + c.provider.key(prefs))
            .post(Http.jsonBody(body))
        c.provider.extraHeaders.forEach { (k, v) -> b.header(k, v) }
        val json = JSONObject(Http.call(b.build()))
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw IOException("Respuesta sin choices: ${json.toString().take(200)}")
        }
        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IOException("Respuesta sin message")
        val content = contentOf(msg)
        val calls = msg.optJSONArray("tool_calls")
        if (content.isBlank() && (calls == null || calls.length() == 0)) {
            throw IOException("Respuesta vacía del modelo")
        }
        return msg
    }

    /** Ajusta el historial a lo que cada proveedor acepta. */
    private fun sanitize(messages: JSONArray, providerId: String): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val m = JSONObject(messages.getJSONObject(i).toString())
            if (providerId != "gemini") {
                // La "firma de pensamiento" de Gemini solo la entiende Gemini.
                m.optJSONArray("tool_calls")?.let { calls ->
                    for (j in 0 until calls.length()) calls.getJSONObject(j).remove("extra_content")
                }
            }
            out.put(m)
        }
        return out
    }

    private suspend fun expand(chain: List<Candidate>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (c in chain) {
            if (c.provider.key(prefs).isBlank()) continue
            if (c.provider.id == "openrouter" && c.model == "auto-free") {
                freeOpenRouterModels().forEach { out += Candidate(c.provider, it) }
            } else {
                out += c
            }
        }
        return out.distinctBy { it.label }
    }

    /** Lista dinámica de modelos gratuitos de OpenRouter que soportan herramientas. */
    private suspend fun freeOpenRouterModels(): List<String> {
        if (openRouterFree.isNotEmpty() && System.currentTimeMillis() - openRouterFetchedAt < 6 * 3600_000L) {
            return openRouterFree
        }
        return try {
            val data = Http.getJson("https://openrouter.ai/api/v1/models").getJSONArray("data")
            val list = mutableListOf<Pair<String, Int>>()
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val id = m.optString("id")
                val pricing = m.optJSONObject("pricing")
                val free = id.endsWith(":free") ||
                    (pricing?.optString("prompt") == "0" && pricing.optString("completion") == "0")
                val params = m.optJSONArray("supported_parameters")
                var tools = false
                if (params != null) for (j in 0 until params.length()) if (params.optString(j) == "tools") tools = true
                if (free && tools) list += id to m.optInt("context_length", 0)
            }
            openRouterFree = list.sortedByDescending { it.second }.take(4).map { it.first }
            openRouterFetchedAt = System.currentTimeMillis()
            Bus.log("OpenRouter gratis: ${openRouterFree.joinToString()}")
            openRouterFree
        } catch (e: Exception) {
            Bus.log("No se pudo obtener la lista de OpenRouter: ${e.message}")
            openRouterFree
        }
    }

    companion object {
        /** org.json convierte un content nulo en el texto "null"; esto lo evita. */
        fun contentOf(msg: JSONObject): String =
            if (!msg.has("content") || msg.isNull("content")) "" else msg.optString("content")

        val PROVIDERS = listOf(
            Provider("groq", "https://api.groq.com/openai/v1", { it.groqKey }),
            Provider("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", { it.geminiKey }),
            Provider(
                "openrouter", "https://openrouter.ai/api/v1", { it.openRouterKey },
                mapOf("HTTP-Referer" to "https://github.com/SteveOzo/sukis-assistant", "X-Title" to "Sukis"),
            ),
            Provider("opencode", "https://opencode.ai/zen/v1", { it.openCodeKey }),
        ).associateBy { it.id }

        fun parseChain(text: String): List<Candidate> = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val p = PROVIDERS[line.substring(0, idx).trim().lowercase()] ?: return@mapNotNull null
                val model = line.substring(idx + 1).trim()
                if (model.isEmpty()) null else Candidate(p, model)
            }
    }
}
