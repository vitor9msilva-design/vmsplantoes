package br.med.plantoes

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** O que as telas pedem para a Activity fazer (abrir formulário, excluir, trocar de aba...). */
interface Acoes {
    fun editar(p: Plantao?, preset: Long? = null)
    fun excluir(p: Plantao)
    fun redesenhar()
    fun irPara(aba: Int)
    fun permissoes()
    fun testarAlarme()
    fun ajuda()
    fun receber()
    fun enviar()
    fun excluirVarios()
    fun procurarAtualizacao()
    fun mudarTema(id: String)
    fun volumeMaximo(v: Boolean)
    fun mudarFiltroLivre(minH: Int?, descansoH: Int?)
    fun lancar(p: Plantao)
    fun editarRegra(local: String)
    fun editarMeta()
}

/** Tudo o que as telas precisam saber para se desenhar. */
data class Dados(
    val lista: List<Plantao>,
    val now: Long,
    val descansoMin: Int,
    val minLivreH: Int,
    val faltas: List<String>,
    val volMax: Boolean,
    val versao: String,
    val regras: Map<String, Regra> = emptyMap(),
    val meta: Double = 100.0,
)

class Telas(private val ui: Ui, private val a: Acoes) {
    private val pal = ui.pal
    private val tickers = mutableListOf<() -> Unit>()
    private var meta = 100.0

    /** 0 = semana, 1 = mês, 2 = horários livres */
    var calModo = 0
    var calMes: Boolean
        get() = calModo == 1
        set(v) { calModo = if (v) 1 else 0 }
    var mesGanhos: YearMonth = YearMonth.now()
    var semana: LocalDate = inicioSemana(LocalDate.now())
    var mes: YearMonth = YearMonth.now()
    var diaSel: LocalDate = LocalDate.now()

