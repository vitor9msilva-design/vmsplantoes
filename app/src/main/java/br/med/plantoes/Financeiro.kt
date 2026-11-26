package br.med.plantoes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.time.YearMonth

/** Números de um mês: produtividade (fichas/valor fixo por plantão), salários fixos e previsão. */
data class ResumoMes(
    val ym: YearMonth,
    val prod: Double,          // produtividade já lançada
    val fixo: Double,          // salários fixos do mês
    val previsto: Double,      // estimativa dos plantões que ainda vão acontecer
    val horasProd: Double,     // horas dos plantões com ganho lançado
    val horasFixo: Double,     // horas de plantão nos locais pagos por salário
    val plantoes: Int,
    val horas: Double,
    val pendentes: Int,        // plantões feitos sem fichas
    val esperadoReceber: Double,
    val recebido: Double,
    /** Meta do mês: manual, ou automática (plantões fixos × R$/h da meta + salários). */
    val meta: Double = 0.0,
    /** Meta e ganho só dos plantões fixos já lançados (para dizer quanto ficou acima/abaixo até agora). */
    val metaLancada: Double = 0.0,
    val prodFixos: Double = 0.0,
    val prodAvulsos: Double = 0.0,
    val avulsos: Int = 0,
) {
    /** Diferença dos plantões fixos lançados em relação à meta deles. */
    val difFixos: Double get() = prodFixos - metaLancada
    val total: Double get() = prod + fixo
    val totalPrevisto: Double get() = total + previsto
    val tudoRecebido: Boolean get() = esperadoReceber > 0 && recebido >= esperadoReceber - 1
}

private fun chaveFixo(r: RendaFixa) = "fixo:" + r.id

/** Estimativa de ganho de um plantão futuro: valor fixo, ou sua média R$/h nesse hospital nos últimos 90 dias. */
internal fun Telas.estimativa(p: Plantao, d: Dados): Double? {
    if (p.local.trim() in d.locaisFixos && p.valor <= 0) return 0.0
    if (p.valor > 0 && !p.lancado) return p.valor
    val hist = d.ativos.filter { it.local == p.local && it.lancado && it.inicio < d.now && it.inicio > d.now - 90 * DAY }
    val h = hist.sumOf { it.horas }
    return if (h > 0) hist.sumOf { it.ganho } / h * p.horas else null
}

internal fun Telas.resumoMes(d: Dados, ym: YearMonth): ResumoMes {
    val ini = ym.atDay(1).ms(); val fim = ym.plusMonths(1).atDay(1).ms()
    val doMes = d.ativos.filter { it.inicio in ini until fim }
    val feitos = doMes.filter { it.inicio <= d.now }
    val comGanho = feitos.filter { it.temGanho && !(it.local.trim() in d.locaisFixos && it.valor <= 0) }
    val fixosMes = d.fixos.filter { it.ativaEm(ym) }
    val prod = comGanho.sumOf { it.ganho }
    val fixo = fixosMes.sumOf { it.valor }
    val previsto = doMes.filter { it.inicio > d.now }.sumOf { estimativa(it, d) ?: 0.0 }
    // fechamento: o que se espera receber por hospital e por salário, e o que já foi marcado como recebido
    var esperado = 0.0; var recebido = 0.0
    for ((local, ps) in comGanho.groupBy { it.local }) {
        val t = ps.sumOf { it.ganho }; esperado += t
        recebido += d.recebidos[Recebimentos.chave(ym.year, ym.monthValue, local)]?.valor ?: 0.0
    }
    for (r in fixosMes) { esperado += r.valor; recebido += d.recebidos[Recebimentos.chave(ym.year, ym.monthValue, chaveFixo(r))]?.valor ?: 0.0 }
    val ehMeta = { p: Plantao -> !p.avulso && !(p.local.trim() in d.locaisFixos && p.valor <= 0) }
    val metaAuto = doMes.filter(ehMeta).sumOf { it.horas * d.meta } + fixo
    val fixLanc = comGanho.filter { !it.avulso }
    val avLanc = comGanho.filter { it.avulso }
    return ResumoMes(
        ym, prod, fixo, previsto,
        comGanho.sumOf { it.horas },
        feitos.filter { it.local.trim() in d.locaisFixos && it.valor <= 0 }.sumOf { it.horas },
        doMes.size, doMes.sumOf { it.horas },
        feitos.count { !it.temGanho && it.local.trim() !in d.locaisFixos },
        esperado, recebido,
        meta = if (d.metaMes > 0) d.metaMes else metaAuto,
        metaLancada = fixLanc.sumOf { it.horas * d.meta },
        prodFixos = fixLanc.sumOf { it.ganho },
        prodAvulsos = avLanc.sumOf { it.ganho },
        avulsos = doMes.count { it.avulso },
    )
}

