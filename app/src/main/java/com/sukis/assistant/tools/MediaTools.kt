package com.sukis.assistant.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent
import org.json.JSONObject

class PlayMusicTool(private val env: ToolEnv, private val spotify: SpotifyClient) : Tool {
    override val name = "play_music"
    override val description = "Reproduce música en Spotify: una canción, un artista, un álbum o una playlist. " +
        "Para una canción concreta incluye título y artista en query (ej. 'Candy Plan B')."
    override val parameters = params(
        listOf("query", "type"),
        "query" to pStr("Qué buscar en Spotify"),
        "type" to pStr("Tipo de contenido", listOf("track", "artist", "album", "playlist")),
        "shuffle" to pBool("Activar modo aleatorio (útil para artistas o playlists)"),
    )

    override suspend fun run(args: JSONObject): String {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return fail("Falta qué reproducir")
        val type = args.optString("type", "track")
        if (spotify.isConnected) {
            try {
                return spotify.play(q, type, args.optBoolean("shuffle"))
            } catch (e: Exception) {
                com.sukis.assistant.Bus.log("Spotify API falló, uso el intent: ${e.message}")
            }
        }
        // Sin Web API: Spotify soporta "reproducir desde búsqueda" de Android.
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(SpotifyClient.SPOTIFY_PKG)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(
                MediaStore.EXTRA_MEDIA_FOCUS,
                when (type) {
                    "artist" -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
                    "album" -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
                    "playlist" -> "vnd.android.cursor.item/playlist"
                    else -> "vnd.android.cursor.item/audio"
                },
            )
        if (!isInstalled(env.context.packageManager, SpotifyClient.SPOTIFY_PKG)) return fail("Spotify no está instalado")
        env.startActivity(intent)
        return ok("Pedí a Spotify que reproduzca '$q'")
    }
}

class MediaControlTool(private val env: ToolEnv, private val spotify: SpotifyClient) : Tool {
    override val name = "media_control"
    override val description = "Controla la música o el video que suena: pausar, continuar, siguiente, anterior, detener, o saber qué canción suena."
    override val parameters = params(
        listOf("action"),
        "action" to pStr("Acción", listOf("pause", "resume", "next", "previous", "stop", "what_is_playing")),
    )

    override suspend fun run(args: JSONObject): String {
        val action = args.optString("action")
        if (action == "what_is_playing") {
            return if (spotify.isConnected) runCatching { spotify.nowPlaying() }.getOrElse { fail(it.message ?: "error") }
            else fail("Para saber qué suena, conecta Spotify en Ajustes")
        }
        val key = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return fail("Acción desconocida: $action")
        }
        val am = env.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ok("Hecho: $action")
    }
}

class VolumeTool(private val env: ToolEnv) : Tool {
    override val name = "set_volume"
    override val description = "Cambia el volumen multimedia de la tablet. Usa level (0-100) o change (up/down/mute)."
    override val parameters = params(
        emptyList(),
        "level" to pInt("Volumen en porcentaje 0-100"),
        "change" to pStr("Cambio relativo", listOf("up", "down", "mute")),
    )

    override suspend fun run(args: JSONObject): String {
        val am = env.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = when {
            args.has("level") -> (args.optInt("level").coerceIn(0, 100) * max + 50) / 100
            args.optString("change") == "up" -> (cur + (max / 5).coerceAtLeast(1)).coerceAtMost(max)
            args.optString("change") == "down" -> (cur - (max / 5).coerceAtLeast(1)).coerceAtLeast(0)
            args.optString("change") == "mute" -> 0
            else -> return fail("Indica level o change")
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return ok("Volumen al ${target * 100 / max}%")
    }
}
