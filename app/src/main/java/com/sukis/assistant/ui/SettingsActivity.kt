package com.sukis.assistant.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sukis.assistant.Prefs
import com.sukis.assistant.tools.SpotifyClient
import com.sukis.assistant.voice.AssistantService
import com.sukis.assistant.voice.Speaker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private val fields = mutableListOf<Pair<EditText, (String) -> Unit>>()
    private lateinit var spotifyStatus: TextView
    private lateinit var engines: RadioGroup
    private var previewSpeaker: Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Theme.BG
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        updateSpotify()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        previewSpeaker?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(48))
        }
        root.addView(text("Ajustes de Sukis", 28f, bold = true))

        section(root, "Cerebro (IA gratuita)",
            "Sukis prueba los modelos en orden y salta al siguiente si uno falla o llega a su límite. " +
                "Con Groq basta para empezar: también transcribe tu voz con Whisper.")
        link(root, "Crear key de Groq (gratis)", "https://console.groq.com/keys")
        field(root, "Groq API key", prefs.groqKey, true) { prefs.groqKey = it }
        link(root, "Crear key de Google Gemini (gratis)", "https://aistudio.google.com/apikey")
        field(root, "Gemini API key", prefs.geminiKey, true) { prefs.geminiKey = it }
        link(root, "Crear key de OpenRouter (modelos :free)", "https://openrouter.ai/settings/keys")
        field(root, "OpenRouter API key", prefs.openRouterKey, true) { prefs.openRouterKey = it }
        link(root, "Crear key de OpenCode Zen", "https://opencode.ai/auth")
        field(root, "OpenCode Zen API key", prefs.openCodeKey, true) { prefs.openCodeKey = it }
        root.addView(text("Orden de modelos (proveedor:modelo, uno por línea; openrouter:auto-free elige los gratuitos)", 13f, Theme.MUTED), lp(top = dp(12)))
        val chain = field(root, "", prefs.modelChain, multiline = true) { prefs.modelChain = it.ifBlank { Prefs.DEFAULT_MODEL_CHAIN } }
        root.addView(button("Restaurar orden por defecto") { chain.setText(Prefs.DEFAULT_MODEL_CHAIN) }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = dp(8)))

        section(root, "Búsqueda en internet", "Sin key se usa DuckDuckGo. Tavily (1000 búsquedas gratis al mes) da resultados mucho más precisos para deportes y noticias.")
        link(root, "Crear key de Tavily (gratis)", "https://app.tavily.com/home")
        field(root, "Tavily API key", prefs.tavilyKey, true) { prefs.tavilyKey = it }

        section(root, "Spotify Premium",
            "1) Entra a developer.spotify.com/dashboard y crea una app.\n" +
                "2) En Redirect URI pon exactamente: ${SpotifyClient.REDIRECT_URI}\n" +
                "3) Marca \"Web API\" y \"Android\", guarda, y copia aquí el Client ID.\n" +
                "4) Toca Conectar y acepta.")
        link(root, "Abrir el panel de Spotify Developers", "https://developer.spotify.com/dashboard")
        field(root, "Spotify Client ID", prefs.spotifyClientId) { prefs.spotifyClientId = it }
        spotifyStatus = text("", 14f, Theme.MUTED)
        root.addView(spotifyStatus, lp(top = dp(8)))
        val spRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        spRow.addView(button("Conectar Spotify", primary = true) {
            save()
            val i = SpotifyClient(this, prefs).authorizeIntent()
            if (i == null) toast("Primero pega el Client ID") else startActivity(i)
        })
        spRow.addView(View(this), lp(dp(12), 1))
        spRow.addView(button("Desconectar") { SpotifyClient(this, prefs).disconnect(); updateSpotify() })
        root.addView(spRow, lp(top = dp(8)))

        section(root, "Voz de Sukis", "Andrea (es-EC) es una voz neural femenina de Ecuador. \"Edge\" es gratis y sin cuenta; " +
            "\"Azure\" es el servicio oficial de Microsoft (capa gratuita con tarjeta). Si fallan, se usa la voz de la tablet.")
        engines = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("edge" to "Microsoft Edge (gratis, recomendado)", "azure" to "Azure Speech (oficial, requiere key)", "android" to "Voz de la tablet (offline)")
            .forEachIndexed { i, (id, label) ->
                engines.addView(RadioButton(this).apply {
                    this.id = 1000 + i
                    text = label
                    setTextColor(Theme.TEXT)
                    tag = id
                    isChecked = prefs.ttsEngine == id
                })
            }
        root.addView(engines, lp(top = dp(8)))
        field(root, "Voz (ej. es-EC-AndreaNeural)", prefs.voiceName) { prefs.voiceName = it.ifBlank { "es-EC-AndreaNeural" } }
        field(root, "Azure Speech key (opcional)", prefs.azureKey, true) { prefs.azureKey = it }
        field(root, "Azure región (ej. eastus)", prefs.azureRegion) { prefs.azureRegion = it.ifBlank { "eastus" } }
        root.addView(button("▶  Probar voz") {
            save()
            val sp = previewSpeaker ?: Speaker(this, prefs).also { previewSpeaker = it }
            scope.launch { sp.speak("¡Hola! Soy Sukis, tu asistente. ¿En qué te ayudo hoy?") }
        }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = dp(8)))

        section(root, "Personal", "")
        field(root, "Tu nombre (opcional)", prefs.userName) { prefs.userName = it }
        field(root, "Ciudad (para el clima)", prefs.city) { prefs.city = it.ifBlank { "Quito" } }

        section(root, "Palabra de activación",
            "Formas en que el reconocedor offline puede escribir \"Sukis\", separadas por comas. " +
                "Si Sukis no te responde, mira en la pantalla principal qué aparece en \"Oigo:\" y agrégalo aquí.")
        field(root, "", prefs.wakeAliases) { prefs.wakeAliases = it }
        root.addView(check("Sonido corto al escuchar \"Oye Sukis\"", prefs.wakeBeep) { prefs.wakeBeep = it }, lp(top = dp(8)))
        root.addView(check("Mantener la pantalla encendida en Sukis", prefs.keepScreenOn) { prefs.keepScreenOn = it }, lp(top = dp(4)))

        section(root, "Casa inteligente (opcional)", "Si usas Home Assistant, Sukis puede prender luces, enchufes, etc. Crea un token de larga duración en tu perfil de Home Assistant.")
        field(root, "URL de Home Assistant (ej. http://192.168.1.10:8123)", prefs.haUrl) { prefs.haUrl = it }
        field(root, "Token de Home Assistant", prefs.haToken, true) { prefs.haToken = it }

        root.addView(button("Guardar", primary = true) { save(); toast("Guardado"); finish() }, lp(top = dp(24)))

        return ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            addView(root)
        }
    }

    private fun section(root: LinearLayout, title: String, help: String) {
        root.addView(text(title, 20f, Theme.ACCENT, bold = true), lp(top = dp(28)))
        if (help.isNotBlank()) root.addView(text(help, 14f, Theme.MUTED), lp(top = dp(4)))
    }

    private fun link(root: LinearLayout, label: String, url: String) {
        root.addView(text("↗ $label", 14f, Theme.ACCENT).apply {
            setPadding(0, dp(10), 0, dp(2))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        })
    }

    private fun field(root: LinearLayout, hint: String, value: String, secret: Boolean = false, multiline: Boolean = false, save: (String) -> Unit): EditText {
        val e = input(hint, value, secret, multiline)
        fields += e to save
        root.addView(e, lp(top = dp(6)))
        return e
    }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = value
        setTextColor(Theme.TEXT)
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun save() {
        fields.forEach { (e, s) -> s(e.text.toString()) }
        if (::engines.isInitialized) {
            findViewById<RadioButton>(engines.checkedRadioButtonId)?.let { prefs.ttsEngine = it.tag as String }
        }
        AssistantService.instance?.let {
            startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_RELOAD))
        }
    }

    private fun updateSpotify() {
        if (!::spotifyStatus.isInitialized) return
        spotifyStatus.text = if (prefs.spotifyRefreshToken.isNotBlank()) "✅ Spotify conectado" else "Spotify no conectado (se usará el modo básico)"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** Recibe la redirección sukis://spotify-callback tras autorizar en Spotify. */
class SpotifyCallbackActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.getQueryParameter("code")
        val error = intent?.data?.getQueryParameter("error")
        if (code == null) {
            Toast.makeText(this, "Spotify: ${error ?: "sin código"}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        scope.launch {
            try {
                SpotifyClient(this@SpotifyCallbackActivity, Prefs(this@SpotifyCallbackActivity)).exchangeCode(code)
                Toast.makeText(this@SpotifyCallbackActivity, "✅ Spotify conectado", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SpotifyCallbackActivity, "Error con Spotify: ${e.message}", Toast.LENGTH_LONG).show()
            }
            startActivity(Intent(this@SpotifyCallbackActivity, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