private fun brlK(v: Double): String = when {
    v >= 1_000_000 -> "%.1f mi".format(PT, v / 1_000_000)
    v >= 10_000 -> "%.0f mil".format(PT, v / 1000)
    v >= 1000 -> "%.1f mil".format(PT, v / 1000)
    else -> "%.0f".format(PT, v)
}

private fun mesCurto(ym: YearMonth): String =
    ym.month.getDisplayName(java.time.format.TextStyle.SHORT, PT).replace(".", "").replaceFirstChar { it.titlecase(PT) }

// ======================= FINANCEIRO =======================
fun Telas.financeiro(d: Dados): View {
    tickers.clear(); meta = d.meta; locaisFixos = d.locaisFixos
    val v = ui.col()
    v.addView(ui.cabecalho("Financeiro"))
    val tg = MaterialButtonToggleGroup(ui.c).apply { isSingleSelection = true; isSelectionRequired = true }
    val estilo = com.google.android.material.R.attr.materialButtonOutlinedStyle
    val b0 = MaterialButton(ui.c, null, estilo).apply { text = "Mês"; isAllCaps = false; id = View.generateViewId() }
    val b1 = MaterialButton(ui.c, null, estilo).apply { text = "Linha do tempo"; isAllCaps = false; id = View.generateViewId() }
    tg.addView(b0, ui.lp(0, WRAP, peso = 1f)); tg.addView(b1, ui.lp(0, WRAP, peso = 1f))
    tg.check(if (finModo == 0) b0.id else b1.id)
    tg.addOnButtonCheckedListener { _, id, ok -> val n = if (id == b1.id) 1 else 0; if (ok && n != finModo) { finModo = n; tg.post { a.redesenhar() } } }
    v.addView(tg, ui.lp(bottom = 12))
    if (finModo == 1) linhaDoTempo(v, d) else mesFinanceiro(v, d)
    return v
}

