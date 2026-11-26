package br.med.plantoes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private val DIAS = arrayOf("D", "S", "T", "Q", "Q", "S", "S")

private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

/** Contorno tracejado: marca plantões avulsos no calendário. */
private fun View.tracejado(c: Canvas, r: RectF, rad: Float, cor: Int) {
    traco.color = cor; traco.strokeWidth = context.dpf(1.5f)
    traco.pathEffect = android.graphics.DashPathEffect(floatArrayOf(context.dpf(4f), context.dpf(3f)), 0f)
    val x = RectF(r); x.inset(context.dpf(0.75f), context.dpf(0.75f))
    c.drawRoundRect(x, rad, rad, traco)
}

/** Mistura a cor do hospital com branco: fundo claro do bloco, mantendo o tom do hospital. */
private fun tinta(cor: Int, f: Float) = ColorUtils.blendARGB(Color.WHITE, cor, f)

/** Cor escura do mesmo tom, para texto legível sobre o fundo claro. */
private fun escura(cor: Int) = ColorUtils.blendARGB(cor, Color.BLACK, 0.35f)

/** Semana em colunas de 24 h: plantões coloridos por hospital, faixas verdes = livre, cinza = descanso. */
class WeekView(ctx: Context, private val pal: Pal) : View(ctx) {
    var semanaIni: LocalDate = LocalDate.now()
    var dados: List<Plantao> = emptyList()
    var descansoMin = 0
    var choques: Set<String> = emptySet()
    var agora: Long = System.currentTimeMillis()
    var onPlantao: ((Plantao) -> Unit)? = null
    var onDia: ((LocalDate) -> Unit)? = null

    private val hourH = ctx.dpf(28f)
    private val headH = ctx.dpf(56f)
    private val axisW = ctx.dpf(30f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val hits = mutableListOf<Pair<RectF, Plantao>>()
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (headH + hourH * 24 + context.dpf(10f)).toInt())

    private fun y(t: Long, ds: Long) = headH + (t - ds).toFloat() / HOUR * hourH
    private fun dpf(v: Float) = context.dpf(v)

