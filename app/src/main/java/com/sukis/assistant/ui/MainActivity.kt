package com.sukis.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sukis.assistant.Bus
import com.sukis.assistant.Prefs
import com.sukis.assistant.voice.AssistantService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var heard: TextView
    private lateinit var youText: TextView
    private lateinit var sukisText: TextView
    private lateinit var toggle: Button
    private lateinit var setup: LinearLayout
    private lateinit var logView: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        setContentView(build())

        scope.launch { Bus.state.collect { s -> orb.setState(s); status.text = s.label; updateToggle() } }
        scope.launch { Bus.detail.collect { detail.text = it; detail.visibility = if (it.isBlank()) View.GONE else View.VISIBLE } }
        scope.launch { Bus.heard.collect { heard.text = if (it.isBlank()) "" else "Oigo: \"$it\"" } }
        scope.launch {
            Bus.lastExchange.collect { e ->
                youText.text = e?.let { "Tú: ${it.user}" } ?: "Prueba: \"Oye Sukis, pon Candy de Plan B\""
                sukisText.text = e?.reply ?: "o \"Oye Sukis, ¿cómo quedó el último partido de Independiente del Valle?\""
            }
        }
        scope.launch { Bus.log.collect { logView.text = it.takeLast(60).joinToString("\n") } }

        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) prefs.serviceEnabled = true
    }

    override fun onResume() {
        super.onResume()
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshSetup()
        if (prefs.serviceEnabled && hasMic() && AssistantService.instance == null) AssistantService.start(this)
        updateToggle()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("Sukis", 30f, bold = true), lp(0, weight = 1f))
        top.addView(button("Ajustes") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(top)

        orb = OrbView(this).apply { setOnClickListener { talk() } }
        root.addView(orb, lp(h = dp(280), top = dp(8)))
        status = text("", 26f, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(status, lp())
        detail = text("", 15f, Theme.MUTED).apply { gravity = Gravity.CENTER }
        root.addView(detail, lp(top = dp(4)))
        heard = text("", 14f, Theme.MUTED).apply { gravity = Gravity.CENTER }
        root.addView(heard, lp(top = dp(4)))

        val talkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        talkRow.addView(button("🎤  Hablar", primary = true) { talk() })
        talkRow.addView(View(this), lp(dp(12), 1))
        talkRow.addView(button("■  Callar") { AssistantService.instance?.stopTalking() })
        talkRow.addView(View(this), lp(dp(12), 1))
        toggle = button("Encender") { toggleService() }
        talkRow.addView(toggle)
        root.addView(talkRow, lp(top = dp(16)))

        val conv = card()
        youText = text("", 16f, Theme.MUTED)
        sukisText = text("", 20f)
        conv.addView(youText)
        conv.addView(sukisText, lp(top = dp(8)))
        root.addView(conv, lp(top = dp(20)))

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = input("O escribe un pedido…").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> sendTyped(); true }
        }
        inputRow.addView(input, lp(0, weight = 1f))
        inputRow.addView(View(this), lp(dp(10), 1))
        inputRow.addView(button("Enviar") { sendTyped() })
        root.addView(inputRow, lp(top = dp(16)))

        setup = card()
        root.addView(setup, lp(top = dp(20)))

        logView = text("", 12f, Theme.MUTED).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        val logToggle = button("Ver registro técnico") {
            logView.visibility = if (logView.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        root.addView(logToggle, lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = dp(20)))
        root.addView(logView, lp(top = dp(8)))

        return ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            addView(root)
        }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        val svc = AssistantService.instance
        if (svc != null) {
            svc.handleText(t)
            return
        }
        if (!ensureReady()) return
        prefs.serviceEnabled = true
        AssistantService.start(this)
        // El servicio procesa el texto en cuanto arranca.
        startService(
            Intent(this, AssistantService::class.java)
                .setAction(AssistantService.ACTION_TEXT)
                .putExtra(AssistantService.EXTRA_TEXT, t),
        )
    }

    private fun talk() {
        val svc = AssistantService.instance ?: return toggleService()
        svc.listenNow()
    }

    private fun toggleService() {
        if (AssistantService.instance != null) {
            prefs.serviceEnabled = false
            AssistantService.stop(this)
        } else {
            if (!ensureReady()) return
            prefs.serviceEnabled = true
            AssistantService.start(this)
        }
        status.postDelayed({ updateToggle() }, 500)
    }

    private fun updateToggle() {
        if (!::toggle.isInitialized) return
        toggle.text = if (AssistantService.instance != null) "Apagar" else "Encender"
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureReady(): Boolean {
        if (!hasMic()) {
            requestPerms()
            return false
        }
        return true
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
        if (hasMic() && AssistantService.instance == null) {
            prefs.serviceEnabled = true
            AssistantService.start(this)
            status.postDelayed({ updateToggle() }, 500)
        }
    }

    /** Pasos de configuración pendientes, con un botón para cada uno. */
    private fun refreshSetup() {
        setup.removeAllViews()
        val pm = getSystemService(PowerManager::class.java)!!
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!hasMic() || needsNotif) {
            items += "Dar permiso de micrófono y notificaciones" to { requestPerms() }
        }
        if (!Settings.canDrawOverlays(this)) {
            items += "Permitir \"Mostrar sobre otras apps\" (para abrir apps por voz)" to {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            items += "Quitar restricción de batería (para que Samsung no la apague)" to {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        val openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (prefs.groqKey.isBlank() && prefs.geminiKey.isBlank() && prefs.openRouterKey.isBlank()) {
            items += "Agregar al menos una API key gratuita (Groq recomendado)" to openSettings
        }
        if (prefs.spotifyRefreshToken.isBlank()) {
            items += "Conectar Spotify Premium (opcional, reproducción exacta)" to openSettings
        }
        if (prefs.tavilyKey.isBlank()) {
            items += "Agregar key de Tavily (opcional, búsquedas más precisas)" to openSettings
        }
        setup.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) return
        setup.addView(text("Configuración pendiente", 18f, bold = true))
        items.forEach { (label, action) ->
            setup.addView(
                button("•  $label") { action() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
                lp(top = dp(10)),
            )
        }
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}
