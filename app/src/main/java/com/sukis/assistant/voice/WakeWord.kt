package com.sukis.assistant.voice

import com.sukis.assistant.tools.norm

/**
 * Detecta "Oye Sukis" en el texto del reconocedor offline. Como "Sukis" no es una palabra del
 * diccionario, el reconocedor puede escribirlo de muchas formas ("suki", "zuquis", "su quis"…);
 * por eso se compara una versión fonética y se aceptan alias configurables.
 */
class WakeWord(aliasesCsv: String) {
    private val targets: Set<String> = (BASE + aliasesCsv.split(',', ';', '\n'))
        .map { phonetic(norm(it).replace(" ", "")) }
        .filter { it.length >= 3 }
        .toSet()

    /** Índice (en palabras) donde termina la palabra de activación, o -1 si no aparece. */
    fun find(text: String): Int {
        val words = norm(text).split(' ').filter { it.isNotEmpty() }
        for (i in words.indices) {
            if (phonetic(words[i]) in targets) return i + 1
            if (i + 1 < words.size && phonetic(words[i] + words[i + 1]) in targets) return i + 2
        }
        return -1
    }

    fun matches(text: String) = find(text) >= 0

    /** Lo que se dijo después de la palabra de activación. */
    fun commandAfter(text: String): String {
        val words = norm(text).split(' ').filter { it.isNotEmpty() }
        val end = find(text)
        return if (end < 0) "" else words.drop(end).joinToString(" ")
    }

    companion object {
        private val BASE = listOf("sukis", "suki")

        fun phonetic(w: String): String {
            var s = w.lowercase()
            s = s.replace("qu", "k").replace(Regex("c(?=[aou])"), "k").replace("c", "s")
                .replace("z", "s").replace("y", "i").replace("h", "")
                .replace(Regex("(.)\\1+"), "$1")
            return s
        }
    }
}