    fun inicioSemana(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    /** Atualiza só os números que mudam com o tempo (contagem, barra), sem redesenhar a tela. */
    fun tick() = tickers.forEach { it() }

    private fun cor(p: Plantao, conf: Set<String>) = if (p.id in conf) pal.choque else corDo(p.local)

    // ======================= INÍCIO =======================
    fun inicio(d: Dados): View {
        tickers.clear(); meta = d.meta
        val v = ui.col()
        val hoje = d.now.ldt().toLocalDate()
        v.addView(ui.cabecalho("Plantões", diaLongo(hoje)))
        if (d.faltas.isNotEmpty())
            v.addView(aviso("Os alarmes podem não tocar", "Falta liberar: ${d.faltas.joinToString(", ")}.", "Corrigir") { a.permissoes() })
        val fut = d.lista.filter { it.fim > d.now }
        val conf = Store.conflitos(fut)
        v.addView(cartaoProximo(fut.firstOrNull(), d, conf))
        if (conf.isNotEmpty())
            v.addView(aviso("Choque de horário", "${conf.size} plantões se sobrepõem. Estão em vermelho na agenda.", "Ver") { a.irPara(1) })
        val pend = pendentes(d)
        if (pend.isNotEmpty()) {
            v.addView(ui.secao("Lançar fichas", "${pend.size} pendente" + if (pend.size > 1) "s" else ""))
            pend.take(3).forEach { v.addView(itemRow(it, false, d.now)) }
        }

        v.addView(ui.secao("Próximos 7 dias"))
        v.addView(faixaSemana(d, hoje, conf))

        for ((titulo, dia) in listOf("Hoje" to hoje, "Amanhã" to hoje.plusDays(1))) {
            val ps = d.lista.filter { it.inicio < dia.plusDays(1).ms() && it.fim > dia.ms() }
            val h = ps.sumOf { it.durMin.toLong() }
            v.addView(ui.secao(titulo, if (ps.isEmpty()) null else horas(h)))
            if (ps.isEmpty()) v.addView(vazio(if (titulo == "Hoje") "Sem plantão hoje" else "Sem plantão amanhã", "Dia livre"))
            else ps.forEach { v.addView(itemRow(it, it.id in conf, d.now)) }
        }

        val ym = YearMonth.from(hoje)
        val doMes = d.lista.filter { YearMonth.from(it.inicio.ldt()) == ym }
        v.addView(ui.secao(mesAno(ym)))
        val comGanho = doMes.filter { it.temGanho }
        val ganho = comGanho.sumOf { it.ganho }
        val horasG = comGanho.sumOf { it.horas }
        v.addView(estatisticas(
            listOf(
                doMes.size.toString() to "plantões",
                brl0(ganho) to "ganhos até agora",
                (if (horasG > 0) "${brl0(ganho / horasG)}/h" else "–") to "média por hora",
            )
        ).apply { setOnClickListener { a.irPara(3) } })
        return v
    }

    private fun cartaoProximo(prox: Plantao?, d: Dados, conf: Set<String>): View {
        val card = ui.card(raio = 20f)
        val v = ui.col(20, 20)
        if (prox == null) {
            v.addView(ui.tv("Nenhum plantão agendado", 19f, pal.ink, 600))
            v.addView(ui.tv("Toque em Plantão para cadastrar, ou receba a escala da página em Ajustes.", 14f, pal.mute), ui.lp(top = 6))
            card.addView(v); return card
        }
        val corP = cor(prox, conf)
        val topo = ui.lin()
        topo.addView(ui.ponto(corP, 10))
        topo.addView(ui.tv(prox.local + if (prox.setor.isNotBlank()) " · ${prox.setor}" else "", 15f, pal.ink2, 600), ui.lp(0, WRAP, start = 8, peso = 1f))
        val status = ui.pill("", pal.acc, pal.accClaro)
        topo.addView(status)
        v.addView(topo)

        val rotulo = ui.tv("", 13f, pal.mute, 500)
        v.addView(rotulo, ui.lp(top = 16))
        val cont = ui.tv("", 44f, pal.ink, 700, num = true)
        v.addView(cont, ui.lp(top = 4))
        val dt = prox.inicio.ldt().toLocalDate()
        v.addView(ui.tv("${diaLongo(dt)}\n${hm(prox.inicio)} – ${hm(prox.fim)} · ${horas(prox.durMin.toLong())}", 14f, pal.ink2, 400, num = true), ui.lp(top = 8))

        // barra de progresso simples: trilho cinza com parte preenchida na cor do hospital
        val barra = ui.lin().apply { background = ui.fundo(pal.surface2, 4f) }
        val cheio = View(ui.c).apply { background = ui.fundo(corP, 4f) }
        val vazio = View(ui.c)
        barra.addView(cheio, LinearLayout.LayoutParams(0, ui.dp(6), 0f))
        barra.addView(vazio, LinearLayout.LayoutParams(0, ui.dp(6), 1f))
        fun progresso(x: Float) {
            val f = x.coerceIn(0.02f, 1f)
            (cheio.layoutParams as LinearLayout.LayoutParams).weight = f
            (vazio.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
            barra.requestLayout()
        }
        v.addView(barra, ui.lp(top = 16))
        val legendaBarra = ui.tv("", 12f, pal.mute, 400, num = true)
        v.addView(legendaBarra, ui.lp(top = 6))

        v.addView(ui.divisor(0), ui.lp(h = 1, top = 16, bottom = 14))
        val alarmes = ui.lin()
        val estados = mutableListOf<Triple<android.widget.TextView, Long, String>>()
        for ((rot, t) in listOf("Aviso 1 dia antes" to prox.inicio - DAY, "Aviso 2 h antes" to prox.inicio - 2 * HOUR)) {
            val c = ui.col()
            c.addView(ui.tv(rot, 12f, pal.mute, 500))
            c.addView(ui.tv("${diaCurto(t)}, ${hm(t)}", 15f, pal.ink, 600, num = true), ui.lp(top = 3))
            val est = ui.tv("", 12f, pal.acc, 500)
            c.addView(est, ui.lp(top = 3))
            estados.add(Triple(est, t, rot))
            alarmes.addView(c, ui.lp(0, WRAP, peso = 1f))
        }
        v.addView(alarmes)

        val hoje = LocalDate.now()
        val atualizar = {
            val now = System.currentTimeMillis().coerceAtLeast(d.now)
            val emCurso = prox.inicio <= now
            val alvo = if (emCurso) prox.fim - now else prox.inicio - now
            rotulo.text = if (emCurso) "Termina em" else "Começa em"
            cont.text = contagem(alvo)
            cont.setTextColor(if (!emCurso && alvo < 2 * HOUR) pal.choque else pal.ink)
            status.text = when {
                emCurso -> "Em andamento"
                dt == hoje -> "Hoje"
                dt == hoje.plusDays(1) -> "Amanhã"
                else -> "Em ${ChronoUnit.DAYS.between(hoje, dt)} dias"
            }
            if (emCurso) {
                barra.visibility = View.VISIBLE
                progresso((now - prox.inicio).toFloat() / (prox.fim - prox.inicio))
                legendaBarra.text = "${horas((now - prox.inicio) / MIN)} de ${horas(prox.durMin.toLong())}"
            } else if (alvo < DAY) {
                barra.visibility = View.VISIBLE
                progresso((DAY - alvo).toFloat() / DAY)
                legendaBarra.text = "Últimas 24 h antes do plantão"
            } else {
                barra.visibility = View.GONE; legendaBarra.visibility = View.GONE
            }
            if (barra.visibility == View.VISIBLE) legendaBarra.visibility = View.VISIBLE
            for ((tv, t, _) in estados) {
                tv.text = if (t <= now) "Já tocou" else "Agendado"
                tv.setTextColor(if (t <= now) pal.mute else pal.acc)
            }
        }
        atualizar()
        tickers.add(atualizar)
        card.addView(v)
        return card
    }

    private fun faixaSemana(d: Dados, hoje: LocalDate, conf: Set<String>): View {
        val card = ui.card()
        val r = ui.lin(Gravity.TOP)
        r.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
        val letras = arrayOf("D", "S", "T", "Q", "Q", "S", "S")
        for (i in 0..6) {
            val dia = hoje.plusDays(i.toLong())
            val ps = d.lista.filter { it.inicio.ldt().toLocalDate() == dia }
            val c = ui.col()
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(ui.tv(letras[dia.dayOfWeek.value % 7], 12f, pal.mute, 500).apply { gravity = Gravity.CENTER })
            val n = ui.tv(dia.dayOfMonth.toString(), 16f, if (i == 0) android.graphics.Color.WHITE else pal.ink, 600, num = true).apply {
                gravity = Gravity.CENTER
                if (i == 0) background = ui.fundo(pal.acc, 16f)
            }
            c.addView(n, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { topMargin = ui.dp(6) })
            val barras = ui.col()
            for (p in ps.take(3)) barras.addView(View(ui.c).apply { background = ui.fundo(cor(p, conf), 2f) }, ui.lp(h = ui.dp(4), top = 4))
            c.addView(barras, LinearLayout.LayoutParams(ui.dp(26), WRAP).apply { topMargin = ui.dp(4) })
            val h = ps.sumOf { it.durMin }
            c.addView(ui.tv(if (h == 0) "livre" else "${h / 60}h", 11f, if (h == 0) pal.livreTexto else pal.mute, 500, num = true).apply { gravity = Gravity.CENTER }, ui.lp(top = 6))
            c.setOnClickListener { semana = inicioSemana(dia); calMes = false; a.irPara(2) }
            r.addView(c, ui.lp(0, WRAP, peso = 1f))
        }
        card.addView(r)
        return card
    }

    private fun estatisticas(itens: List<Pair<String, String>>): View {
        val r = ui.lin(Gravity.TOP)
        itens.forEachIndexed { i, (num, rot) ->
            val c = ui.card(raio = 14f)
            val v = ui.col(14, 14)
            v.addView(ui.tv(num, 20f, pal.ink, 700, num = true))
            v.addView(ui.tv(rot, 12f, pal.mute, 500), ui.lp(top = 4))
            c.addView(v)
            r.addView(c, ui.lp(0, WRAP, start = if (i == 0) 0 else 8, peso = 1f))
        }
        return r
    }

    private fun aviso(titulo: String, texto: String, botao: String, onClick: () -> Unit): View {
        val c = ui.card(pal.choqueFundo, pal.choqueBorda)
        val r = ui.lin()
        r.setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(12))
        val t = ui.col()
        t.addView(ui.tv(titulo, 15f, pal.choque, 600))
        t.addView(ui.tv(texto, 13f, pal.ink2), ui.lp(top = 3))
        r.addView(t, ui.lp(0, WRAP, peso = 1f))
        r.addView(botaoTexto(botao, pal.choque, onClick))
        c.addView(r)
        return c
    }

    private fun botaoTexto(t: String, cor: Int = pal.acc, onClick: () -> Unit) =
        MaterialButton(ui.c, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = t; isAllCaps = false; setTextColor(cor); textSize = 14f
            setOnClickListener { onClick() }
        }

    private fun vazio(titulo: String, sub: String): View {
        val c = ui.card(pal.surface, pal.line)
        val r = ui.lin()
        r.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        r.addView(ui.ponto(pal.livre, 8))
        r.addView(ui.tv(titulo, 15f, pal.ink2, 500), ui.lp(0, WRAP, start = 10, peso = 1f))
        r.addView(ui.tv(sub, 13f, pal.livreTexto, 600))
        c.addView(r)
        return c
    }

    /** Uma linha da agenda: horário, faixa colorida do hospital, nome e lixeira. */
    fun itemRow(p: Plantao, choque: Boolean, now: Long, esmaecer: Boolean = true): View {
        val c = if (choque) ui.card(pal.choqueFundo, pal.choqueBorda) else ui.card()
        if (esmaecer && p.fim < now) c.alpha = 0.55f
        val r = ui.lin(Gravity.TOP)
        r.setPadding(ui.dp(16), ui.dp(14), ui.dp(4), ui.dp(14))
        val t = ui.col()
        t.addView(ui.tv(hm(p.inicio), 17f, pal.ink, 600, num = true))
        val vira = if (p.fim.ldt().toLocalDate() != p.inicio.ldt().toLocalDate()) " +1" else ""
        t.addView(ui.tv(hm(p.fim) + vira, 13f, pal.mute, 400, num = true), ui.lp(top = 4))
        r.addView(t, LinearLayout.LayoutParams(ui.dp(58), WRAP))
        r.addView(View(ui.c).apply { background = ui.fundo(if (choque) pal.choque else corDo(p.local), 2f) },
            LinearLayout.LayoutParams(ui.dp(3), MATCH).apply { marginEnd = ui.dp(14) })
        val info = ui.col()
        info.addView(ui.tv(p.local, 16f, pal.ink, 600))
        val linhaMeta = listOfNotNull(p.setor.takeIf { it.isNotBlank() }, horas(p.durMin.toLong()))
        info.addView(ui.tv(linhaMeta.joinToString(" · "), 13f, pal.mute, 400), ui.lp(top = 4))
        if (p.obs.isNotBlank()) info.addView(ui.tv(p.obs, 13f, pal.ink2, 400), ui.lp(top = 4))
        if (choque) info.addView(ui.pill("Choque de horário", pal.choque), ui.lp(WRAP, WRAP, top = 8))
        if (p.lancado || p.valor > 0) {
            val partes = mutableListOf<String>()
            if (p.fichas != null) partes += "${p.fichas} fichas"
            if ((p.uti ?: 0) > 0) partes += "${p.uti} UTI"
            partes += brl0(p.ganho)
            partes += "${brl0(p.porHora)}/h"
            val corG = if (p.porHora >= meta) pal.livreTexto else pal.alerta
            if (p.inicio <= now) info.addView(ui.pill(partes.joinToString(" · "), corG), ui.lp(WRAP, WRAP, top = 8))
        } else if (p.inicio <= now) {
            info.addView(botaoTexto("Lançar fichas") { a.lancar(p) }.apply {
                setPadding(0, 0, 0, 0); minHeight = ui.dp(32); minimumHeight = ui.dp(32)
            }, ui.lp(WRAP, WRAP, top = 2))
        }
        r.addView(info, ui.lp(0, WRAP, peso = 1f))
        r.addView(ui.icone(R.drawable.ic_delete, "Excluir só este plantão") { a.excluir(p) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        c.addView(r)
        c.setOnClickListener { a.editar(p) }
        c.setOnLongClickListener { a.excluir(p); true }
        return c
    }

    // ======================= AGENDA =======================
    fun agenda(d: Dados): View {
        tickers.clear(); meta = d.meta
        val v = ui.col()
        val fut = d.lista.filter { it.fim > d.now }
        v.addView(ui.cabecalho("Agenda",
            if (fut.isEmpty()) "Nenhum plantão pela frente" else "${fut.size} plantões pela frente · ${horas(fut.sumOf { it.durMin.toLong() })}"))
        val conf = Store.conflitos(fut)
        if (conf.isNotEmpty()) v.addView(aviso("Choque de horário", "${conf.size} plantões se sobrepõem. Estão marcados em vermelho.", "Calendário") { calMes = false; a.irPara(2) })
        val lista = d.lista.filter { it.fim > d.now - 12 * HOUR }
        if (lista.isEmpty()) {
            v.addView(vazioGrande("Agenda vazia", "Toque em Plantão para cadastrar, ou em Ajustes › Receber plantões da página."))
            return v
        }
        val hoje = d.now.ldt().toLocalDate()
        for ((dia, ps) in lista.groupBy { it.inicio.ldt().toLocalDate() }) {
            val rel = when (dia) { hoje -> "Hoje · "; hoje.plusDays(1) -> "Amanhã · "; else -> "" }
            v.addView(ui.secao(rel + diaCurto(dia), horas(ps.sumOf { it.durMin.toLong() })))
            ps.forEach { v.addView(itemRow(it, it.id in conf, d.now)) }
        }
        return v
    }

    private fun vazioGrande(t: String, s: String): View {
        val c = ui.card()
        val v = ui.col(20, 28)
        v.gravity = Gravity.CENTER_HORIZONTAL
        v.addView(ui.tv(t, 18f, pal.ink, 600).apply { gravity = Gravity.CENTER })
        v.addView(ui.tv(s, 14f, pal.mute).apply { gravity = Gravity.CENTER }, ui.lp(top = 6))
        c.addView(v)
        return c
    }

    // ======================= CALENDÁRIO =======================
    fun calendario(d: Dados): View {
        tickers.clear(); meta = d.meta
        val v = ui.col()
        v.addView(ui.cabecalho("Calendário"))
        val tg = MaterialButtonToggleGroup(ui.c).apply { isSingleSelection = true; isSelectionRequired = true }
        val estilo = com.google.android.material.R.attr.materialButtonOutlinedStyle
        val bS = MaterialButton(ui.c, null, estilo).apply { text = "Semana"; isAllCaps = false; id = View.generateViewId() }
        val bM = MaterialButton(ui.c, null, estilo).apply { text = "Mês"; isAllCaps = false; id = View.generateViewId() }
        val bL = MaterialButton(ui.c, null, estilo).apply { text = "Livres"; isAllCaps = false; id = View.generateViewId() }
        val ids = listOf(bS.id, bM.id, bL.id)
        tg.addView(bS, ui.lp(0, WRAP, peso = 1f)); tg.addView(bM, ui.lp(0, WRAP, peso = 1f)); tg.addView(bL, ui.lp(0, WRAP, peso = 1f))
        tg.check(ids[calModo])
        tg.addOnButtonCheckedListener { _, id, checked ->
            val novo = ids.indexOf(id)
            if (checked && novo != calModo) { calModo = novo; tg.post { a.redesenhar() } }
        }
        v.addView(tg, ui.lp(bottom = 12))
        if (calModo == 2) { livresConteudo(v, d); return v }

        val nav = ui.lin()
        val titulo = if (calMes) mesAno(mes) else "${diaCurto(semana)} – ${diaCurto(semana.plusDays(6))}"
        nav.addView(ui.icone(R.drawable.ic_ant, "Anterior", pal.ink2) {
            if (calMes) mes = mes.minusMonths(1) else semana = semana.minusWeeks(1); a.redesenhar()
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        nav.addView(ui.tv(titulo, 16f, pal.ink, 600, num = true).apply { gravity = Gravity.CENTER }, ui.lp(0, WRAP, peso = 1f))
        nav.addView(botaoTexto("Hoje") {
            semana = inicioSemana(LocalDate.now()); mes = YearMonth.now(); diaSel = LocalDate.now(); a.redesenhar()
        })
        nav.addView(ui.icone(R.drawable.ic_prox, "Próximo", pal.ink2) {
            if (calMes) mes = mes.plusMonths(1) else semana = semana.plusWeeks(1); a.redesenhar()
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        v.addView(nav, ui.lp(bottom = 8))

        val lista = d.lista
        val conf = Store.conflitos(lista)
        val card = ui.card()
        if (!calMes) {
            card.addView(WeekView(ui.c, pal).apply {
                semanaIni = semana; dados = lista; descansoMin = d.descansoMin; choques = conf; agora = d.now
                onPlantao = { a.editar(it) }
                onDia = { dd -> diaSel = dd; mes = YearMonth.from(dd); calMes = true; a.redesenhar() }
            })
            v.addView(card)
            val ini = semana.ms(); val fim = semana.plusDays(7).ms()
            val trab = lista.filter { it.inicio in ini until fim }
            val livre = Livre.janelas(lista, ini, fim, d.descansoMin).sumOf { it.fim - it.ini } / MIN
            v.addView(legenda(trab, conf), ui.lp(top = 4))
            v.addView(estatisticas(listOf(trab.size.toString() to "plantões", horas(trab.sumOf { it.durMin.toLong() }) to "de trabalho", horasCurto(livre) to "livres")), ui.lp(top = 8))
        } else {
            card.addView(MonthView(ui.c, pal).apply {
                mesAtual = mes; dados = lista; descansoMin = d.descansoMin; choques = conf; selecionado = diaSel
                onDia = { dd -> diaSel = dd; if (YearMonth.from(dd) != mes) mes = YearMonth.from(dd); a.redesenhar() }
            }, android.widget.FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            v.addView(card)
            val ini = mes.atDay(1).ms(); val fim = mes.plusMonths(1).atDay(1).ms()
            val noMes = lista.filter { it.inicio in ini until fim }
            v.addView(legenda(noMes, conf), ui.lp(top = 4))
            detalheDia(v, d, diaSel, conf)
        }
        return v
    }

    private fun legenda(ps: List<Plantao>, conf: Set<String>): View {
        val g = ChipGroup(ui.c).apply { chipSpacingHorizontal = ui.dp(14); chipSpacingVertical = ui.dp(6) }
        fun item(t: String, cor: Int) = ui.lin().apply {
            addView(ui.ponto(cor, 9)); addView(ui.tv(t, 13f, pal.ink2, 500), ui.lp(WRAP, WRAP, start = 6))
        }
        ps.map { it.local }.distinct().sorted().forEach { g.addView(item(it, corDo(it))) }
        if (ps.any { it.id in conf }) g.addView(item("Choque de horário", pal.choque))
        g.addView(item("Livre", pal.livre))
        return g
    }

    private fun detalheDia(v: LinearLayout, d: Dados, dia: LocalDate, conf: Set<String>) {
        val ds = dia.ms(); val de = dia.plusDays(1).ms()
        val doDia = d.lista.filter { it.inicio < de && it.fim > ds }
        v.addView(ui.secao(diaLongo(dia), if (doDia.isEmpty()) null else horas(doDia.sumOf { it.durMin.toLong() })))
        if (doDia.isEmpty()) { v.addView(livreCard(Janela(ds, de, null, null), "Dia inteiro livre")); return }
        val js = Livre.janelas(d.lista, ds, de, d.descansoMin).filter { it.min >= 30 }
        val eventos = doDia.map { maxOf(it.inicio, ds) to (it as Any) } + js.map { it.ini to (it as Any) }
        for ((_, e) in eventos.sortedBy { it.first }) {
            if (e is Plantao) v.addView(itemRow(e, e.id in conf, d.now)) else if (e is Janela) v.addView(livreCard(e))
        }
    }

    // ======================= LIVRES =======================
    private fun livresConteudo(v: LinearLayout, d: Dados) {
        v.addView(ui.tv("Intervalos sem plantão nos próximos 30 dias", 14f, pal.mute), ui.lp(bottom = 10))
        val filtros = ui.card()
        val f = ui.col(16, 14)
        f.addView(ui.tv("Intervalo mínimo", 13f, pal.mute, 500))
        f.addView(escolhas(listOf(2, 4, 6, 12, 24), d.minLivreH) { a.mudarFiltroLivre(it, null) }, ui.lp(top = 6, bottom = 10))
        f.addView(ui.tv("Descanso após cada plantão", 13f, pal.mute, 500))
        f.addView(escolhas(listOf(0, 4, 6, 8, 12), d.descansoMin / 60) { a.mudarFiltroLivre(null, it) }, ui.lp(top = 6))
        filtros.addView(f)
        v.addView(filtros)

        val ate = d.now.ldt().toLocalDate().plusDays(31).ms()
        val js = Livre.janelas(d.lista, d.now, ate, d.descansoMin).filter { it.min >= d.minLivreH * 60L }
        val total = js.sumOf { it.min }
        val maior = js.maxByOrNull { it.min }
        v.addView(estatisticas(listOf(
            horasCurto(total) to "livres no total",
            js.size.toString() to "intervalos",
            (maior?.let { horasCurto(it.min) } ?: "–") to "o maior",
        )), ui.lp(top = 4))
        if (js.isEmpty()) { v.addView(vazioGrande("Nenhum intervalo desse tamanho", "Diminua o intervalo mínimo acima.")); return }
        for ((dia, lista) in js.groupBy { it.ini.ldt().toLocalDate() }) {
            v.addView(ui.secao(diaCurto(dia)))
            lista.forEach { v.addView(livreCard(it)) }
        }
    }

    private fun escolhas(vals: List<Int>, sel: Int, cb: (Int) -> Unit): View {
        val g = ChipGroup(ui.c).apply { isSingleSelection = true; isSelectionRequired = true; chipSpacingHorizontal = ui.dp(8) }
        for (x in vals) {
            val ch = android.view.LayoutInflater.from(ui.c).inflate(R.layout.chip_filtro, g, false) as Chip
            ch.text = if (x == 0) "Nenhum" else "$x h"
            ch.isChecked = x == sel
            ch.setOnClickListener { if (x != sel) cb(x) }
            g.addView(ch)
        }
        return g
    }

    fun livreCard(j: Janela, rotulo: String? = null): View {
        val c = ui.card(pal.livreFundo, ColorUtils.setAlphaComponent(pal.livre, 80))
        val v = ui.col(16, 14)
        val r = ui.lin()
        val a1 = j.ini.ldt(); val b1 = j.fim.ldt()
        val fimMeiaNoite = b1.toLocalDate() == a1.toLocalDate().plusDays(1) && b1.hour == 0 && b1.minute == 0
        val mesmoDia = a1.toLocalDate() == b1.toLocalDate() || fimMeiaNoite
        val tt = ui.col()
        tt.addView(ui.tv(rotulo ?: "${hm(j.ini)} → ${if (fimMeiaNoite) "24:00" else hm(j.fim)}", 16f, pal.ink, 600, num = true))
        if (!mesmoDia && rotulo == null) tt.addView(ui.tv("${diaCurto(j.ini)} → ${diaCurto(j.fim)}", 13f, pal.mute, 400, num = true), ui.lp(top = 3))
        r.addView(tt, ui.lp(0, WRAP, peso = 1f))
        r.addView(ui.tv(horas(j.min), 17f, pal.livreTexto, 700, num = true))
        v.addView(r)
        val ctx = listOfNotNull(
            j.antes?.let { "Depois de ${it.local} (termina ${hm(it.fim)})" },
            j.depois?.let { "Antes de ${it.local} (começa ${hm(it.inicio)})" }
        )
        if (ctx.isNotEmpty()) v.addView(ui.tv(ctx.joinToString("\n"), 13f, pal.ink2), ui.lp(top = 6))
        v.addView(botaoTexto("Encaixar plantão", pal.livreTexto) {
            val l = j.ini.ldt()
            val ini = (if (l.minute == 0) l else l.truncatedTo(ChronoUnit.HOURS).plusHours(1)).ms()
            a.editar(null, ini)
        }.apply { setPadding(0, 0, 0, 0); minHeight = ui.dp(36); minimumHeight = ui.dp(36) }, ui.lp(WRAP, WRAP, top = 4))
        c.addView(v)
        return c
    }

    /** Plantões que já começaram e ainda não tiveram fichas lançadas (últimos 30 dias, sem valor fixo). */
    private fun pendentes(d: Dados) =
        d.lista.filter { it.inicio <= d.now && !it.lancado && it.valor <= 0 && it.fim > d.now - 30 * DAY }
            .sortedByDescending { it.inicio }

    // ======================= GANHOS =======================
    fun ganhos(d: Dados): View {
        tickers.clear(); meta = d.meta
        val v = ui.col()
        v.addView(ui.cabecalho("Ganhos"))
        val nav = ui.lin()
        nav.addView(ui.icone(R.drawable.ic_ant, "Mês anterior", pal.ink2) { mesGanhos = mesGanhos.minusMonths(1); a.redesenhar() },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        nav.addView(ui.tv(mesAno(mesGanhos), 16f, pal.ink, 600).apply { gravity = Gravity.CENTER }, ui.lp(0, WRAP, peso = 1f))
        nav.addView(ui.icone(R.drawable.ic_prox, "Próximo mês", pal.ink2) { mesGanhos = mesGanhos.plusMonths(1); a.redesenhar() },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        v.addView(nav, ui.lp(bottom = 8))

        val ini = mesGanhos.atDay(1).ms(); val fim = mesGanhos.plusMonths(1).atDay(1).ms()
        val doMes = d.lista.filter { it.inicio in ini until fim }
        val feitos = doMes.filter { it.inicio <= d.now }
        val comGanho = feitos.filter { it.temGanho }
        val total = comGanho.sumOf { it.ganho }
        val horasG = comGanho.sumOf { it.horas }
        val media = if (horasG > 0) total / horasG else 0.0

        // cartão principal: total e média por hora contra a meta
        val card = ui.card(raio = 20f)
        val c = ui.col(20, 20)
        c.addView(ui.tv("Total no mês", 13f, pal.mute, 500))
        c.addView(ui.tv(brl0(total), 40f, pal.ink, 700, num = true), ui.lp(top = 4))
        val linhaMedia = ui.lin()
        linhaMedia.addView(ui.tv(if (horasG > 0) "${brl0(media)}/h em média" else "Sem fichas lançadas ainda", 15f, pal.ink2, 600, num = true), ui.lp(0, WRAP, peso = 1f))
        linhaMedia.addView(ui.tv("meta ${brl0(d.meta)}/h", 13f, pal.mute, 500, num = true))
        c.addView(linhaMedia, ui.lp(top = 12))
        val ok = media >= d.meta
        val corM = if (ok) pal.livre else pal.alerta
        val barra = ui.lin().apply { background = ui.fundo(pal.surface2, 4f) }
        val fr = if (d.meta > 0) (media / d.meta).toFloat().coerceIn(0.02f, 1f) else 1f
        barra.addView(View(ui.c).apply { background = ui.fundo(corM, 4f) }, LinearLayout.LayoutParams(0, ui.dp(8), fr))
        barra.addView(View(ui.c), LinearLayout.LayoutParams(0, ui.dp(8), 1f - fr))
        c.addView(barra, ui.lp(top = 10))
        if (horasG > 0) c.addView(ui.tv(
            if (ok) "Acima da meta: ${brl0(media - d.meta)}/h a mais" else "Abaixo da meta: faltam ${brl0(d.meta - media)}/h",
            13f, if (ok) pal.livreTexto else pal.alerta, 600), ui.lp(top = 8))
        card.addView(c)
        v.addView(card)

        v.addView(estatisticas(listOf(
            "${comGanho.size}/${feitos.size}" to "plantões lançados",
            (comGanho.sumOf { it.fichas ?: 0 }).toString() to "fichas",
            (comGanho.sumOf { it.uti ?: 0 }).toString() to "UTI",
        )), ui.lp(top = 4))

        val pend = feitos.filter { !it.temGanho }
        if (pend.isNotEmpty()) v.addView(avisoNeutro("${pend.size} plantões sem fichas lançadas", "O total sobe quando você lançar.", "Lançar") { a.lancar(pend.first()) })

        // por hospital
        if (feitos.isNotEmpty()) {
            v.addView(ui.secao("Por hospital"))
            val linhas = feitos.groupBy { it.local }.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (local, ps) ->
                val g = ps.filter { it.temGanho }
                val tot = g.sumOf { it.ganho }; val h = g.sumOf { it.horas }
                val r = ui.lin()
                r.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                r.addView(ui.ponto(corDo(local), 10))
                val t = ui.col()
                t.addView(ui.tv(local, 15f, pal.ink, 600))
                val regra = d.regras[local]
                val semValor = (regra == null || !regra.configurada) && ps.any { !it.temGanho }
                val sub = "${ps.size} plantões · ${horas(ps.sumOf { it.durMin.toLong() })}" + if (semValor) " · configure os valores" else ""
                t.addView(ui.tv(sub, 12f, if (semValor) pal.alerta else pal.mute), ui.lp(top = 2))
                if (semValor) r.setOnClickListener { a.editarRegra(local) }
                r.addView(t, ui.lp(0, WRAP, start = 12, peso = 1f))
                val dir = ui.col(); dir.gravity = Gravity.END
                dir.addView(ui.tv(brl0(tot), 15f, pal.ink, 700, num = true).apply { gravity = Gravity.END; maxLines = 1; isSingleLine = true })
                if (h > 0) dir.addView(ui.tv("${brl0(tot / h)}/h", 12f, if (tot / h >= d.meta) pal.livreTexto else pal.alerta, 600, num = true).apply { gravity = Gravity.END }, ui.lp(WRAP, WRAP, top = 2))
                r.addView(dir, ui.lp(WRAP, WRAP, start = 8))
                r
            }
            v.addView(ui.grupo(*linhas.toTypedArray()))

            v.addView(ui.secao("Plantões do mês"))
            feitos.sortedByDescending { it.inicio }.forEach { v.addView(itemRow(it, false, d.now, esmaecer = false)) }
        } else v.addView(vazioGrande("Nenhum plantão neste mês ainda", "Os ganhos aparecem aqui depois de lançar as fichas."))
        return v
    }

    private fun avisoNeutro(titulo: String, texto: String, botao: String, onClick: () -> Unit): View {
        val c = ui.card(pal.accClaro, pal.accClaro)
        val r = ui.lin()
        r.setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(12))
        val t = ui.col()
        t.addView(ui.tv(titulo, 15f, pal.accEscuro, 600))
        t.addView(ui.tv(texto, 13f, pal.ink2), ui.lp(top = 3))
        r.addView(t, ui.lp(0, WRAP, peso = 1f))
        r.addView(botaoTexto(botao, pal.acc, onClick))
        c.addView(r)
        return c
    }

    // ======================= AJUSTES =======================
    fun ajustes(d: Dados, temaAtual: String): View {
        tickers.clear()
        val v = ui.col()
        v.addView(ui.cabecalho("Ajustes"))

        v.addView(ui.secao("Aparência"))
        val temas = ui.card()
        val tr = ui.lin()
        tr.setPadding(ui.dp(8), ui.dp(16), ui.dp(8), ui.dp(16))
        for (t in Temas.lista) {
            val c = ui.col()
            c.gravity = Gravity.CENTER_HORIZONTAL
            val sel = t.id == temaAtual
            val bola = android.widget.FrameLayout(ui.c).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(t.clara)
                    setStroke(ui.dp(if (sel) 3 else 1), if (sel) t.primaria else pal.line)
                }
                addView(View(ui.c).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(t.primaria) }
                }, android.widget.FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
            }
            c.addView(bola, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            c.addView(ui.tv(t.nome, 13f, if (sel) pal.ink else pal.mute, if (sel) 600 else 500), ui.lp(WRAP, WRAP, top = 8))
            c.setOnClickListener { if (!sel) a.mudarTema(t.id) }
            tr.addView(c, ui.lp(0, WRAP, peso = 1f))
        }
        temas.addView(tr)
        v.addView(temas)

        v.addView(ui.secao("Ganhos"))
        val locais = (d.lista.map { it.local } + d.regras.keys).map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val linhasG = mutableListOf(ui.linha("Meta por hora", "${brl0(d.meta)} por hora trabalhada") { a.editarMeta() })
        for (l in locais) {
            val r = d.regras[l]
            val sub = if (r == null || !r.configurada) "Toque para informar quanto paga"
            else listOf("${brl0(r.porFicha)} por atendimento", if (r.porUti > 0) "${brl0(r.porUti)} por UTI" else "sem UTI").joinToString(" · ")
            linhasG += ui.linha(l, sub, if (r == null || !r.configurada) pal.alerta else pal.mute) { a.editarRegra(l) }
        }
        v.addView(ui.grupo(*linhasG.toTypedArray()))

        v.addView(ui.secao("Alarmes"))
        val ok = d.faltas.isEmpty()
        val chave = MaterialSwitch(ui.c).apply { isChecked = d.volMax; setOnCheckedChangeListener { _, x -> a.volumeMaximo(x) } }
        v.addView(ui.grupo(
            ui.linha("Permissões", if (ok) "Tudo certo para tocar com a tela bloqueada" else "Falta: ${d.faltas.joinToString(", ")}",
                if (ok) pal.livreTexto else pal.choque) { a.permissoes() },
            ui.linha("Volume máximo no alarme", "Sobe o volume do alarme enquanto toca", direita = chave),
            ui.linha("Testar alarme", "Toca em 5 segundos. Bloqueie a tela para ver") { a.testarAlarme() },
            ui.linha("Como funcionam os alarmes", "1 dia antes e 2 horas antes de cada plantão") { a.ajuda() },
        ))

        v.addView(ui.secao("Sincronizar com a página"))
        v.addView(ui.grupo(
            ui.linha("Receber plantões da página", "Escolha o arquivo baixado em \"Enviar para o app\"") { a.receber() },
            ui.linha("Enviar plantões para a página", "Gera um arquivo para abrir na página") { a.enviar() },
        ))

        v.addView(ui.secao("Plantões"))
        v.addView(ui.grupo(
            ui.linha("Excluir vários plantões", "Marque exatamente quais quer apagar") { a.excluirVarios() },
        ))

        v.addView(ui.secao("Aplicativo"))
        v.addView(ui.grupo(
            ui.linha("Procurar atualização", "Versão ${d.versao}") { a.procurarAtualizacao() },
        ))
        return v
    }
}
