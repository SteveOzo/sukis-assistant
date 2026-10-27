package com.sukis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.sukis.assistant.Bus
import com.sukis.assistant.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** Habla las respuestas: voz neural en la nube (Edge/Azure) con la voz de Android como respaldo. */
class Speaker(private val context: Context, private val prefs: Prefs) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focus: AudioFocusRequest? = null
    @Volatile private var player: MediaPlayer? = null

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()

    init {
        tts = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) configureAndroidVoice()
            ttsReady.complete(ok)
        }
    }

    private fun configureAndroidVoice() {
        val t = tts ?: return
        val voices = runCatching { t.voices }.getOrNull().orEmpty().filter { it.locale.language == "es" && !it.isNetworkConnectionRequired }
        val preferredCountries = listOf("EC", "US", "MX", "CO", "419", "ES")
        val best = voices.sortedWith(
            compareBy(
                { preferredCountries.indexOf(it.locale.country).let { i -> if (i < 0) 99 else i } },
                { if (it.name.contains("female", true)) 0 else 1 },
                { -it.quality },
            ),
        ).firstOrNull()
        if (best != null) t.voice = best else t.language = Locale.forLanguageTag("es-US")
    }

    /** Pide a las otras apps (Spotify) que bajen el volumen mientras Sukis escucha o habla. */
    fun duck() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        focus = req
    }

    fun unduck() {
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 300)
        }
    }

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        // Se vuelve a pedir el foco: Spotify pudo haberlo tomado al empezar a sonar.
        unduck()
        duck()
        try {
            val mp3 = runCatching {
                when (prefs.ttsEngine) {
                    "azure" -> if (prefs.azureKey.isNotBlank()) AzureTts.synthesize(text, prefs.voiceName, prefs.azureKey, prefs.azureRegion) else null
                    "edge" -> EdgeTts.synthesize(text, prefs.voiceName)
                    else -> null
                }
            }.onFailure { Bus.log("Voz en la nube falló: ${it.message}") }.getOrNull()
            if (mp3 != null && playMp3(mp3)) return
            speakAndroid(text)
        } finally {
            unduck()
        }
    }

    private suspend fun playMp3(data: ByteArray): Boolean {
        val f = File(context.cacheDir, "tts.mp3")
        f.writeBytes(data)
        return suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener {
                    it.release(); player = null
                    if (cont.isActive) cont.resume(true)
                }
                mp.setOnErrorListener { p, _, _ ->
                    p.release(); player = null
                    if (cont.isActive) cont.resume(false)
                    true
                }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                mp.release(); player = null
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { runCatching { mp.release() }; player = null }
        }
    }

    private suspend fun speakAndroid(text: String) {
        if (!ttsReady.await()) return
        val t = tts ?: return
        suspendCancellableCoroutine { cont ->
            val id = "sukis-${System.nanoTime()}"
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (cont.isActive) cont.resume(Unit) }
            })
            t.setAudioAttributes(attrs)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { t.stop() }
        }
    }

    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null
        tts?.stop()
        unduck()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