private fun Telas.mesFinanceiro(v: LinearLayout, d: Dados) {
    val pal = ui.pal
    val nav = ui.lin()
    nav.addView(ui.icone(R.drawable.ic_ant, "Mês anterior", pal.ink2) { mesGanhos = mesGanhos.minusMonths(1); a.redesenhar() },
        LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    nav.addView(ui.tv(mesAno(mesGanhos), 16f, pal.ink, 600).apply { gravity = Gravity.CENTER }, ui.lp(0, WRAP, peso = 1f))
    nav.addView(ui.icone(R.drawable.ic_prox, "Próximo mês", pal.ink2) { mesGanhos = mesGanhos.plusMonths(1); a.redesenhar() },
        LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    v.addView(nav, ui.lp(bottom = 8))

    val ym = mesGanhos
    val r = resumoMes(d, ym)
    val ini = ym.atDay(1).ms(); val fim = ym.plusMonths(1).atDay(1).ms()
    val doMes = d.ativos.filter { it.inicio in ini until fim }
    val feitos = doMes.filter { it.inicio <= d.now }
    val comGanho = feitos.filter { it.temGanho && !(it.local.trim() in d.locaisFixos && it.valor <= 0) }
    val fixosMes = d.fixos.filter { it.ativaEm(ym) }

    // ---- cartão principal ----
    val card = ui.card(raio = 20f)
    val c = ui.col(20, 20)
    c.addView(ui.tv("Total no mês", 13f, pal.mute, 500))
    c.addView(ui.tv(brl0(r.total), 40f, pal.ink, 700, num = true), ui.lp(top = 4))
    if (r.fixo > 0) {
        val comp = ui.lin()
        comp.addView(ui.pill("Fixo ${brl0(r.fixo)}", pal.accEscuro, pal.accClaro))
        comp.addView(ui.pill("Produtividade ${brl0(r.prod)}", pal.livreTexto, pal.livreFundo), ui.lp(WRAP, WRAP, start = 6))
        c.addView(comp, ui.lp(top = 10))
    }
    // meta mensal (automática pelos plantões fixos, ou manual)
    val metaM = r.meta
    if (metaM > 0) {
        val base = r.total
        val fr = (base / metaM).toFloat().coerceIn(0f, 1f)
        val frPrev = (r.totalPrevisto / metaM).toFloat().coerceIn(0f, 1f)
        c.addView(ui.divisor(0), ui.lp(h = 1, top = 16, bottom = 12))
        val l = ui.lin()
        l.addView(ui.tv(if (d.metaMes > 0) "Meta do mês" else "Meta do mês (automática)", 14f, pal.ink2, 500), ui.lp(0, WRAP, peso = 1f))
        l.addView(ui.tv("${(fr * 100).toInt()}% de ${brl0(metaM)}", 13f, pal.mute, 600, num = true))
        c.addView(l)
        // barra com três partes: feito, previsto, falta
        val barra = ui.lin().apply { background = ui.fundo(pal.surface2, 4f) }
        val corOk = if (r.totalPrevisto >= metaM) pal.livre else pal.acc
        barra.addView(View(ui.c).apply { background = ui.fundo(corOk, 4f) }, LinearLayout.LayoutParams(0, ui.dp(8), maxOf(fr, 0.01f)))
        barra.addView(View(ui.c).apply { background = ui.fundo(ColorUtils.setAlphaComponent(corOk, 70), 4f) }, LinearLayout.LayoutParams(0, ui.dp(8), maxOf(frPrev - fr, 0f)))
        barra.addView(View(ui.c), LinearLayout.LayoutParams(0, ui.dp(8), maxOf(1f - frPrev, 0.0001f)))
        c.addView(barra, ui.lp(top = 8))
        val falta = metaM - r.totalPrevisto
        c.addView(ui.tv(
            if (r.previsto > 0) (if (falta <= 0) "Pela previsão (${brl0(r.totalPrevisto)}) você passa a meta." else "Pela previsão (${brl0(r.totalPrevisto)}) faltariam ${brl0(falta)}.")
            else if (r.total >= metaM) "Meta batida: ${brl0(r.total - metaM)} acima." else "Ficou ${brl0(metaM - r.total)} abaixo da meta.",
            12f, if (falta <= 0 || r.total >= metaM) pal.livreTexto else pal.mute, 500), ui.lp(top = 6))
        // acima/abaixo da meta só nos plantões fixos já lançados, e o extra dos avulsos
        if (r.metaLancada > 0) {
            val dif = r.difFixos
            val l2 = ui.lin()
            l2.addView(ui.tv("Plantões fixos lançados", 13f, pal.ink2, 500), ui.lp(0, WRAP, peso = 1f))
            l2.addView(ui.tv((if (dif >= 0) "+" else "−") + brl0(Math.abs(dif)) + (if (dif >= 0) " acima" else " abaixo"), 13f,
                if (dif >= 0) pal.livreTexto else pal.alerta, 700, num = true))
            c.addView(l2, ui.lp(top = 10))
            c.addView(ui.tv("${brl0(r.prodFixos)} ganhos contra ${brl0(r.metaLancada)} de meta", 12f, pal.mute, 400, num = true), ui.lp(top = 2))
        }
        if (r.prodAvulsos > 0 || r.avulsos > 0) {
            val l3 = ui.lin()
            l3.addView(ui.tv("Avulsos (${r.avulsos})", 13f, pal.ink2, 500), ui.lp(0, WRAP, peso = 1f))
            l3.addView(ui.tv("+${brl0(r.prodAvulsos)} extra", 13f, pal.avulso, 700, num = true))
            c.addView(l3, ui.lp(top = 8))
        }
    } else if (r.previsto > 0) {
        c.addView(ui.divisor(0), ui.lp(h = 1, top = 16, bottom = 12))
        val l = ui.lin()
        l.addView(ui.tv("Previsão para o mês", 14f, pal.ink2, 500), ui.lp(0, WRAP, peso = 1f))
        l.addView(ui.tv(brl0(r.totalPrevisto), 18f, pal.ink, 700, num = true))
        c.addView(l)
    }
    // R$/h da produtividade contra a meta por hora
    if (r.horasProd > 0) {
        val media = r.prod / r.horasProd
        val ok = media >= d.meta
        c.addView(ui.divisor(0), ui.lp(h = 1, top = 16, bottom = 12))
        val l = ui.lin()
        l.addView(ui.tv("Produtividade: ${brl0(media)}/h", 14f, pal.ink2, 600, num = true), ui.lp(0, WRAP, peso = 1f))
        l.addView(ui.tv("meta ${brl0(d.meta)}/h", 13f, pal.mute, 500, num = true))
        c.addView(l)
        val barra = ui.lin().apply { background = ui.fundo(pal.surface2, 4f) }
        val fr = if (d.meta > 0) (media / d.meta).toFloat().coerceIn(0.02f, 1f) else 1f
        barra.addView(View(ui.c).apply { background = ui.fundo(if (ok) pal.livre else pal.alerta, 4f) }, LinearLayout.LayoutParams(0, ui.dp(8), fr))
        barra.addView(View(ui.c), LinearLayout.LayoutParams(0, ui.dp(8), 1f - fr + 0.0001f))
        c.addView(barra, ui.lp(top = 8))
        c.addView(ui.tv(if (ok) "${brl0(media - d.meta)}/h acima da meta" else "${brl0(d.meta - media)}/h abaixo da meta",
            12f, if (ok) pal.livreTexto else pal.alerta, 600), ui.lp(top = 6))
    }
    card.addView(c)
    v.addView(card)

    v.addView(estatisticas(listOf(
        r.plantoes.toString() to "${if (r.plantoes == 1) "plantão" else "plantões"} · ${horas(Math.round(r.horas * 60))}",
        (comGanho.sumOf { it.fichas ?: 0 }).toString() to "fichas",
        (comGanho.sumOf { it.uti ?: 0 }).toString() to "UTI",
    )), ui.lp(top = 4))

    val pend = feitos.filter { !it.temGanho && it.local.trim() !in d.locaisFixos }
    if (pend.isNotEmpty()) v.addView(avisoNeutro("${pl(pend.size, "plantão", "plantões")} sem fichas lançadas", "O total sobe quando você lançar.", "Lançar") { a.lancar(pend.first()) })

    // ---- análises ----
    val ins = analises(d, ym)
    if (ins.isNotEmpty()) {
        v.addView(ui.secao("Análises"))
        val cc = ui.card()
        val cv = ui.col(16, 14)
        ins.forEachIndexed { i, (t, cor) ->
            val l = ui.lin(Gravity.TOP)
            l.addView(ui.ponto(cor, 8), LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { topMargin = ui.dp(6) })
            l.addView(ui.tv(t, 14f, pal.ink2), ui.lp(0, WRAP, start = 10, peso = 1f))
            cv.addView(l, ui.lp(top = if (i == 0) 0 else 10))
        }
        cc.addView(cv); v.addView(cc)
    }

    // ---- fechamento ----
    if (fixosMes.isNotEmpty() || comGanho.isNotEmpty() || feitos.isNotEmpty()) {
        v.addView(ui.secao("Fechamento", if (r.esperadoReceber > 0) "recebido ${brl0(r.recebido)} de ${brl0(r.esperadoReceber)}" else null))
        val linhas = mutableListOf<View>()
        for (fx in fixosMes) {
            val h = feitos.filter { it.local.trim() == fx.local.trim() && fx.local.isNotBlank() && it.valor <= 0 }.sumOf { it.horas }
            val sub = "Salário fixo" + if (h > 0) " · ${horas(Math.round(h * 60))} de plantão · ${brl0(fx.valor / h)}/h" else ""
            linhas += linhaFechamento(d, ym, chaveFixo(fx), fx.nome, sub, pal.accEscuro, fx.valor, null, true)
        }
        for ((local, ps) in feitos.filter { it.local.trim() !in d.locaisFixos || it.valor > 0 }.groupBy { it.local }.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
            val g = ps.filter { it.temGanho }
            val tot = g.sumOf { it.ganho }; val h = g.sumOf { it.horas }
            val regra = d.regras[local]
            val semValor = (regra == null || !regra.configurada) && ps.any { !it.temGanho }
            val sub = "${pl(ps.size, "plantão", "plantões")} · ${horas(ps.sumOf { it.durMin.toLong() })}" + if (semValor) " · configure os valores" else ""
            linhas += linhaFechamento(d, ym, local, local, sub, corDo(local), tot, if (h > 0) tot / h else null, !semValor, semValor)
        }
        v.addView(ui.grupo(*linhas.toTypedArray()))
    }

    v.addView(ui.secao("Planilha"))
    v.addView(ui.grupo(
        ui.linha("Exportar ${mesAno(ym).lowercase(PT)}", "Arquivo para Excel ou para o contador") { a.exportar(ym.year, ym.monthValue) },
        ui.linha("Exportar o ano de ${ym.year}", "Todos os plantões e ganhos do ano") { a.exportar(ym.year, null) },
    ))

    if (feitos.isNotEmpty()) {
        v.addView(ui.secao("Plantões do mês"))
        feitos.sortedByDescending { it.inicio }.forEach { v.addView(itemRow(it, false, d.now, esmaecer = false)) }
    } else if (fixosMes.isEmpty()) v.addView(vazioGrande("Nenhum plantão neste mês ainda", "Os ganhos aparecem aqui depois de lançar as fichas."))
}

private fun Telas.linhaFechamento(
    d: Dados, ym: YearMonth, chaveLocal: String, titulo: String, sub: String, cor: Int,
    tot: Double, porHora: Double?, podeMarcar: Boolean, configurar: Boolean = false,
): View {
    val pal = ui.pal
    val r = ui.lin()
    r.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
    r.addView(ui.ponto(cor, 10))
    val t = ui.col()
    t.addView(ui.tv(titulo, 15f, pal.ink, 600))
    t.addView(ui.tv(sub, 12f, if (configurar) pal.alerta else pal.mute), ui.lp(top = 2))
    val chave = Recebimentos.chave(ym.year, ym.monthValue, chaveLocal)
    val rec = d.recebidos[chave]
    if (podeMarcar && tot > 0) {
        val (txt, corR) = when {
            rec == null -> "A receber · toque quando cair na conta" to pal.mute
            Math.abs(rec.valor - tot) < 1 -> "Recebido em ${diaCurto(rec.quando)} · confere" to pal.livreTexto
            rec.valor < tot -> "Recebido ${brl0(rec.valor)} · faltaram ${brl0(tot - rec.valor)}" to pal.choque
            else -> "Recebido ${brl0(rec.valor)} · ${brl0(rec.valor - tot)} a mais" to pal.livreTexto
        }
        t.addView(ui.tv(txt, 12f, corR, 600), ui.lp(top = 3))
    }
    r.addView(t, ui.lp(0, WRAP, start = 12, peso = 1f))
    val dir = ui.col(); dir.gravity = Gravity.END; dir.minimumWidth = ui.dp(104)
    dir.addView(ui.tv(brl0(tot), 15f, pal.ink, 700, num = true).apply { gravity = Gravity.END; maxLines = 1 })
    if (porHora != null) dir.addView(ui.tv("${brl0(porHora)}/h", 12f, if (porHora >= d.meta) pal.livreTexto else pal.alerta, 600, num = true).apply { gravity = Gravity.END }, ui.lp(WRAP, WRAP, top = 2))
    r.addView(dir, ui.lp(WRAP, WRAP, start = 8))
    r.setBackgroundResource(android.util.TypedValue().also { ui.c.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId)
    r.setOnClickListener {
        when {
            configurar -> a.editarRegra(titulo)
            podeMarcar && tot > 0 -> a.marcarRecebido(chave, titulo, mesAno(ym), tot)
        }
    }
    return r
}

/** Frases curtas tiradas dos números: o que mudou e onde rende mais. */
private fun Telas.analises(d: Dados, ym: YearMonth): List<Pair<String, Int>> {
    val pal = ui.pal
    val out = mutableListOf<Pair<String, Int>>()
    val r = resumoMes(d, ym)
    val ant = resumoMes(d, ym.minusMonths(1))
    if (r.horasProd > 0 && ant.horasProd > 0) {
        val a1 = r.prod / r.horasProd; val a0 = ant.prod / ant.horasProd
        val pct = ((a1 - a0) / a0 * 100).toInt()
        if (pct != 0) out += (if (pct > 0) "Seu R$/h de produtividade subiu $pct% em relação a ${mesCurto(ym.minusMonths(1)).lowercase(PT)}."
        else "Seu R$/h de produtividade caiu ${-pct}% em relação a ${mesCurto(ym.minusMonths(1)).lowercase(PT)}.") to (if (pct > 0) pal.livre else pal.alerta)
    }
    // hospital que rende mais por hora (últimos 90 dias)
    val hist = d.ativos.filter { it.lancado && it.inicio < d.now && it.inicio > d.now - 90 * DAY }
    val porLocal = hist.groupBy { it.local }.mapValues { (_, ps) -> ps.sumOf { it.ganho } / ps.sumOf { it.horas } }.filter { it.value > 0 }
    if (porLocal.size >= 2) {
        val melhor = porLocal.maxBy { it.value }; val pior = porLocal.minBy { it.value }
        out += "${melhor.key} é o que mais rende: ${brl0(melhor.value)}/h, contra ${brl0(pior.value)}/h em ${pior.key} (últimos 90 dias)." to pal.acc
    }
    // fichas para bater a meta num plantão típico de 12 h
    val regras = d.regras.filter { it.value.porFicha > 0 && it.key in porLocal.keys }
    for ((local, rg) in regras.entries.take(2)) {
        val fichasMeta = Math.ceil(d.meta * 12 / rg.porFicha).toInt()
        val ps = hist.filter { it.local == local && it.durMin == 720 }
        if (ps.isNotEmpty()) {
            val med = ps.mapNotNull { it.fichas }.average()
            out += "Em $local, a meta pede $fichasMeta fichas num plantão de 12 h; sua média é ${"%.1f".format(PT, med)}." to (if (med >= fichasMeta) pal.livre else pal.alerta)
        }
    }
    if (r.pendentes > 0) out += "Faltam lançar as fichas de ${pl(r.pendentes, "plantão", "plantões")} deste mês." to pal.alerta
    return out
}

// ======================= LINHA DO TEMPO =======================
private fun Telas.linhaDoTempo(v: LinearLayout, d: Dados) {
    val pal = ui.pal
    val agora = YearMonth.from(d.now.ldt())
    val primeiro = (d.ativos.minOfOrNull { YearMonth.from(it.inicio.ldt()) } ?: agora)
        .let { p -> listOfNotNull(p, d.fixos.minOfOrNull { YearMonth.parse(it.desde) }).min() }
    val inicio = maxOf(primeiro, agora.minusMonths(11))
    val temProximo = d.ativos.any { YearMonth.from(it.inicio.ldt()) == agora.plusMonths(1) }
    val fimTL = if (temProximo) agora.plusMonths(1) else agora
    val meses = generateSequence(inicio) { it.plusMonths(1) }.takeWhile { it <= fimTL }.toList()
    val res = meses.map { resumoMes(d, it) }

    // resumo do ano
    val doAno = meses.indices.filter { meses[it].year == agora.year && meses[it] <= agora }.map { res[it] }
    val totalAno = doAno.sumOf { it.total }
    val melhor = doAno.maxByOrNull { it.total }
    val card = ui.card(raio = 20f)
    val c = ui.col(20, 20)
    c.addView(ui.tv("${agora.year} até agora", 13f, pal.mute, 500))
    c.addView(ui.tv(brl0(totalAno), 36f, pal.ink, 700, num = true), ui.lp(top = 4))
    val l = ui.lin(Gravity.TOP)
    fun bloco(t: String, s: String) = ui.col().apply { addView(ui.tv(t, 16f, pal.ink, 700, num = true)); addView(ui.tv(s, 12f, pal.mute, 500), ui.lp(top = 2)) }
    l.addView(bloco(if (doAno.isNotEmpty()) brl0(totalAno / doAno.size) else "–", "média por mês"), ui.lp(0, WRAP, peso = 1f))
    l.addView(bloco(melhor?.let { mesCurto(it.ym) } ?: "–", melhor?.let { "melhor mês · ${brl0(it.total)}" } ?: "melhor mês"), ui.lp(0, WRAP, peso = 1f))
    c.addView(l, ui.lp(top = 14))
    // gráfico
    val graf = MesesChart(ui.c, pal).apply {
        dados = res; selecionado = mesGanhos; atual = agora
        onMes = { ym -> mesGanhos = ym; finModo = 0; a.redesenhar() }
    }
    c.addView(graf, ui.lp(top = 18))
    val leg = ui.lin()
    fun item(t: String, cor: Int) = ui.lin().apply { addView(ui.ponto(cor, 9)); addView(ui.tv(t, 12f, pal.ink2, 500), ui.lp(WRAP, WRAP, start = 6, end = 14)) }
    leg.addView(item("Fixo", pal.accEscuro)); leg.addView(item("Produtividade", pal.acc)); leg.addView(item("Previsão", ColorUtils.setAlphaComponent(pal.acc, 80)))
    leg.addView(item("— Meta", pal.ink2))
    c.addView(leg, ui.lp(top = 10))
    card.addView(c)
    v.addView(card)

    // linha do tempo mês a mês (mais recente em cima)
    v.addView(ui.secao("Mês a mês"))
    val maxTot = res.maxOfOrNull { it.totalPrevisto }?.takeIf { it > 0 } ?: 1.0
    res.asReversed().forEachIndexed { i, r ->
        val linha = ui.lin(Gravity.TOP)
        // trilho com ponto
        val trilho = android.widget.FrameLayout(ui.c)
        trilho.addView(View(ui.c).apply { setBackgroundColor(pal.line) },
            android.widget.FrameLayout.LayoutParams(ui.dp(2), MATCH, Gravity.CENTER_HORIZONTAL).apply { topMargin = if (i == 0) ui.dp(22) else 0 })
        val corPonto = when { r.ym > agora -> pal.line; r.ym == agora -> pal.acc; r.tudoRecebido -> pal.livre; else -> pal.alerta }
        trilho.addView(View(ui.c).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(corPonto); setStroke(ui.dp(3), pal.bg) }
        }, android.widget.FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(16) })
        linha.addView(trilho, LinearLayout.LayoutParams(ui.dp(24), MATCH))

        val cardM = ui.card()
        val cm = ui.col(16, 14)
        val topo = ui.lin()
        topo.addView(ui.tv(mesAno(r.ym), 15f, pal.ink, 600), ui.lp(0, WRAP, peso = 1f))
        topo.addView(ui.tv(brl0(if (r.ym > agora) r.totalPrevisto else r.total), 17f, pal.ink, 700, num = true))
        cm.addView(topo)
        // barra empilhada: fixo | produtividade | previsão
        val barra = ui.lin().apply { background = ui.fundo(pal.surface2, 3f) }
        fun parte(x: Double, cor: Int) { if (x > 0) barra.addView(View(ui.c).apply { setBackgroundColor(cor) }, LinearLayout.LayoutParams(0, ui.dp(6), (x / maxTot).toFloat())) }
        parte(r.fixo, pal.accEscuro); parte(r.prod, pal.acc); parte(r.previsto, ColorUtils.setAlphaComponent(pal.acc, 80))
        barra.addView(View(ui.c), LinearLayout.LayoutParams(0, ui.dp(6), maxOf(0.0001f, (1 - r.totalPrevisto / maxTot).toFloat())))
        barra.clipToOutline = true
        cm.addView(barra, ui.lp(top = 10))
        val det = mutableListOf("${pl(r.plantoes, "plantão", "plantões")} · ${horas(Math.round(r.horas * 60))}")
        if (r.horasProd > 0) det += "${brl0(r.prod / r.horasProd)}/h"
        if (r.meta > 0 && r.ym <= agora) {
            val dif = (if (r.ym < agora) r.total - r.meta else r.difFixos)
            det += (if (dif >= 0) "+" else "−") + brl0(Math.abs(dif)) + " vs meta"
        }
        cm.addView(ui.tv(det.joinToString(" · "), 12f, pal.mute, 500, num = true), ui.lp(top = 8))
        val (st, corSt) = when {
            r.ym > agora -> "Previsão" to pal.mute
            r.ym == agora && r.previsto > 0 -> "Em andamento · previsão ${brl0(r.totalPrevisto)}" to pal.acc
            r.esperadoReceber <= 0 -> "Sem valores lançados" to pal.mute
            r.tudoRecebido -> "Tudo recebido" to pal.livreTexto
            else -> "A receber ${brl0(r.esperadoReceber - r.recebido)}" to pal.alerta
        }
        cm.addView(ui.tv(st, 12f, corSt, 600), ui.lp(top = 4))
        cardM.addView(cm)
        cardM.setOnClickListener { mesGanhos = r.ym; finModo = 0; a.redesenhar() }
        linha.addView(cardM, ui.lp(0, WRAP, start = 6, peso = 1f))
        v.addView(linha)
    }
}

