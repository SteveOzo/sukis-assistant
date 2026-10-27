package com.sukis.assistant.tools

import com.sukis.assistant.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** Listas (compras, tareas…) guardadas en la tablet. */
class ListsTool(private val prefs: Prefs) : Tool {
    override val name = "lists"
    override val description = "Gestiona listas guardadas como la lista de compras o de tareas: agregar, ver, quitar o vaciar."
    override val parameters = params(
        listOf("action", "list"),
        "action" to pStr("Acción", listOf("add", "show", "remove", "clear", "show_all_lists")),
        "list" to pStr("Nombre de la lista, ej. 'compras', 'tareas'"),
        "items" to pStrArray("Elementos a agregar o quitar"),
    )

    private fun load(): JSONObject = JSONObject(prefs.sp.getString("lists", "{}") ?: "{}")
    private fun save(o: JSONObject) = prefs.sp.edit().putString("lists", o.toString()).apply()

    override suspend fun run(args: JSONObject): String {
        val all = load()
        val listName = norm(args.optString("list")).ifEmpty { "compras" }
        val list = all.optJSONArray(listName) ?: JSONArray()
        val items = args.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
        val current = (0 until list.length()).map { list.optString(it) }.toMutableList()
        return when (args.optString("action")) {
            "add" -> {
                if (items.isEmpty()) return fail("No hay elementos para agregar")
                items.forEach { if (current.none { c -> norm(c) == norm(it) }) current += it }
                all.put(listName, JSONArray(current)); save(all)
                ok("Lista $listName: ${current.joinToString(", ")}")
            }
            "remove" -> {
                current.removeAll { c -> items.any { norm(it) == norm(c) || norm(c).contains(norm(it)) } }
                all.put(listName, JSONArray(current)); save(all)
                ok("Lista $listName: ${current.joinToString(", ").ifEmpty { "vacía" }}")
            }
            "clear" -> {
                all.remove(listName); save(all)
                ok("Lista $listName vaciada")
            }
            "show_all_lists" -> ok(all.keys().asSequence().joinToString { "$it (${all.getJSONArray(it).length()})" }.ifEmpty { "No hay listas" })
            else -> ok(if (current.isEmpty()) "La lista $listName está vacía" else "Lista $listName: ${current.joinToString(", ")}")
        }
    }
}

/** Datos que el usuario le pide a Sukis que recuerde ("mi equipo es Independiente del Valle"). */
class MemoryTool(private val prefs: Prefs) : Tool {
    override val name = "memory"
    override val description = "Guarda u olvida datos personales duraderos del usuario o del hogar (gustos, nombres, cumpleaños, preferencias)."
    override val parameters = params(
        listOf("action", "fact"),
        "action" to pStr("Acción", listOf("remember", "forget")),
        "fact" to pStr("El dato a recordar, o palabras clave del dato a olvidar"),
    )

    override suspend fun run(args: JSONObject): String {
        val fact = args.optString("fact").trim()
        if (fact.isEmpty()) return fail("Falta el dato")
        val facts = facts(prefs).toMutableList()
        return if (args.optString("action") == "forget") {
            val before = facts.size
            facts.removeAll { norm(it).contains(norm(fact)) }
            store(facts)
            ok(if (facts.size < before) "Olvidado" else "No tenía guardado eso")
        } else {
            facts += fact
            store(facts.takeLast(60))
            ok("Lo recordaré")
        }
    }

    private fun store(f: List<String>) = prefs.sp.edit().putString("facts", JSONArray(f).toString()).apply()

    companion object {
        fun facts(prefs: Prefs): List<String> {
            val a = JSONArray(prefs.sp.getString("facts", "[]") ?: "[]")
            return (0 until a.length()).map { a.optString(it) }
        }
    }
}
