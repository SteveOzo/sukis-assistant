package com.sukis.assistant.tools

import android.text.Html
import com.sukis.assistant.Bus
import com.sukis.assistant.net.Http
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

class WebSearchTool(private val env: ToolEnv) : Tool {
    override val name = "web_search"
    override val description = "Busca información actual en internet: resultados deportivos, noticias, horarios, precios, " +
        "datos que cambian o que no sabes con seguridad. Devuelve fragmentos de páginas con su URL."
    override val parameters = params(
        listOf("query"),
        "query" to pStr("Búsqueda, idealmente con palabras clave y en español"),
        "recency" to pStr("Limitar a resultados recientes", listOf("day", "week", "month", "year", "any")),
        "news" to pBool("true para buscar en noticias (deportes, actualidad)"),
    )

    override suspend fun run(args: JSONObject): String {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return fail("Falta la búsqueda")
        val recency = args.optString("recency", "any")
        val news = args.optBoolean("news")
        if (env.prefs.tavilyKey.isNotBlank()) {
            try {
                return tavily(q, recency, news)
            } catch (e: Exception) {
                Bus.log("Tavily falló: ${e.message}")
            }
        }
        return try {
            duckDuckGo(q, recency)
        } catch (e: Exception) {
            fail("La búsqueda falló: ${e.message}")
        }
    }

    private suspend fun tavily(q: String, recency: String, news: Boolean): String {
        val body = JSONObject()
            .put("query", q)
            .put("topic", if (news) "news" else "general")
            .put("search_depth", "basic")
            .put("max_results", 6)
            .put("include_answer", true)
        if (recency != "any") body.put("time_range", recency)
        val j = Http.postJson(
            "https://api.tavily.com/search", body,
            mapOf("Authorization" to "Bearer " + env.prefs.tavilyKey),
        )
        val out = JSONObject()
        j.optStringOrNull("answer")?.let { out.put("summary", it) }
        val results = JSONArray()
        val arr = j.optJSONArray("results") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            results.put(
                JSONObject()
                    .put("title", r.optString("title"))
                    .put("url", r.optString("url"))
                    .put("date", r.optString("published_date", ""))
                    .put("content", r.optString("content").take(700)),
            )
        }
        return out.put("results", results).toString()
    }

    private suspend fun duckDuckGo(q: String, recency: String): String {
        val form = FormBody.Builder().add("q", q).add("kl", "es-es")
        when (recency) {
            "day" -> form.add("df", "d")
            "week" -> form.add("df", "w")
            "month" -> form.add("df", "m")
            "year" -> form.add("df", "y")
        }
        val html = Http.call(
            Request.Builder().url("https://html.duckduckgo.com/html/")
                .header("User-Agent", Http.UA)
                .post(form.build()).build(),
        )
        val links = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val results = JSONArray()
        links.take(6).forEachIndexed { i, m ->
            var url = Html.fromHtml(m.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString()
            Regex("uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            results.put(
                JSONObject()
                    .put("title", stripTags(m.groupValues[2]))
                    .put("url", url)
                    .put("content", snippets.getOrNull(i)?.let { stripTags(it.groupValues[1]) } ?: ""),
            )
        }
        if (results.length() == 0) return fail("Sin resultados")
        return JSONObject().put("results", results).toString()
    }
}

class FetchPageTool : Tool {
    override val name = "read_webpage"
    override val description = "Descarga una página web y devuelve su texto, para leer el detalle de un resultado de búsqueda."
    override val parameters = params(listOf("url"), "url" to pStr("URL a leer"))

    override suspend fun run(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (!url.startsWith("http")) return fail("URL inválida")
        val html = Http.call(
            Request.Builder().url(url).header("User-Agent", Http.UA).header("Accept-Language", "es-EC,es;q=0.9").build(),
        )
        val text = stripTags(
            html.replace(Regex("<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " "),
        )
        return JSONObject().put("url", url).put("text", text.take(6000)).toString()
    }
}

fun stripTags(html: String): String =
    Html.fromHtml(html.replace(Regex("<[^>]+>"), " "), Html.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("\\s+"), " ").trim()
