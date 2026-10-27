package com.sukis.assistant.tools

import android.net.Uri
import com.sukis.assistant.net.Http
import org.json.JSONArray
import org.json.JSONObject

/** Clima con Open-Meteo (gratis, sin API key). */
class WeatherTool(private val env: ToolEnv) : Tool {
    override val name = "get_weather"
    override val description = "Clima actual y pronóstico de los próximos días para una ciudad (por defecto la ciudad del usuario)."
    override val parameters = params(
        emptyList(),
        "location" to pStr("Ciudad, opcional"),
        "days" to pInt("Días de pronóstico (1-7), por defecto 3"),
    )

    override suspend fun run(args: JSONObject): String {
        val loc = args.optStringOrNull("location") ?: env.prefs.city
        val days = args.optInt("days", 3).coerceIn(1, 7)
        val geo = Http.getJson(
            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name=" + Uri.encode(loc),
        ).optJSONArray("results")?.optJSONObject(0) ?: return fail("No encontré la ciudad '$loc'")
        val lat = geo.getDouble("latitude")
        val lon = geo.getDouble("longitude")
        val w = Http.getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=$days" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max",
        )
        val cur = w.getJSONObject("current")
        val out = JSONObject()
            .put("place", geo.optString("name") + ", " + geo.optString("country"))
            .put(
                "now",
                JSONObject()
                    .put("temp_c", cur.optDouble("temperature_2m"))
                    .put("feels_like_c", cur.optDouble("apparent_temperature"))
                    .put("humidity_pct", cur.optInt("relative_humidity_2m"))
                    .put("wind_kmh", cur.optDouble("wind_speed_10m"))
                    .put("precip_mm", cur.optDouble("precipitation"))
                    .put("sky", describe(cur.optInt("weather_code"))),
            )
        val d = w.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val forecast = JSONArray()
        for (i in 0 until dates.length()) {
            forecast.put(
                JSONObject()
                    .put("date", dates.getString(i))
                    .put("sky", describe(d.getJSONArray("weather_code").optInt(i)))
                    .put("max_c", d.getJSONArray("temperature_2m_max").optDouble(i))
                    .put("min_c", d.getJSONArray("temperature_2m_min").optDouble(i))
                    .put("rain_prob_pct", d.getJSONArray("precipitation_probability_max").optInt(i))
                    .put("uv_max", d.getJSONArray("uv_index_max").optDouble(i)),
            )
        }
        return out.put("forecast", forecast).toString()
    }

    private fun describe(code: Int) = when (code) {
        0 -> "despejado"
        1 -> "mayormente despejado"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "neblina"
        51, 53, 55 -> "llovizna"
        56, 57 -> "llovizna helada"
        61 -> "lluvia ligera"
        63 -> "lluvia"
        65 -> "lluvia fuerte"
        66, 67 -> "lluvia helada"
        71, 73, 75, 77 -> "nieve"
        80, 81 -> "chubascos"
        82 -> "chubascos fuertes"
        85, 86 -> "chubascos de nieve"
        95 -> "tormenta"
        96, 99 -> "tormenta con granizo"
        else -> "condición $code"
    }
}
