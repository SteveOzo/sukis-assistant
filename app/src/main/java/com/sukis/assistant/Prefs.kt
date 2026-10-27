package com.sukis.assistant

import android.content.Context
import android.content.SharedPreferences

/** Configuración persistente de Sukis (API keys, preferencias, tokens). */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("sukis", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = sp.getString(key, def) ?: def
    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()

    var groqKey get() = str("groq_key"); set(v) = put("groq_key", v.trim())
    var geminiKey get() = str("gemini_key"); set(v) = put("gemini_key", v.trim())
    var openRouterKey get() = str("openrouter_key"); set(v) = put("openrouter_key", v.trim())
    var openCodeKey get() = str("opencode_key"); set(v) = put("opencode_key", v.trim())
    var tavilyKey get() = str("tavily_key"); set(v) = put("tavily_key", v.trim())

    /** Cadena de modelos, uno por línea: "proveedor:modelo". Se prueban en orden. */
    var modelChain get() = str("model_chain", DEFAULT_MODEL_CHAIN); set(v) = put("model_chain", v)

    var city get() = str("city", "Quito"); set(v) = put("city", v.trim())
    var userName get() = str("user_name"); set(v) = put("user_name", v.trim())
    var wakeAliases get() = str("wake_aliases", DEFAULT_WAKE_ALIASES); set(v) = put("wake_aliases", v)

    /** "edge" (voz neural gratis de Microsoft), "azure" (oficial con key) o "android". */
    var ttsEngine get() = str("tts_engine", "edge"); set(v) = put("tts_engine", v)
    var voiceName get() = str("voice_name", "es-EC-AndreaNeural"); set(v) = put("voice_name", v.trim())
    var azureKey get() = str("azure_key"); set(v) = put("azure_key", v.trim())
    var azureRegion get() = str("azure_region", "eastus"); set(v) = put("azure_region", v.trim())

    var spotifyClientId get() = str("spotify_client_id"); set(v) = put("spotify_client_id", v.trim())
    var spotifyAccessToken get() = str("spotify_access"); set(v) = put("spotify_access", v)
    var spotifyRefreshToken get() = str("spotify_refresh"); set(v) = put("spotify_refresh", v)
    var spotifyExpiresAt: Long
        get() = sp.getLong("spotify_expires", 0)
        set(v) = sp.edit().putLong("spotify_expires", v).apply()
    var spotifyVerifier get() = str("spotify_verifier"); set(v) = put("spotify_verifier", v)

    var haUrl get() = str("ha_url"); set(v) = put("ha_url", v.trim().trimEnd('/'))
    var haToken get() = str("ha_token"); set(v) = put("ha_token", v.trim())

    var wakeBeep: Boolean
        get() = sp.getBoolean("wake_beep", true)
        set(v) = sp.edit().putBoolean("wake_beep", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    companion object {
        const val DEFAULT_MODEL_CHAIN = """groq:openai/gpt-oss-120b
gemini:gemini-flash-latest
groq:llama-3.3-70b-versatile
openrouter:auto-free
opencode:big-pickle
groq:openai/gpt-oss-20b"""

        const val DEFAULT_WAKE_ALIASES = "sukis, suki, suquis, suqui, zukis, zuki, suky, sukys"
    }
}
