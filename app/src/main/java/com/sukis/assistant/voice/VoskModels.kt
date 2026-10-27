package com.sukis.assistant.voice

import android.content.Context
import com.sukis.assistant.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Descarga (una sola vez, ~40 MB) el modelo offline en español para la palabra de activación. */
object VoskModels {
    private const val NAME = "vosk-model-small-es-0.42"
    private const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"

    fun dir(context: Context) = File(context.filesDir, NAME)
    fun isReady(context: Context) = File(dir(context), "am/final.mdl").exists() || File(dir(context), "conf/model.conf").exists()

    suspend fun ensure(context: Context, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val target = dir(context)
        if (isReady(context)) return@withContext target
        val tmp = File(context.filesDir, "$NAME.zip.part")
        Http.client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("No se pudo descargar el modelo de voz (HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("Descarga vacía")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
            }
        }
        val root = context.filesDir.canonicalFile
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("Zip inválido")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        tmp.delete()
        if (!isReady(context)) throw IOException("El modelo descargado está incompleto")
        target
    }
}
