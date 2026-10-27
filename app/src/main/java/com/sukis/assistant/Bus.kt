package com.sukis.assistant

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AssistantState(val label: String) {
    OFF("Apagada"),
    LOADING("Preparando…"),
    IDLE("Di \"Oye Sukis\""),
    LISTENING("Te escucho…"),
    THINKING("Pensando…"),
    SPEAKING("Hablando…"),
    ERROR("Error"),
}

data class Exchange(val user: String, val reply: String)

/** Estado compartido entre el servicio y la interfaz. */
object Bus {
    val state = MutableStateFlow(AssistantState.OFF)
    val detail = MutableStateFlow("")
    val lastExchange = MutableStateFlow<Exchange?>(null)
    /** Lo último que oyó el detector de la palabra de activación (para depurar alias). */
    val heard = MutableStateFlow("")

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        Log.i("Sukis", msg)
        val line = "${fmt.format(Date())}  $msg"
        _log.value = (_log.value + line).takeLast(200)
    }

    fun set(s: AssistantState, d: String = "") {
        state.value = s
        detail.value = d
    }
}
