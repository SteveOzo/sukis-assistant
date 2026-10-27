package com.sukis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sukis.assistant.AssistantState
import kotlin.math.min
import kotlin.math.sin

/** Paleta y helpers para construir la interfaz por código (sin XML). */
object Theme {
    val BG = Color.parseColor("#120C2E")
    val CARD = Color.parseColor("#1F1748")
    val TEXT = Color.parseColor("#F2EEFF")
    val MUTED = Color.parseColor("#A79FD0")
    val ACCENT = Color.parseColor("#9C7BFF")
    val OK = Color.parseColor("#4ADE80")
    val WARN = Color.parseColor("#FBBF24")

    fun colorFor(s: AssistantState) = when (s) {
        AssistantState.OFF -> Color.parseColor("#4B4670")
        AssistantState.LOADING -> WARN
        AssistantState.IDLE -> ACCENT
        AssistantState.LISTENING -> Color.parseColor("#38BDF8")
        AssistantState.THINKING -> Color.parseColor("#F472B6")
        AssistantState.SPEAKING -> OK
        AssistantState.ERROR -> Color.parseColor("#F87171")
    }
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.text(s: String = "", size: Float = 16f, color: Int = Theme.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.button(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(if (primary) Color.WHITE else Theme.TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(if (primary) Theme.ACCENT else Theme.CARD)
            if (!primary) setStroke(dp(1), Color.parseColor("#3A3170"))
        }
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setOnClickListener { onClick() }
    }

fun Context.input(hint: String, value: String = "", secret: Boolean = false, multiline: Boolean = false): EditText =
    EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Theme.TEXT)
        setHintTextColor(Theme.MUTED)
        textSize = 15f
        inputType = when {
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) { minLines = 4; gravity = Gravity.TOP }
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Theme.CARD)
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Theme.CARD)
    }
    setPadding(dp(20), dp(16), dp(20), dp(16))
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top }

/** El "orbe" animado que muestra el estado de Sukis. */
class OrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = Theme.ACCENT
    private var phase = 0f
    private var energy = 0.15f
    private val anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun setState(s: AssistantState) {
        color = Theme.colorFor(s)
        energy = when (s) {
            AssistantState.LISTENING -> 0.6f
            AssistantState.SPEAKING -> 0.45f
            AssistantState.THINKING -> 0.35f
            AssistantState.OFF -> 0f
            else -> 0.15f
        }
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.62f
        val pulse = 1f + energy * 0.12f * sin(phase.toDouble()).toFloat()
        for (ring in 3 downTo 1) {
            val r = base * pulse * (1f + ring * 0.16f * (0.5f + energy))
            paint.shader = null
            paint.color = Color.argb((28 + (3 - ring) * 14), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(cx, cy, r, paint)
        }
        val r = base * pulse
        paint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.3f, r * 1.4f,
            intArrayOf(Color.WHITE, color, Color.argb(255, Color.red(color) / 2, Color.green(color) / 2, Color.blue(color) / 2)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)
    }
}
