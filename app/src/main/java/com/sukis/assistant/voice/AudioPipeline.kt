package com.sukis.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.sqrt

/**
 * Escucha el micrófono todo el tiempo. Vosk (offline) busca "Oye Sukis"; cuando lo oye, se sigue
 * grabando hasta que el usuario deja de hablar y se entrega el audio completo del comando.
 */
class AudioPipeline(
    private val modelDir: File,
    @Volatile var wake: WakeWord,
    private val listener: Listener,
) {
    interface Listener {
        fun onWake()
        fun onCommand(pcm: ShortArray, voskText: String)
        fun onNothingSaid()
        fun onHeard(text: String)
        fun onError(msg: String)
    }

    /** Mientras Sukis piensa o habla se ignora el micrófono (para no oírse a sí misma). */
    @Volatile var muted = false
    @Volatile private var running = false
    @Volatile private var forceRequest = false
    @Volatile private var armedUntil = 0L
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "sukis-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    /** Botón "Hablar": empieza a grabar un comando sin palabra de activación. */
    fun listenNow() {
        forceRequest = true
    }

    /** Tras una pregunta de Sukis, la respuesta del usuario no necesita "Oye Sukis". */
    fun armFollowUp(ms: Long) {
        armedUntil = SystemClock.elapsedRealtime() + ms
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = try {
            Model(modelDir.absolutePath)
        } catch (e: Exception) {
            listener.onError("No se pudo cargar el modelo de voz: ${e.message}")
            running = false
            return
        }
        val rec = Recognizer(model, RATE.toFloat())
        val frame = RATE / 10 // 100 ms
        var ar: AudioRecord? = null
        try {
            while (running) {
                if (ar == null) {
                    ar = openMic(frame)
                    if (ar == null) {
                        listener.onError("No pude abrir el micrófono; reintentando…")
                        SystemClock.sleep(3000)
                        continue
                    }
                }
                process(ar, rec, frame)
            }
        } finally {
            runCatching { ar?.stop() }
            runCatching { ar?.release() }
            runCatching { rec.close() }
            runCatching { model.close() }
        }
    }

    private fun openMic(frame: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            @SuppressLint("MissingPermission")
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, frame * 2 * 8),
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release(); null
            } else {
                r.startRecording(); r
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- estado del bucle (solo se usa desde el hilo de audio) ----
    private val chunks = ArrayList<ShortArray>()
    private var chunkSamples = 0
    private var noise = 300.0
    private var wasMuted = false
    private var capturing = false
    private var captureForced = false
    private var captureStart = 0L
    private var lastVoice = 0L
    private var speechFrames = 0
    private var finalsText = ""
    private var partialText = ""
    private var lastFinalAt = 0L
    private var lastPartialShown = ""

    private fun process(ar: AudioRecord, rec: Recognizer, frame: Int) {
        val buf = ShortArray(frame)
        val n = ar.read(buf, 0, frame)
        if (n <= 0) {
            SystemClock.sleep(20)
            return
        }
        if (muted) {
            wasMuted = true
            return
        }
        if (wasMuted) {
            wasMuted = false
            resetUtterance(rec)
            capturing = false
        }
        val now = SystemClock.elapsedRealtime()
        if (forceRequest) {
            forceRequest = false
            resetUtterance(rec)
            startCapture(now, forced = true)
        }

        val chunk = if (n == frame) buf else buf.copyOf(n)
        chunks.add(chunk)
        chunkSamples += n
        if (!capturing) {
            // Antes de la palabra de activación basta con guardar los últimos ~6 s.
            while (chunkSamples > RATE * 6 && chunks.size > 1) chunkSamples -= chunks.removeAt(0).size
        }

        var sum = 0.0
        for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
        val rms = sqrt(sum / n)
        val speech = rms > maxOf(noise * 2.5, 250.0)
        if (!speech && !capturing) noise = noise * 0.95 + rms * 0.05

        val isFinal = rec.acceptWaveForm(chunk, n)
        if (isFinal) {
            val t = JSONObject(rec.result).optString("text").trim()
            if (t.isNotEmpty()) finalsText = (finalsText + " " + t).trim()
            partialText = ""
            lastFinalAt = now
        } else {
            partialText = JSONObject(rec.partialResult).optString("partial").trim()
        }
        val heard = (finalsText + " " + partialText).trim()
        if (heard != lastPartialShown) {
            lastPartialShown = heard
            if (heard.isNotEmpty()) listener.onHeard(heard)
        }

        if (!capturing) {
            val armed = now < armedUntil
            if (wake.matches(heard) || (armed && heard.isNotEmpty())) {
                startCapture(now, forced = armed && !wake.matches(heard))
                // Si la frase ya terminó completa ("oye sukis pon música" + silencio) se procesa abajo.
            } else {
                if (isFinal) clearAudio() // frase sin palabra de activación: se descarta
                return
            }
        }

        // ---- capturando el comando ----
        if (speech) {
            lastVoice = now
            if (now - captureStart > 250) speechFrames++
        }
        val command = if (captureForced) heard else wake.commandAfter(heard)
        val hasCommand = command.isNotBlank() || speechFrames >= 4
        val silentFor = now - maxOf(lastVoice, captureStart)
        val sinceFinal = if (lastFinalAt >= captureStart) now - maxOf(lastFinalAt, lastVoice) else -1
        val elapsed = now - captureStart

        val done = (hasCommand && silentFor > 1300) ||
            (command.isNotBlank() && sinceFinal > 700) ||
            elapsed > 15_000
        if (done && hasCommand) {
            val pcm = ShortArray(chunkSamples)
            var off = 0
            for (c in chunks) { System.arraycopy(c, 0, pcm, off, c.size); off += c.size }
            capturing = false
            muted = true // el servicio lo reactiva cuando termina de responder
            resetUtterance(rec)
            listener.onCommand(pcm, heard)
        } else if (!hasCommand && elapsed > 6000) {
            capturing = false
            resetUtterance(rec)
            listener.onNothingSaid()
        }
    }

    private fun startCapture(now: Long, forced: Boolean) {
        capturing = true
        captureForced = forced
        captureStart = now
        lastVoice = now
        speechFrames = 0
        armedUntil = 0
        listener.onWake()
    }

    private fun clearAudio() {
        chunks.clear()
        chunkSamples = 0
        finalsText = ""
        partialText = ""
    }

    private fun resetUtterance(rec: Recognizer) {
        rec.reset()
        clearAudio()
        lastFinalAt = 0
        lastPartialShown = ""
    }

    companion object {
        const val RATE = 16000
    }
}