    override fun onDraw(c: Canvas) {
        hits.clear()
        val colW = (width - axisW - dpf(6f)) / 7f
        val hoje = agora.ldt().toLocalDate()
        tp.textAlign = Paint.Align.CENTER
        for (d in 0..6) {
            val date = semanaIni.plusDays(d.toLong())
            val cx = axisW + colW * d + colW / 2
            tp.textSize = context.spf(11f); tp.color = pal.mute; tp.isFakeBoldText = false
            c.drawText(DIAS[d], cx, dpf(18f), tp)
            if (date == hoje) {
                paint.style = Paint.Style.FILL; paint.color = pal.acc
                c.drawCircle(cx, dpf(37f), dpf(14f), paint); tp.color = Color.WHITE
            } else tp.color = pal.ink
            tp.textSize = context.spf(15f); tp.isFakeBoldText = true
            c.drawText(date.dayOfMonth.toString(), cx, dpf(42f), tp)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dpf(1f); paint.color = pal.line
        tp.textAlign = Paint.Align.RIGHT; tp.textSize = context.spf(10f); tp.color = pal.mute; tp.isFakeBoldText = false
        for (hh in 0..24 step 3) {
            val yy = headH + hh * hourH
            c.drawLine(axisW, yy, width - dpf(6f), yy, paint)
            if (hh < 24) c.drawText("%02d".format(hh), axisW - dpf(6f), yy + dpf(4f), tp)
        }
        paint.style = Paint.Style.FILL
        val rad = dpf(6f)
        for (d in 0..6) {
            val date = semanaIni.plusDays(d.toLong())
            val ds = date.ms(); val de = date.plusDays(1).ms()
            val x0 = axisW + colW * d + dpf(2f)
            val x1 = x0 + colW - dpf(4f)
            for (j in Livre.janelas(dados, ds, de, descansoMin)) {
                if (j.fim - j.ini < HOUR) continue
                r.set(x0, y(j.ini, ds) + dpf(1f), x1, y(j.fim, ds) - dpf(1f))
                paint.color = pal.livreFundo
                c.drawRoundRect(r, rad, rad, paint)
                if (r.height() > dpf(40f)) {
                    tp.textAlign = Paint.Align.CENTER; tp.color = pal.livreTexto
                    tp.textSize = context.spf(10f); tp.isFakeBoldText = true
                    c.drawText(horasCurto(j.min), r.centerX(), r.centerY() + dpf(4f), tp)
                }
            }
            if (descansoMin > 0) for (p in dados) {
                val s = maxOf(p.fim, ds); val e = minOf(p.fim + descansoMin * MIN, de)
                if (s < e) { r.set(x0, y(s, ds), x1, y(e, ds)); paint.color = pal.descanso; c.drawRoundRect(r, rad, rad, paint) }
            }
            // plantões do dia; os que se sobrepõem ficam lado a lado
            val segs = dados.map { Triple(it, maxOf(it.inicio, ds), minOf(it.fim, de)) }.filter { it.second < it.third }.sortedBy { it.second }
            val fimFaixa = mutableListOf<Long>()
            val faixa = IntArray(segs.size)
            segs.forEachIndexed { i, sg ->
                var k = fimFaixa.indexOfFirst { it <= sg.second }
                if (k < 0) { k = fimFaixa.size; fimFaixa.add(0) }
                fimFaixa[k] = sg.third; faixa[i] = k
            }
            segs.forEachIndexed { i, (p, s, e) ->
                val n = maxOf(faixa[i] + 1, 1 + segs.count { o -> o.first !== p && o.second < e && o.third > s })
                val w = (x1 - x0) / n
                val bx0 = x0 + w * faixa[i]
                r.set(bx0 + (if (faixa[i] > 0) dpf(1f) else 0f), y(s, ds) + dpf(1f), bx0 + w, y(e, ds) - dpf(1f))
                val cc = if (p.id in choques) pal.choque else corDo(p.local)
                paint.color = tinta(cc, 0.18f)
                c.drawRoundRect(r, rad, rad, paint)
                paint.color = cc
                c.save(); c.clipRect(r.left, r.top, r.left + dpf(3f), r.bottom)
                c.drawRoundRect(r, rad, rad, paint); c.restore()
                if (p.avulso) tracejado(c, r, rad, cc)
                hits.add(RectF(r) to p)
                c.save(); c.clipRect(r)
                tp.textAlign = Paint.Align.LEFT; tp.color = escura(cc)
                tp.textSize = context.spf(10f); tp.isFakeBoldText = true
                var ty = r.top + dpf(13f)
                c.drawText(if (s == p.inicio) hm(p.inicio) else "cont.", r.left + dpf(6f), ty, tp)
                tp.isFakeBoldText = false
                for (wd in abrev(p.local).split(" ")) {
                    ty += dpf(12f)
                    if (ty > r.bottom - dpf(3f)) break
                    c.drawText(wd, r.left + dpf(6f), ty, tp)
                }
                c.restore()
            }
        }
        val idx = ChronoUnit.DAYS.between(semanaIni, hoje)
        if (idx in 0..6) {
            val yy = y(agora, hoje.ms())
            val x = axisW + colW * idx
            paint.color = pal.choque
            c.drawRect(x, yy - dpf(1f), x + colW, yy + dpf(1f), paint)
            c.drawCircle(x + dpf(2f), yy, dpf(4f), paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val hit = hits.lastOrNull { it.first.contains(e.x, e.y) }
                if (hit != null) onPlantao?.invoke(hit.second)
                else if (e.y < headH && e.x > axisW) {
                    val d = ((e.x - axisW) / ((width - axisW - dpf(6f)) / 7f)).toInt().coerceIn(0, 6)
                    onDia?.invoke(semanaIni.plusDays(d.toLong()))
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/** Mês em grade: cada dia mostra os plantões e quantas horas ficam livres. Dias totalmente livres ficam verdes. */
class MonthView(ctx: Context, private val pal: Pal) : View(ctx) {
    var mesAtual: YearMonth = YearMonth.now()
    var dados: List<Plantao> = emptyList()
    var descansoMin = 0
    var choques: Set<String> = emptySet()
    var selecionado: LocalDate? = null
    var hoje: LocalDate = LocalDate.now()
    var onDia: ((LocalDate) -> Unit)? = null

    private val headH = ctx.dpf(28f)
    private val cellH = ctx.dpf(86f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val b = RectF()
    private fun dpf(v: Float) = context.dpf(v)

    private fun inicioGrade(): LocalDate {
        val f = mesAtual.atDay(1)
        return f.minusDays((f.dayOfWeek.value % 7).toLong())
    }

    private fun semanas(): Int {
        val g = inicioGrade()
        return ((ChronoUnit.DAYS.between(g, mesAtual.atEndOfMonth()) / 7) + 1).toInt()
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (headH + cellH * semanas()).toInt())

    override fun onDraw(c: Canvas) {
        val cw = width / 7f
        val rad = dpf(10f)
        tp.textAlign = Paint.Align.CENTER; tp.textSize = context.spf(11f); tp.color = pal.mute; tp.isFakeBoldText = false
        for (i in 0..6) c.drawText(DIAS[i], cw * i + cw / 2, dpf(18f), tp)
        val g = inicioGrade()
        for (i in 0 until semanas() * 7) {
            val d = g.plusDays(i.toLong())
            val x = (i % 7) * cw
            val yy = headH + (i / 7) * cellH
            r.set(x + dpf(2f), yy + dpf(2f), x + cw - dpf(2f), yy + cellH - dpf(2f))
            val noMes = YearMonth.from(d) == mesAtual
            val ds = d.ms(); val de = d.plusDays(1).ms()
            val doDia = dados.filter { it.inicio in ds until de }
            val livreMin = Livre.livreNoDia(dados, d, descansoMin)
            paint.style = Paint.Style.FILL
            paint.color = if (livreMin >= 1440) pal.livreFundo else if (noMes) pal.surface else pal.bg
            c.drawRoundRect(r, rad, rad, paint)
            val cx = doDia.any { it.id in choques }
            if (cx || d == selecionado) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dpf(if (d == selecionado) 2f else 1.5f)
                paint.color = if (d == selecionado) pal.acc else pal.choque
                c.drawRoundRect(r, rad, rad, paint); paint.style = Paint.Style.FILL
            }
            tp.textAlign = Paint.Align.LEFT; tp.textSize = context.spf(13f); tp.isFakeBoldText = true
            val nx = r.left + dpf(7f); val ny = r.top + dpf(18f)
            if (d == hoje) {
                paint.color = pal.acc
                val w = tp.measureText(d.dayOfMonth.toString())
                c.drawCircle(nx + w / 2, ny - dpf(4.5f), dpf(11f), paint)
                tp.color = Color.WHITE
            } else tp.color = if (noMes) pal.ink else pal.mute
            c.drawText(d.dayOfMonth.toString(), nx, ny, tp)

            var by = r.top + dpf(27f)
            for ((k, p) in doDia.withIndex()) {
                if (k == 3) {
                    tp.color = pal.mute; tp.textSize = context.spf(9f); tp.isFakeBoldText = true
                    c.drawText("+${doDia.size - 3}", r.left + dpf(5f), by + dpf(9f), tp)
                    break
                }
                b.set(r.left + dpf(3f), by, r.right - dpf(3f), by + dpf(15f))
                val cc = if (p.id in choques) pal.choque else corDo(p.local)
                paint.color = if (noMes) tinta(cc, 0.2f) else tinta(cc, 0.1f)
                c.drawRoundRect(b, dpf(4f), dpf(4f), paint)
                if (p.avulso) tracejado(c, b, dpf(4f), cc)
                c.save(); c.clipRect(b)
                tp.color = escura(cc); tp.textSize = context.spf(9f); tp.isFakeBoldText = true
                c.drawText("${hm(p.inicio).substring(0, 2)}h ${abrev(p.local)}", b.left + dpf(4f), b.bottom - dpf(4.5f), tp)
                c.restore()
                by += dpf(17f)
            }
            if (livreMin >= 60 && noMes) {
                tp.color = pal.livreTexto; tp.textSize = context.spf(10f); tp.isFakeBoldText = true
                c.drawText(if (livreMin >= 1440) "livre" else "${livreMin / 60}h livre", r.left + dpf(6f), r.bottom - dpf(7f), tp)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (e.y > headH) {
                    val col = (e.x / (width / 7f)).toInt().coerceIn(0, 6)
                    val row = ((e.y - headH) / cellH).toInt().coerceIn(0, semanas() - 1)
                    onDia?.invoke(inicioGrade().plusDays((row * 7 + col).toLong()))
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
