package com.example.winarm

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*

object C {
    val bgTop = 0xFF0A2A66.toInt(); val bgBottom = 0xFF050B1E.toInt()
    val card = 0xFF12284F.toInt(); val card2 = 0xFF1C3A6E.toInt(); val nav = 0xFF081533.toInt()
    val accent = 0xFF1E6FEB.toInt(); val accent2 = 0xFF5AA2FF.toInt()
    val text = 0xFFFFFFFF.toInt(); val sub = 0xFFA9B8D6.toInt(); val green = 0xFF3DDC84.toInt()
    val red = 0xFFE5453A.toInt(); val sheet = 0xFFF4F7FC.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.round(color: Int, r: Int = 14) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(r).toFloat()
}

fun Context.tv(t: String, sz: Float = 14f, col: Int = C.text, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = sz; setTextColor(col)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.vbox(pad: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}

fun Context.card(pad: Int = 14) = vbox(pad).apply { background = round(C.card, 16) }

fun Context.btn(t: String, filled: Boolean = true, f: () -> Unit) = TextView(this).apply {
    text = t; textSize = 14f; gravity = Gravity.CENTER; setTextColor(C.text)
    background = round(if (filled) C.accent else C.card2, 12)
    setPadding(dp(14), dp(12), dp(14), dp(12)); setOnClickListener { f() }
}

fun LinearLayout.add(v: View, w: Int = -1, h: Int = -2, wt: Float = 0f,
                     t: Int = 0, l: Int = 0, r: Int = 0, b: Int = 0): View {
    addView(v, LinearLayout.LayoutParams(w, h, wt).apply {
        setMargins(context.dp(l), context.dp(t), context.dp(r), context.dp(b))
    })
    return v
}

/** โลโก้สี่เหลี่ยม 4 ช่อง */
class Logo(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B8CFF.toInt() }
    override fun onDraw(cv: Canvas) {
        val g = width * 0.05f; val s = (width - g) / 2
        for (i in 0..1) for (j in 0..1) {
            val x = i * (s + g); val y = j * (s + g)
            cv.drawRoundRect(x, y, x + s, y + s, 4f, 4f, p)
        }
    }
}
