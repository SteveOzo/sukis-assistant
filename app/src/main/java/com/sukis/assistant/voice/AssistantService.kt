package com.sukis.assistant.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.sukis.assistant.AssistantState
import com.sukis.assistant.Bus
import com.sukis.assistant.Exchange
import com.sukis.assistant.Prefs
import com.sukis.assistant.R
import com.sukis.assistant.SukisApp
import com.sukis.assistant.brain.Agent
import com.sukis.assistant.tools.norm
import com.sukis.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Servicio en primer plano: mantiene el micrófono escuchando y coordina voz → IA → acción → voz. */
class AssistantService : Service(), AudioPipeline.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    lateinit var agent: Agent
        private set
    private lateinit var speaker: Speaker
    private lateinit var transcriber: Transcriber
    private var pipeline: AudioPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var turn: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        if (!goForeground()) {
            SukisApp.notifyReactivate(this)
            stopSelf()
            return
        }
        instance = this
        agent = Agent(this, prefs)
        speaker = Speaker(this, prefs)
        transcriber = Transcriber(prefs)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sukis:listen").apply { acquire() }
        Bus.set(AssistantState.LOADING, "Preparando el oído de Sukis…")
        scope.launch {
            try {
                val dir = VoskModels.ensure(this@AssistantService) { pct ->
                    Bus.set(AssistantState.LOADING, "Descargando modelo de voz offline: $pct%")
                }
                pipeline = AudioPipeline(dir, WakeWord(prefs.wakeAliases), this@AssistantService).also { it.start() }
                Bus.set(AssistantState.IDLE)
                Bus.log("Escuchando \"Oye Sukis\"")
            } catch (e: Exception) {
                Bus.set(AssistantState.ERROR, "No se pudo preparar el modelo de voz: ${e.message}")
                Bus.log("Error modelo: ${e.message}")
            }
        }
    }

    private fun goForeground(): Boolean {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val listen = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_LISTEN), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, SukisApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sukis está escuchando")
            .setContentText("Di \"Oye Sukis\"")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Hablar", listen).build())
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SukisApp.NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(SukisApp.NOTIF_SERVICE, n)
            }
            true
        } catch (e: Exception) {
            Bus.log("No se pudo iniciar en primer plano: ${e.message}")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance == null) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_LISTEN -> listenNow()
            ACTION_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { handleText(it) }
            ACTION_STOP -> stopTalking()
            ACTION_RELOAD -> pipeline?.wake = WakeWord(prefs.wakeAliases)
        }
        return START_STICKY
    }

    fun listenNow() {
        stopTalking()
        pipeline?.let {
            it.muted = false
            it.listenNow()
        }
    }

    fun stopTalking() {
        turn?.cancel()
        speaker.stop()
        pipeline?.muted = false
        Bus.set(AssistantState.IDLE)
    }

    // ---- AudioPipeline.Listener (llamado desde el hilo de audio) ----

    override fun onWake() {
        scope.launch {
            Bus.set(AssistantState.LISTENING)
            speaker.duck()
            if (prefs.wakeBeep) speaker.beep()
        }
    }

    override fun onCommand(pcm: ShortArray, voskText: String) {
        scope.launch { runTurn(pcm, voskText) }
    }

    override fun onNothingSaid() {
        scope.launch {
            speaker.unduck()
            Bus.set(AssistantState.IDLE)
        }
    }

    override fun onHeard(text: String) {
        Bus.heard.value = text
    }

    override fun onError(msg: String) {
        Bus.log(msg)
    }

    // ---- turnos ----

    private suspend fun runTurn(pcm: ShortArray, voskText: String) {
        Bus.set(AssistantState.THINKING, "Transcribiendo…")
        var text = voskText
        if (transcriber.available) {
            try {
                val t = transcriber.transcribe(pcm, AudioPipeline.RATE)
                if (t.isNotBlank()) text = t
            } catch (e: Exception) {
                Bus.log("Whisper falló, uso el texto offline: ${e.message}")
            }
        }
        Bus.log("🗣 $text")
        val wake = WakeWord(prefs.wakeAliases)
        val command = if (wake.matches(text)) wake.commandAfter(text) else norm(text)
        if (command.isBlank()) {
            speaker.unduck()
            pipeline?.muted = false
            Bus.set(AssistantState.IDLE)
            return
        }
        respond(text)
    }

    /** Comando escrito desde la pantalla. */
    fun handleText(text: String) {
        if (text.isBlank()) return
        pipeline?.muted = true
        scope.launch { respond(text) }
    }

    private suspend fun respond(text: String) {
        turn?.cancel()
        val job = scope.launch {
            try {
                Bus.set(AssistantState.THINKING, "Pensando…")
                val reply = try {
                    agent.handle(text)
                } catch (e: Exception) {
                    Bus.log("Error del agente: ${e.message}")
                    com.sukis.assistant.brain.Reply(
                        "Uy, no pude conectarme con mi cerebro en la nube. Revisa el internet o las API keys en Ajustes.",
                        false,
                    )
                }
                Bus.lastExchange.value = Exchange(text, reply.text)
                Bus.set(AssistantState.SPEAKING)
                speaker.speak(reply.text)
                if (reply.expectsAnswer) pipeline?.armFollowUp(8000)
            } finally {
                speaker.unduck()
                pipeline?.muted = false
                if (Bus.state.value != AssistantState.LISTENING) Bus.set(AssistantState.IDLE)
            }
        }
        turn = job
        job.join()
    }

    /** Habla algo por iniciativa propia (recordatorios). */
    fun announce(text: String) {
        scope.launch {
            pipeline?.muted = true
            try {
                Bus.set(AssistantState.SPEAKING, text)
                speaker.speak(text)
            } finally {
                pipeline?.muted = false
                Bus.set(AssistantState.IDLE)
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        val p = pipeline
        pipeline = null
        Thread { p?.stop() }.start()
        if (::speaker.isInitialized) speaker.shutdown()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        Bus.set(AssistantState.OFF)
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: AssistantService? = null
            private set

        const val ACTION_LISTEN = "com.sukis.assistant.LISTEN"
        const val ACTION_TEXT = "com.sukis.assistant.TEXT"
        const val ACTION_STOP = "com.sukis.assistant.STOP"
        const val ACTION_RELOAD = "com.sukis.assistant.RELOAD"
        const val EXTRA_TEXT = "text"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AssistantService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantService::class.java))
        }
    }
}
