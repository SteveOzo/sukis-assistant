package com.sukis.assistant.tools

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.sukis.assistant.net.Http
import okhttp3.Request
import org.json.JSONObject

private val APP_ALIASES = mapOf(
    "ajustes" to listOf("com.android.settings"),
    "configuracion" to listOf("com.android.settings"),
    "camara" to listOf("com.sec.android.app.camera", "com.android.camera"),
    "galeria" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
    "fotos" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d"),
    "reloj" to listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"),
    "alarma" to listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"),
    "calendario" to listOf("com.samsung.android.calendar", "com.google.android.calendar"),
    "navegador" to listOf("com.android.chrome", "com.sec.android.app.sbrowser"),
    "internet" to listOf("com.sec.android.app.sbrowser", "com.android.chrome"),
    "tienda" to listOf("com.android.vending"),
    "play store" to listOf("com.android.vending"),
    "mapas" to listOf("com.google.android.apps.maps"),
    "correo" to listOf("com.google.android.gm"),
    "spotify" to listOf("com.spotify.music"),
    "youtube" to listOf("com.google.android.youtube"),
    "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
    "notas" to listOf("com.samsung.android.app.notes", "com.google.android.keep"),
    "calculadora" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
    "netflix" to listOf("com.netflix.mediaclient"),
)

class OpenAppTool(private val env: ToolEnv) : Tool {
    override val name = "open_app"
    override val description = "Abre una aplicación instalada en la tablet por su nombre (ej. Spotify, YouTube, Netflix, Ajustes, Cámara)."
    override val parameters = params(listOf("name"), "name" to pStr("Nombre de la app, como lo diría el usuario"))

    override suspend fun run(args: JSONObject): String {
        val query = norm(args.optString("name"))
        if (query.isEmpty()) return fail("Falta el nombre de la app")
        val pm = env.context.packageManager

        APP_ALIASES[query]?.forEach { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let {
                env.startActivity(it)
                return ok("Abriendo $pkg")
            }
        }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        var best: Pair<Int, android.content.pm.ResolveInfo>? = null
        for (ri in apps) {
            val label = norm(ri.loadLabel(pm).toString())
            val score = when {
                label == query -> 100
                label.startsWith(query) -> 80
                label.contains(query) -> 60
                query.contains(label) && label.length >= 4 -> 50
                label.split(" ").any { it.length >= 3 && query.split(" ").contains(it) } -> 30
                else -> 0
            }
            if (score > 0 && (best == null || score > best.first)) best = score to ri
        }
        val found = best?.second ?: return fail("No encontré una app llamada '${args.optString("name")}'")
        val intent = pm.getLaunchIntentForPackage(found.activityInfo.packageName)
            ?: return fail("No se puede abrir ${found.loadLabel(pm)}")
        env.startActivity(intent)
        return ok("Abriendo ${found.loadLabel(pm)}")
    }
}

fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
    pm.getPackageInfo(pkg, 0); true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

class YouTubeTool(private val env: ToolEnv) : Tool {
    override val name = "play_youtube"
    override val description = "Busca y reproduce un video en YouTube (música, tutoriales, noticias, recetas…)."
    override val parameters = params(listOf("query"), "query" to pStr("Qué buscar en YouTube"))

    override suspend fun run(args: JSONObject): String {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return fail("Falta la búsqueda")
        val pkg = "com.google.android.youtube"
        val hasApp = isInstalled(env.context.packageManager, pkg)
        val videoId = try {
            val html = Http.call(
                Request.Builder()
                    .url("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                    .header("User-Agent", Http.UA)
                    .header("Accept-Language", "es-EC,es;q=0.9")
                    .build(),
            )
            Regex("\"videoId\":\"([\\w-]{11})\"").find(html)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
        val intent = if (videoId != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        }
        if (hasApp) intent.setPackage(pkg)
        env.startActivity(intent)
        return ok(if (videoId != null) "Reproduciendo el primer resultado de '$q' en YouTube" else "Mostrando resultados de '$q' en YouTube")
    }
}

class MapsTool(private val env: ToolEnv) : Tool {
    override val name = "open_maps"
    override val description = "Muestra un lugar en Google Maps o inicia la navegación hacia él."
    override val parameters = params(
        listOf("destination"),
        "destination" to pStr("Lugar o dirección"),
        "navigate" to pBool("true para iniciar navegación paso a paso"),
    )

    override suspend fun run(args: JSONObject): String {
        val dest = args.optString("destination").trim()
        if (dest.isEmpty()) return fail("Falta el destino")
        val uri = if (args.optBoolean("navigate")) "google.navigation:q=" + Uri.encode(dest)
        else "geo:0,0?q=" + Uri.encode(dest)
        env.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        return ok("Abriendo mapas: $dest")
    }
}

class OpenUrlTool(private val env: ToolEnv) : Tool {
    override val name = "open_website"
    override val description = "Abre una página web en el navegador para que el usuario la vea."
    override val parameters = params(listOf("url"), "url" to pStr("URL completa, con https://"))

    override suspend fun run(args: JSONObject): String {
        var url = args.optString("url").trim()
        if (url.isEmpty()) return fail("Falta la URL")
        if (!url.startsWith("http")) url = "https://$url"
        env.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return ok("Abriendo $url")
    }
}