/** Gráfico de colunas por mês: fixo (escuro), produtividade (cor do tema) e previsão (clara). Toque abre o mês. */
class MesesChart(ctx: Context, private val pal: Pal) : View(ctx) {
    var dados: List<ResumoMes> = emptyList()
    var selecionado: YearMonth? = null
    var atual: YearMonth = YearMonth.now()
    var onMes: ((YearMonth) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private fun dpf(v: Float) = context.dpf(v)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dpf(170f).toInt())

    override fun onDraw(c: Canvas) {
        if (dados.isEmpty()) return
        val topo = dpf(18f); val base = height - dpf(22f)
        val maxV = dados.maxOf { maxOf(it.totalPrevisto, it.meta) }.takeIf { it > 0 } ?: 1.0
        val n = dados.size
        val slot = width.toFloat() / maxOf(n, 6)
        val bw = minOf(slot * 0.56f, dpf(26f))
        val x0 = (width - slot * n) / 2f
        paint.color = pal.line; paint.strokeWidth = dpf(1f)
        c.drawLine(0f, base, width.toFloat(), base, paint)
        dados.forEachIndexed { i, m ->
            val cx = x0 + slot * i + slot / 2
            var y = base
            fun seg(v: Double, cor: Int) {
                if (v <= 0) return
                val h = ((v / maxV) * (base - topo)).toFloat()
                r.set(cx - bw / 2, y - h, cx + bw / 2, y)
                paint.color = cor; c.drawRect(r, paint); y -= h
            }
            seg(m.fixo, pal.accEscuro); seg(m.prod, pal.acc); seg(m.previsto, ColorUtils.setAlphaComponent(pal.acc, 80))
            if (m.meta > 0) {
                val ym2 = base - ((m.meta / maxV) * (base - topo)).toFloat()
                paint.color = pal.ink2; paint.strokeWidth = dpf(2f)
                c.drawLine(cx - bw / 2 - dpf(3f), ym2, cx + bw / 2 + dpf(3f), ym2, paint)
            }
            if (m.ym == selecionado) {
                paint.color = ColorUtils.setAlphaComponent(pal.acc, 26)
                r.set(cx - slot / 2 + dpf(1f), topo - dpf(14f), cx + slot / 2 - dpf(1f), base); c.drawRoundRect(r, dpf(6f), dpf(6f), paint)
            }
            tp.textAlign = Paint.Align.CENTER
            if (m.totalPrevisto > 0) {
                tp.textSize = context.spf(9f); tp.color = pal.ink2; tp.isFakeBoldText = true
                c.drawText(brlK(if (m.ym > atual) m.totalPrevisto else m.total), cx, y - dpf(4f), tp)
            }
            tp.textSize = context.spf(10f); tp.isFakeBoldText = m.ym == atual
            tp.color = if (m.ym == atual) pal.acc else pal.mute
            c.drawText(mesCurto(m.ym), cx, base + dpf(15f), tp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP && dados.isNotEmpty()) {
            val n = dados.size; val slot = width.toFloat() / maxOf(n, 6); val x0 = (width - slot * n) / 2f
            val i = ((e.x - x0) / slot).toInt()
            if (i in 0 until n) onMes?.invoke(dados[i].ym)
            return true
        }
        return super.onTouchEvent(e)
    }
}
