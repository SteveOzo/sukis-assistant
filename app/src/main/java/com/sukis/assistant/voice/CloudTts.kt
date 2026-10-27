package com.sukis.assistant.voice

import com.sukis.assistant.net.Http
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

/** Convierte "es-EC-AndreaNeural" al nombre largo que espera el servicio. */
private fun longVoiceName(short: String): String {
    val m = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(short) ?: return short
    val (lang, region, name) = m.destructured
    return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
}

/** Voz neural gratuita de Microsoft Edge (sin cuenta). Devuelve MP3. */
object EdgeTts {
    @Volatile private var clockSkewSec = 0L

    suspend fun synthesize(text: String, voice: String, rate: String = "+0%"): ByteArray = withTimeout(20_000) {
        try {
            attempt(text, voice, rate)
        } catch (e: SkewException) {
            // El token depende de la hora; si el reloj está desfasado se corrige con la hora del servidor.
            attempt(text, voice, rate)
        }
    }

    private class SkewException : IOException("Ajuste de reloj")

    private suspend fun attempt(text: String, voice: String, rate: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            val now = System.currentTimeMillis() / 1000 + clockSkewSec
            val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=${EdgeToken.TRUSTED_CLIENT_TOKEN}" +
                "&ConnectionId=${uuid()}" +
                "&Sec-MS-GEC=${EdgeToken.generate(now)}" +
                "&Sec-MS-GEC-Version=${EdgeToken.SEC_MS_GEC_VERSION}"
            val muid = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
            val major = EdgeToken.CHROMIUM_MAJOR
            val req = Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0",
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=$muid;")
                .build()

            val audio = ByteArrayOutputStream()
            val ws = Http.client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ts = jsDate()
                    webSocket.send(
                        "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\"," +
                            "\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n",
                    )
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                        "<voice name='${longVoiceName(voice)}'><prosody pitch='+0Hz' rate='$rate' volume='+0%'>" +
                        escapeXml(text) + "</prosody></voice></speak>"
                    webSocket.send(
                        "X-RequestId:${uuid()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml",
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        if (cont.isActive) {
                            if (audio.size() > 0) cont.resume(audio.toByteArray())
                            else cont.resumeWithException(IOException("Edge TTS no devolvió audio"))
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size < 2) return
                    val headerLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                    if (headerLen + 2 > b.size) return
                    val header = String(b, 2, headerLen, Charsets.UTF_8)
                    if (header.contains("Path:audio")) audio.write(b, 2 + headerLen, b.size - 2 - headerLen)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!cont.isActive) return
                    val serverDate = response?.header("Date")
                    if (response?.code == 403 && serverDate != null) {
                        val server = runCatching {
                            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(serverDate)?.time
                        }.getOrNull()
                        if (server != null) {
                            val skew = server / 1000 - System.currentTimeMillis() / 1000
                            if (kotlin.math.abs(skew - clockSkewSec) > 60) {
                                clockSkewSec = skew
                                cont.resumeWithException(SkewException())
                                return
                            }
                        }
                    }
                    cont.resumeWithException(IOException("Edge TTS: ${response?.code ?: ""} ${t.message}"))
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }

    private fun uuid() = UUID.randomUUID().toString().replace("-", "")

    private fun jsDate(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }
}

/** Servicio oficial Azure Speech (capa gratuita F0: 500 mil caracteres/mes). Misma voz es-EC-AndreaNeural. */
object AzureTts {
    suspend fun synthesize(text: String, voice: String, key: String, region: String): ByteArray {
        val ssml = "<speak version='1.0' xml:lang='es-EC'><voice name='$voice'>${escapeXml(text)}</voice></speak>"
        val req = Request.Builder()
            .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key)
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            .header("User-Agent", "sukis")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Azure TTS HTTP ${r.code}")
                r.body?.bytes() ?: throw IOException("Azure TTS sin audio")
            }
        }
    }
}
