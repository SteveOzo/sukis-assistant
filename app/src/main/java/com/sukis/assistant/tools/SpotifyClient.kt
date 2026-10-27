package com.sukis.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.sukis.assistant.Bus
import com.sukis.assistant.Prefs
import com.sukis.assistant.net.Http
import com.sukis.assistant.net.HttpException
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cliente de la Web API de Spotify (requiere Premium para controlar la reproducción).
 * Autenticación con PKCE: no hace falta "client secret", solo el Client ID de tu app de Spotify.
 */
class SpotifyClient(private val context: Context, private val prefs: Prefs) {

    val isConnected get() = prefs.spotifyRefreshToken.isNotBlank()

    fun authorizeIntent(): Intent? {
        val clientId = prefs.spotifyClientId
        if (clientId.isBlank()) return null
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = b64url(bytes)
        prefs.spotifyVerifier = verifier
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    suspend fun exchangeCode(code: String) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", prefs.spotifyClientId)
            .add("code_verifier", prefs.spotifyVerifier)
            .build()
        saveTokens(JSONObject(Http.call(Request.Builder().url(TOKEN_URL).post(form).build())))
    }

    private suspend fun accessToken(): String {
        if (prefs.spotifyAccessToken.isNotBlank() && System.currentTimeMillis() < prefs.spotifyExpiresAt - 60_000) {
            return prefs.spotifyAccessToken
        }
        if (prefs.spotifyRefreshToken.isBlank()) throw IOException("Spotify no está conectado")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", prefs.spotifyRefreshToken)
            .add("client_id", prefs.spotifyClientId)
            .build()
        saveTokens(JSONObject(Http.call(Request.Builder().url(TOKEN_URL).post(form).build())))
        return prefs.spotifyAccessToken
    }

    private fun saveTokens(j: JSONObject) {
        prefs.spotifyAccessToken = j.getString("access_token")
        j.optStringOrNull("refresh_token")?.let { prefs.spotifyRefreshToken = it }
        prefs.spotifyExpiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
    }

    fun disconnect() {
        prefs.spotifyAccessToken = ""
        prefs.spotifyRefreshToken = ""
        prefs.spotifyExpiresAt = 0
    }

    private suspend fun api(method: String, path: String, body: JSONObject? = null): String {
        val b = Request.Builder().url("https://api.spotify.com/v1$path")
            .header("Authorization", "Bearer " + accessToken())
        when (method) {
            "GET" -> b.get()
            "PUT" -> b.put((body?.toString() ?: "").toRequestBody(Http.JSON))
            "POST" -> b.post((body?.toString() ?: "").toRequestBody(Http.JSON))
        }
        return Http.call(b.build())
    }

    data class Found(val uri: String, val description: String, val isTrack: Boolean)

    suspend fun search(query: String, type: String): Found? {
        val t = if (type in listOf("track", "artist", "album", "playlist")) type else "track"
        val json = JSONObject(api("GET", "/search?type=$t&limit=5&market=from_token&q=" + Uri.encode(query)))
        val items = json.optJSONObject(t + "s")?.optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue // la API a veces devuelve null en playlists
            val name = it.optString("name")
            val desc = when (t) {
                "track", "album" -> "$name de " + artists(it.optJSONArray("artists"))
                "playlist" -> "la playlist $name"
                else -> name
            }
            return Found(it.getString("uri"), desc, t == "track")
        }
        return null
    }

    private fun artists(arr: JSONArray?): String {
        if (arr == null) return ""
        return (0 until arr.length()).joinToString(", ") { arr.getJSONObject(it).optString("name") }
    }

    /** Encuentra el dispositivo de Spotify de la tablet (abriendo la app si hace falta). */
    private suspend fun deviceId(): String? {
        fun pick(devs: JSONArray): String? {
            var fallback: String? = null
            for (i in 0 until devs.length()) {
                val d = devs.getJSONObject(i)
                val n = d.optString("name").lowercase()
                if (d.optString("type").equals("Tablet", true) ||
                    n.contains(Build.MODEL.lowercase()) || n.contains("tab")
                ) return d.optString("id")
                if (fallback == null || d.optBoolean("is_active")) fallback = d.optString("id")
            }
            return fallback
        }

        val first = JSONObject(api("GET", "/me/player/devices")).optJSONArray("devices") ?: JSONArray()
        pick(first)?.let { return it }

        // Spotify solo aparece como dispositivo cuando la app está abierta.
        context.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
        repeat(10) {
            delay(1000)
            val devs = JSONObject(api("GET", "/me/player/devices")).optJSONArray("devices") ?: JSONArray()
            pick(devs)?.let { return it }
        }
        return null
    }

    suspend fun play(query: String, type: String, shuffle: Boolean): String {
        val found = search(query, type) ?: return fail("No encontré '$query' en Spotify")
        val device = deviceId() ?: return fail("No encontré la tablet como dispositivo de Spotify. Abre Spotify una vez e inténtalo de nuevo.")
        val body = if (found.isTrack) JSONObject().put("uris", JSONArray().put(found.uri))
        else JSONObject().put("context_uri", found.uri)
        try {
            api("PUT", "/me/player/play?device_id=$device", body)
        } catch (e: HttpException) {
            if (e.code != 404) throw e
            api("PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(device)).put("play", false))
            delay(800)
            api("PUT", "/me/player/play?device_id=$device", body)
        }
        if (shuffle) runCatching { api("PUT", "/me/player/shuffle?state=true&device_id=$device") }
        Bus.log("Spotify: ${found.description}")
        return ok("Reproduciendo ${found.description}")
    }

    suspend fun nowPlaying(): String {
        val text = api("GET", "/me/player/currently-playing")
        if (text.isBlank()) return ok("No se está reproduciendo nada")
        val j = JSONObject(text)
        val item = j.optJSONObject("item") ?: return ok("No se está reproduciendo nada")
        val state = if (j.optBoolean("is_playing")) "Sonando" else "En pausa"
        return ok("$state: ${item.optString("name")} de ${artists(item.optJSONArray("artists"))}")
    }

    companion object {
        const val REDIRECT_URI = "sukis://spotify-callback"
        const val SPOTIFY_PKG = "com.spotify.music"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing playlist-read-private user-library-read"

        private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
