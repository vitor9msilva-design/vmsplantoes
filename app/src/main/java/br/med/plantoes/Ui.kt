package br.med.plantoes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** Peças visuais da interface: texto, cartões, etiquetas e linhas de ajuste, todas no mesmo estilo. */
class Ui(val c: Context, val pal: Pal) {
    fun dp(v: Int) = c.dp(v)
    fun dpf(v: Float) = c.dpf(v)

    fun fonte(peso: Int): Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, peso, false)
    else when {
        peso >= 600 -> Typeface.create("sans-serif", Typeface.BOLD)
        peso >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** Texto. `num` usa algarismos de largura fixa, para horários e contagens não "pularem". */
    fun tv(t: CharSequence, sp: Float = 15f, cor: Int = pal.ink, peso: Int = 400, num: Boolean = false) = TextView(c).apply {
        text = t; textSize = sp; setTextColor(cor); typeface = fonte(peso)
        if (sp >= 24f) letterSpacing = -0.02f
        if (num) fontFeatureSettings = "tnum"
        includeFontPadding = false
        setLineSpacing(0f, if (sp >= 24f) 1.0f else 1.15f)
    }

    fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, peso: Float = 0f) =
        LinearLayout.LayoutParams(w, h, peso).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end)
        }

    fun col(h: Int = 0, v: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(h), dp(v), dp(h), dp(v))
    }

    fun lin(gravidade: Int = Gravity.CENTER_VERTICAL) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = gravidade
    }

    fun fundo(cor: Int, raio: Float = 16f, borda: Int? = null) = GradientDrawable().apply {
        setColor(cor); cornerRadius = dpf(raio)
        if (borda != null) setStroke(dp(1), borda)
    }

    /** Cartão branco com borda fina e efeito de toque. */
    fun card(cor: Int = pal.surface, borda: Int = pal.line, raio: Float = 16f) = MaterialCardView(c).apply {
        radius = dpf(raio); cardElevation = 0f; strokeWidth = dp(1); strokeColor = borda
        setCardBackgroundColor(cor); layoutParams = lp(bottom = 10)
    }

    /** Etiqueta arredondada (ex.: "12 h", "Hoje", "Choque de horário"). */
    fun pill(t: String, texto: Int, fundo: Int = ColorUtils.setAlphaComponent(texto, 28), sp: Float = 12f) =
        tv(t, sp, texto, 600).apply {
            background = fundo(fundo, 20f); setPadding(dp(9), dp(4), dp(9), dp(4))
        }

    fun ponto(cor: Int, tam: Int = 10) = View(c).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cor) }
        layoutParams = LinearLayout.LayoutParams(dp(tam), dp(tam))
    }

    /** Título de tela: data pequena em cima e nome grande. */
    fun cabecalho(titulo: String, sub: String? = null): View {
        val v = col()
        v.addView(tv(titulo, 30f, pal.ink, 700))
        if (sub != null) v.addView(tv(sub, 14f, pal.mute, 400), lp(top = 6))
        v.layoutParams = lp(top = 8, bottom = 18)
        return v
    }

    fun secao(t: String, direita: String? = null): View {
        val r = lin()
        r.addView(tv(t, 17f, pal.ink, 600), lp(0, WRAP, peso = 1f))
        if (direita != null) r.addView(tv(direita, 13f, pal.mute, 500, num = true))
        r.layoutParams = lp(top = 20, bottom = 10)
        return r
    }

    private fun toqueFundo(): Int {
        val tvv = TypedValue()
        c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tvv, true)
        return tvv.resourceId
    }

    private fun toqueBorda(): Int {
        val tvv = TypedValue()
        c.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tvv, true)
        return tvv.resourceId
    }

    fun icone(res: Int, desc: String, cor: Int = pal.mute, onClick: () -> Unit) = ImageButton(c).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(cor)
        setBackgroundResource(toqueBorda()); contentDescription = desc
        setOnClickListener { onClick() }
    }

    /** Linha de ajuste: título, descrição e algo à direita (seta, chave liga/desliga). */
    fun linha(titulo: String, sub: String? = null, subCor: Int = pal.mute, direita: View? = null, onClick: (() -> Unit)? = null): View {
        val r = lin()
        r.setPadding(dp(16), dp(14), dp(12), dp(14))
        val t = col()
        t.addView(tv(titulo, 16f, pal.ink, 500))
        if (sub != null) t.addView(tv(sub, 13f, subCor, 400), lp(top = 3))
        r.addView(t, lp(0, WRAP, peso = 1f))
        r.addView(direita ?: tv("›", 22f, pal.mute, 400).apply { setPadding(dp(8), 0, dp(4), 0) })
        if (onClick != null) { r.setBackgroundResource(toqueFundo()); r.setOnClickListener { onClick() } }
        r.minimumHeight = dp(56)
        return r
    }

    fun divisor(inicio: Int = 16) = View(c).apply {
        setBackgroundColor(pal.line)
        layoutParams = lp(h = 1, start = inicio)
    }

    /** Agrupa linhas num cartão, com divisores entre elas. */
    fun grupo(vararg linhas: View): View {
        val card = card()
        val v = col()
        linhas.forEachIndexed { i, l -> if (i > 0) v.addView(divisor()); v.addView(l) }
        card.addView(v)
        return card
    }
}
