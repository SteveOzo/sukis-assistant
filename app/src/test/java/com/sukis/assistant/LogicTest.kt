package com.sukis.assistant

import com.sukis.assistant.brain.Agent
import com.sukis.assistant.brain.LlmRouter
import com.sukis.assistant.voice.EdgeToken
import com.sukis.assistant.voice.Transcriber
import com.sukis.assistant.voice.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val wake = WakeWord(Prefs.DEFAULT_WAKE_ALIASES)

    @Test fun detectaVariantesDeSukis() {
        listOf(
            "oye sukis abre spotify", "oye suki pon música", "oye zuquis", "hey suquís qué hora es",
            "oye su quis dime el clima", "Oye, Sukis.",
        ).forEach { assertTrue(it, wake.matches(it)) }
    }

    @Test fun ignoraFrasesNormales() {
        listOf("oye susana", "la sucursal está cerrada", "su casa es bonita", "quiero sushi", "").forEach {
            assertFalse(it, wake.matches(it))
        }
    }

    @Test fun extraeElComando() {
        assertEquals("abre spotify y reproduce candy de plan b", wake.commandAfter("Oye Sukis, abre Spotify y reproduce Candy de Plan B"))
        assertEquals("", wake.commandAfter("oye sukis"))
        assertEquals("pon musica", wake.commandAfter("oye su quis pon música"))
    }

    @Test fun aliasPersonalizado() {
        assertTrue(WakeWord("chuky").matches("oye chuky"))
    }

    @Test fun parseaCadenaDeModelos() {
        val c = LlmRouter.parseChain("groq:openai/gpt-oss-120b\n# comentario\n\ngemini:gemini-flash-latest\ndesconocido:x\nopenrouter:auto-free")
        assertEquals(listOf("groq:openai/gpt-oss-120b", "gemini:gemini-flash-latest", "openrouter:auto-free"), c.map { it.label })
    }

    @Test fun cadenaPorDefectoEsValida() {
        assertEquals(6, LlmRouter.parseChain(Prefs.DEFAULT_MODEL_CHAIN).size)
    }

    @Test fun limpiaTextoParaVoz() {
        assertEquals(
            "Ganó dos a uno. Fuente: El Universo",
            Agent.cleanForSpeech("**Ganó** dos a uno. Fuente: [El Universo](https://eluniverso.com) https://x.com/a"),
        )
        assertEquals("Hola", Agent.cleanForSpeech("<think>pensando…</think>Hola"))
    }

    @Test fun cabeceraWav() {
        val wav = Transcriber.wav(ShortArray(16000), 16000)
        assertEquals(44 + 32000, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
    }

    @Test fun tokenEdgeTieneFormatoYCambiaCada5Min() {
        val t = 1_761_600_000L
        val a = EdgeToken.generate(t)
        assertTrue(a, Regex("^[0-9A-F]{16}-[0-9A-F]{64}$").matches(a))
        assertEquals(a, EdgeToken.generate(t - (t + 11644473600L) % 300 + 299))
        assertNotEquals(a, EdgeToken.generate(t + 300))
    }
}
