package com.sukis.assistant.brain

import android.content.Context
import com.sukis.assistant.AssistantState
import com.sukis.assistant.Bus
import com.sukis.assistant.Prefs
import com.sukis.assistant.tools.AlarmTool
import com.sukis.assistant.tools.DeviceStatusTool
import com.sukis.assistant.tools.FetchPageTool
import com.sukis.assistant.tools.HomeAssistantTool
import com.sukis.assistant.tools.ListsTool
import com.sukis.assistant.tools.MapsTool
import com.sukis.assistant.tools.MediaControlTool
import com.sukis.assistant.tools.MemoryTool
import com.sukis.assistant.tools.OpenAppTool
import com.sukis.assistant.tools.OpenUrlTool
import com.sukis.assistant.tools.PlayMusicTool
import com.sukis.assistant.tools.ReminderTool
import com.sukis.assistant.tools.SpotifyClient
import com.sukis.assistant.tools.TimerTool
import com.sukis.assistant.tools.Tool
import com.sukis.assistant.tools.ToolEnv
import com.sukis.assistant.tools.VolumeTool
import com.sukis.assistant.tools.WeatherTool
import com.sukis.assistant.tools.WebSearchTool
import com.sukis.assistant.tools.WhatsAppTool
import com.sukis.assistant.tools.YouTubeTool
import com.sukis.assistant.tools.fail
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Reply(val text: String, val expectsAnswer: Boolean)

class Agent(context: Context, private val prefs: Prefs) {
    val spotify = SpotifyClient(context, prefs)
    private val router = LlmRouter(prefs)
    private val env = ToolEnv(context, prefs)
    private val mutex = Mutex()

    /** Historial corto (usuario/asistente) para preguntas de seguimiento. */
    private val history = mutableListOf<JSONObject>()
    private var lastTurnAt = 0L

    private fun tools(): List<Tool> {
        val list = mutableListOf(
            PlayMusicTool(env, spotify), MediaControlTool(env, spotify), VolumeTool(env),
            OpenAppTool(env), YouTubeTool(env), MapsTool(env), OpenUrlTool(env),
            WebSearchTool(env), FetchPageTool(), WeatherTool(env),
            TimerTool(env), AlarmTool(env), ReminderTool(env),
            ListsTool(prefs), MemoryTool(prefs), WhatsAppTool(env), DeviceStatusTool(env),
        )
        if (prefs.haUrl.isNotBlank() && prefs.haToken.isNotBlank()) list += HomeAssistantTool(env)
        return list
    }

    private fun systemPrompt(): String {
        val now = ZonedDateTime.now()
        val date = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-EC")).format(now)
        val facts = MemoryTool.facts(prefs)
        val user = prefs.userName.ifBlank { null }
        return buildString {
            appendLine("Eres Sukis, la asistente de voz del hogar. Vives en una tablet Samsung Galaxy Tab en ${prefs.city}, Ecuador.")
            appendLine("Eres mujer, cálida, alegre y eficiente. Hablas español de Ecuador de forma natural (puedes usar expresiones ecuatorianas suaves como \"de una\" o \"chévere\", sin exagerar).")
            appendLine("Fecha y hora actual: $date (zona ${now.zone.id}).")
            if (user != null) appendLine("El usuario se llama $user.")
            appendLine()
            appendLine("REGLAS:")
            appendLine("- Tu respuesta se convierte en voz: responde corto (1 a 3 frases), sin markdown, sin listas con viñetas, sin emojis, sin URLs.")
            appendLine("- Escribe números, horas y marcadores de forma fácil de leer en voz alta (ej. \"dos a uno\", \"las cinco y media\").")
            appendLine("- El texto viene de reconocimiento de voz y puede tener errores; interpreta la intención más probable. Puede empezar con \"Oye Sukis\": ignóralo.")
            appendLine("- Usa las herramientas para actuar. Para pedidos de música usa play_music (en Spotify) con título y artista. Si piden abrir una app y además reproducir algo, basta con play_music.")
            appendLine("- Para cualquier dato actual (resultados deportivos, noticias, precios, horarios, eventos) usa SIEMPRE web_search antes de responder; nunca inventes resultados. En deportes indica rival, marcador, competición y fecha del partido, y verifica que sea el más reciente comparando fechas con la fecha actual.")
            appendLine("- Si los resultados de búsqueda no son claros, haz otra búsqueda o lee una página con read_webpage.")
            appendLine("- Tras ejecutar una acción, confirma brevemente lo que hiciste. Si algo falla, explica en una frase qué pasó.")
            appendLine("- Si el pedido es ambiguo, haz una pregunta corta.")
            appendLine("- Si el usuario te cuenta algo personal duradero (gustos, nombres, fechas importantes) y parece útil, guárdalo con memory.")
            if (facts.isNotEmpty()) {
                appendLine()
                appendLine("Lo que sabes del usuario y su hogar:")
                facts.forEach { appendLine("- $it") }
            }
        }
    }

    suspend fun handle(userText: String): Reply = mutex.withLock {
        if (System.currentTimeMillis() - lastTurnAt > 10 * 60_000) history.clear()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        history.forEach { messages.put(it) }
        val userMsg = JSONObject().put("role", "user").put("content", userText)
        messages.put(userMsg)

        val tools = tools()
        val specs = JSONArray().also { a -> tools.forEach { a.put(it.spec()) } }
        val byName = tools.associateBy { it.name }

        var finalText: String? = null
        for (step in 0 until 6) {
            val (msg, cand) = router.complete(messages, specs)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                finalText = msg.optString("content").trim()
                Bus.log("Respuesta de ${cand.label}")
                break
            }
            // Se reenvía el mensaje del asistente con sus tool_calls tal cual (incluye firmas de Gemini).
            val assistantMsg = JSONObject().put("role", "assistant").put("tool_calls", calls)
            msg.optString("content").takeIf { it.isNotBlank() && it != "null" }?.let { assistantMsg.put("content", it) }
            messages.put(assistantMsg)
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = try {
                    JSONObject(fn.optString("arguments").ifBlank { "{}" })
                } catch (e: Exception) {
                    JSONObject()
                }
                Bus.log("🔧 $name $args (${cand.label})")
                Bus.set(AssistantState.THINKING, toolLabel(name))
                val result = try {
                    byName[name]?.run(args) ?: fail("Herramienta desconocida: $name")
                } catch (e: Exception) {
                    fail(e.message ?: e.javaClass.simpleName)
                }
                Bus.log("   → ${result.take(200)}")
                messages.put(
                    JSONObject().put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("name", name)
                        .put("content", result.take(7000)),
                )
            }
        }
        val text = cleanForSpeech(finalText ?: "Listo.")
        history += userMsg
        history += JSONObject().put("role", "assistant").put("content", text)
        while (history.size > 12) history.removeAt(0)
        lastTurnAt = System.currentTimeMillis()
        Reply(text, text.trimEnd().endsWith("?"))
    }

    fun resetConversation() {
        history.clear()
    }

    private fun toolLabel(name: String) = when (name) {
        "web_search", "read_webpage" -> "Buscando en internet…"
        "play_music" -> "Abriendo Spotify…"
        "get_weather" -> "Consultando el clima…"
        else -> "Ejecutando $name…"
    }

    companion object {
        /** Quita restos de markdown/URLs que no deben leerse en voz alta. */
        fun cleanForSpeech(s: String): String = s
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*#_`>|]"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
