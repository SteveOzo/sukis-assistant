package com.sukis.assistant.voice

import com.sukis.assistant.Prefs
import com.sukis.assistant.net.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Transcribe el comando con Whisper (Groq, gratis y muy preciso en español). */
class Transcriber(private val prefs: Prefs) {
    val available get() = prefs.groqKey.isNotBlank()

    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
        val wav = wav(pcm, sampleRate)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "es")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", "json")
            // Ayuda a Whisper a escribir bien el nombre de la asistente.
            .addFormDataPart("prompt", "Oye Sukis,")
            .build()
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer " + prefs.groqKey)
            .post(body)
            .build()
        return JSONObject(Http.call(req)).optString("text").trim()
    }

    companion object {
        fun wav(pcm: ShortArray, rate: Int): ByteArray {
            val dataLen = pcm.size * 2
            val out = ByteArrayOutputStream(44 + dataLen)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
                .putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(dataLen)
            out.write(h.array())
            val d = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { d.putShort(it) }
            out.write(d.array())
            return out.toByteArray()
        }
    }
}
