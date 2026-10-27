package com.sukis.assistant.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, val body: String, val retryAfterSec: Long? = null) :
    IOException("HTTP $code: ${body.take(300)}")

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-P620) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    fun jsonBody(o: JSONObject): RequestBody = o.toString().toRequestBody(JSON)

    /** Ejecuta la petición y devuelve el cuerpo; lanza [HttpException] si no es 2xx. */
    suspend fun call(req: Request, client: OkHttpClient = this.client): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, body, resp.header("Retry-After")?.toLongOrNull())
            }
            body
        }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        return JSONObject(call(b.build()))
    }

    suspend fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = Request.Builder().url(url).post(jsonBody(body))
        headers.forEach { (k, v) -> b.header(k, v) }
        val text = call(b.build())
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
