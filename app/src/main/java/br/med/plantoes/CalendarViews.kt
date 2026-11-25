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
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private val DIAS = arrayOf("D", "S", "T", "Q", "Q", "S", "S")

/** Semana em colunas de 24 h: plantões coloridos por hospital, faixas verdes = livre, cinza = descanso. */
class WeekView(ctx: Context, private val pal: Pal) : View(ctx) {
    var semanaIni: LocalDate = LocalDate.now()
    var dados: List<Plantao> = emptyList()
    var descansoMin = 0
    var choques: Set<String> = emptySet()
    var onPlantao: ((Plantao) -> Unit)? = null
    var onDia: ((LocalDate) -> Unit)? = null

    private val hourH = ctx.dpf(30f)
    private val headH = ctx.dpf(46f)
    private val axisW = ctx.dpf(30f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val hits = mutableListOf<Pair<RectF, Plantao>>()
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (headH + hourH * 24 + context.dpf(6f)).toInt())

    private fun y(t: Long, ds: Long) = headH + (t - ds).toFloat() / HOUR * hourH

    override fun onDraw(c: Canvas) {
        hits.clear()
        val colW = (width - axisW) / 7f
        val hoje = LocalDate.now()
        tp.textAlign = Paint.Align.CENTER
        for (d in 0..6) {
            val date = semanaIni.plusDays(d.toLong())
            val cx = axisW + colW * d + colW / 2
            tp.textSize = context.spf(11f); tp.color = pal.mute; tp.isFakeBoldText = false
            c.drawText(DIAS[d], cx, context.dpf(14f), tp)
            if (date == hoje) {
                paint.style = Paint.Style.FILL; paint.color = Cores.verde
                c.drawCircle(cx, context.dpf(31f), context.dpf(13f), paint); tp.color = Color.WHITE
            } else tp.color = pal.ink
            tp.textSize = context.spf(15f); tp.isFakeBoldText = true
            c.drawText(date.dayOfMonth.toString(), cx, context.dpf(36f), tp)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = pal.line
        tp.textAlign = Paint.Align.RIGHT; tp.textSize = context.spf(10f); tp.color = pal.mute; tp.isFakeBoldText = false
        for (hh in 0..24) {
            val yy = headH + hh * hourH
            c.drawLine(axisW, yy, width.toFloat(), yy, paint)
            if (hh % 3 == 0 && hh < 24) c.drawText("%02d".format(hh), axisW - context.dpf(4f), yy + context.dpf(11f), tp)
        }
        for (d in 0..7) {
            val x = axisW + colW * d
            c.drawLine(x, headH, x, headH + 24 * hourH, paint)
        }
        paint.style = Paint.Style.FILL
        for (d in 0..6) {
            val date = semanaIni.plusDays(d.toLong())
            val ds = date.ms(); val de = date.plusDays(1).ms()
            val x0 = axisW + colW * d + context.dpf(1.5f)
            val x1 = x0 + colW - context.dpf(3f)
            for (j in Livre.janelas(dados, ds, de, descansoMin)) {
                if (j.fim - j.ini < HOUR) continue
                r.set(x0, y(j.ini, ds) + 1, x1, y(j.fim, ds) - 1)
                paint.color = pal.livre
                c.drawRoundRect(r, context.dpf(4f), context.dpf(4f), paint)
                if (r.height() > context.dpf(36f)) {
                    tp.textAlign = Paint.Align.CENTER; tp.color = pal.verdeTxt
                    tp.textSize = context.spf(10f); tp.isFakeBoldText = true
                    c.drawText("livre", r.centerX(), r.centerY() - context.dpf(2f), tp)
                    c.drawText(horasCurto(j.min), r.centerX(), r.centerY() + context.dpf(10f), tp)
                }
            }
            if (descansoMin > 0) for (p in dados) {
                val s = maxOf(p.fim, ds); val e = minOf(p.fim + descansoMin * MIN, de)
                if (s < e) { r.set(x0, y(s, ds), x1, y(e, ds)); paint.color = pal.descanso; c.drawRect(r, paint) }
            }
            for (p in dados) {
                val s = maxOf(p.inicio, ds); val e = minOf(p.fim, de)
                if (s >= e) continue
                r.set(x0, y(s, ds) + 1, x1, y(e, ds) - 1)
                paint.color = if (p.id in choques) Cores.vermelho else corDo(p.local)
                c.drawRoundRect(r, context.dpf(5f), context.dpf(5f), paint)
                hits.add(RectF(r) to p)
                c.save(); c.clipRect(r)
                tp.textAlign = Paint.Align.LEFT; tp.color = Color.WHITE
                tp.textSize = context.spf(10f); tp.isFakeBoldText = true
                var ty = r.top + context.dpf(12f)
                c.drawText(if (s == p.inicio) hm(p.inicio) else "cont.", r.left + context.dpf(3f), ty, tp)
                tp.isFakeBoldText = false
                for (w in abrev(p.local).split(" ")) {
                    ty += context.dpf(12f)
                    if (ty > r.bottom - 2) break
                    c.drawText(w, r.left + context.dpf(3f), ty, tp)
                }
                c.restore()
            }
        }
        val idx = ChronoUnit.DAYS.between(semanaIni, hoje)
        if (idx in 0..6) {
            val yy = y(System.currentTimeMillis(), hoje.ms())
            val x = axisW + colW * idx
            paint.color = Cores.vermelho
            c.drawRect(x, yy - context.dpf(1f), x + colW, yy + context.dpf(1f), paint)
            c.drawCircle(x, yy, context.dpf(4f), paint)
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
                    val d = ((e.x - axisW) / ((width - axisW) / 7f)).toInt().coerceIn(0, 6)
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
    var onDia: ((LocalDate) -> Unit)? = null

    private val headH = ctx.dpf(22f)
    private val cellH = ctx.dpf(92f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val b = RectF()

    private fun inicioGrade(): LocalDate {
        val f = mesAtual.atDay(1)
        return f.minusDays((f.dayOfWeek.value % 7).toLong())
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (headH + cellH * 6).toInt())

    override fun onDraw(c: Canvas) {
        val cw = width / 7f
        val rad = context.dpf(8f)
        tp.textAlign = Paint.Align.CENTER; tp.textSize = context.spf(11f); tp.color = pal.mute; tp.isFakeBoldText = true
        for (i in 0..6) c.drawText(DIAS[i], cw * i + cw / 2, context.dpf(14f), tp)
        val g = inicioGrade()
        val hoje = LocalDate.now()
        for (i in 0 until 42) {
            val d = g.plusDays(i.toLong())
            val x = (i % 7) * cw
            val yy = headH + (i / 7) * cellH
            r.set(x + 2, yy + 2, x + cw - 2, yy + cellH - 2)
            val ds = d.ms(); val de = d.plusDays(1).ms()
            val doDia = dados.filter { it.inicio in ds until de }
            val livreMin = Livre.livreNoDia(dados, d, descansoMin)
            paint.style = Paint.Style.FILL
            paint.color = if (livreMin >= 1440) pal.livre else pal.panel
            c.drawRoundRect(r, rad, rad, paint)
            if (doDia.any { it.id in choques }) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dpf(2f); paint.color = Cores.vermelho
                c.drawRoundRect(r, rad, rad, paint); paint.style = Paint.Style.FILL
            }
            if (d == selecionado) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dpf(2f); paint.color = Cores.verde
                c.drawRoundRect(r, rad, rad, paint); paint.style = Paint.Style.FILL
            }
            tp.textAlign = Paint.Align.LEFT; tp.textSize = context.spf(13f); tp.isFakeBoldText = true
            val nx = r.left + context.dpf(6f); val ny = r.top + context.dpf(16f)
            if (d == hoje) {
                paint.color = Cores.verde
                c.drawCircle(nx + context.dpf(6f), ny - context.dpf(5f), context.dpf(10f), paint)
                tp.color = Color.WHITE
            } else tp.color = pal.ink
            c.drawText(d.dayOfMonth.toString(), nx, ny, tp)

            var by = r.top + context.dpf(24f)
            for ((k, p) in doDia.withIndex()) {
                if (k == 3) {
                    tp.color = pal.mute; tp.textSize = context.spf(9f); tp.isFakeBoldText = true
                    c.drawText("+${doDia.size - 3}", r.left + context.dpf(4f), by + context.dpf(10f), tp)
                    break
                }
                b.set(r.left + context.dpf(3f), by, r.right - context.dpf(3f), by + context.dpf(15f))
                paint.color = if (p.id in choques) Cores.vermelho else corDo(p.local)
                c.drawRoundRect(b, context.dpf(3f), context.dpf(3f), paint)
                c.save(); c.clipRect(b)
                tp.color = Color.WHITE; tp.textSize = context.spf(9f); tp.isFakeBoldText = true
                c.drawText("${hm(p.inicio).substring(0, 2)}h ${abrev(p.local)}", b.left + context.dpf(3f), b.bottom - context.dpf(4f), tp)
                c.restore()
                by += context.dpf(17f)
            }
            if (livreMin >= 60) {
                tp.color = pal.verdeTxt; tp.textSize = context.spf(10f); tp.isFakeBoldText = true
                c.drawText(if (livreMin >= 1440) "livre" else "${livreMin / 60}h livre", r.left + context.dpf(5f), r.bottom - context.dpf(6f), tp)
            }
            if (YearMonth.from(d) != mesAtual) {
                paint.color = (pal.bg and 0x00FFFFFF) or (0xB0 shl 24)
                c.drawRoundRect(r, rad, rad, paint)
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
                    val row = ((e.y - headH) / cellH).toInt().coerceIn(0, 5)
                    onDia?.invoke(inicioGrade().plusDays((row * 7 + col).toLong()))
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
