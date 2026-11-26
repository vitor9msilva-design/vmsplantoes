package br.med.plantoes

import android.view.View
import android.widget.LinearLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Desenha as telas com uma agenda de exemplo (hospitais fictícios) para revisar o visual sem celular. */
class TelasShot {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "Theme.Plantoes.Azul",
        renderingMode = SessionParams.RenderingMode.V_SCROLL,
    )

    private val hoje = LocalDate.of(2026, 12, 2) // uma quarta-feira
    private val agora = hoje.atTime(14, 20).ms()

    private fun p(local: String, setor: String, d: LocalDate, h: Int, dur: Int) =
        Plantao(local = local, setor = setor, inicio = d.atTime(h, 0).ms(), durMin = dur * 60)

    private val lista: List<Plantao> by lazy {
        val out = mutableListOf<Plantao>()
        var d = hoje.minusDays(200)
        while (d.isBefore(hoje.plusDays(40))) {
            when (d.dayOfWeek.value) {
                1, 4 -> out += p("Santa Clara Sul", "Noturno", d, 19, 12)
                5 -> out += p("Hospital Regional", "", d, 7, 12)
                2 -> out += p("Hospital Central", "Tarde e noite", d, 13, 18)
                3 -> out += p("Santa Maria", "", d, 8, 12).copy(serieId = "sm")
                6 -> { out += p("Hospital Central", "Manhã", d, 7, 6); out += p("Hospital Central", "Noite", d, 19, 12) }
                7 -> out += p("Santa Maria", "", d, 7, 12)
            }
            d = d.plusDays(1)
        }
        out += p("Clínica Norte", "Cinderela", hoje.plusDays(3), 19, 6).copy(tipo = Plantao.AVULSO) // sábado: choca com Hospital Central noite
        out += p("Clínica Norte", "", hoje.minusDays(6), 7, 12).copy(tipo = Plantao.AVULSO, fichas = 30, porFicha = 60.0)
        out += p("Clínica Norte", "", hoje.plusDays(5), 7, 12).copy(situacao = Plantao.COBERTURA, colega = "Dra. Ana")
        out += p("Santa Maria", "", hoje.plusDays(1), 7, 12).copy(situacao = Plantao.REPASSADO, colega = "Dr. Paulo")
        // fichas lançadas nos plantões já encerrados (valores fictícios)
        out.sortedBy { it.inicio }.mapIndexed { i, x ->
            if (x.fim > agora || i % 4 == 3) x
            else when (x.local) {
                "Santa Clara Sul" -> x.copy(fichas = 20 + i % 9, uti = i % 3, porFicha = 50.0, porUti = 150.0)
                "Santa Maria" -> x.copy(fichas = 18 + i % 7, porFicha = 50.0)
                "Hospital Regional" -> x
                else -> x.copy(valor = 1200.0)
            }
        }
    }

    private val nada = object : Acoes {
        override fun editar(p: Plantao?, preset: Long?) {}
        override fun excluir(p: Plantao) {}
        override fun redesenhar() {}
        override fun irPara(aba: Int) {}
        override fun permissoes() {}
        override fun testarAlarme() {}
        override fun ajuda() {}
        override fun receber() {}
        override fun enviar() {}
        override fun excluirVarios() {}
        override fun procurarAtualizacao() {}
        override fun mudarTema(id: String) {}
        override fun volumeMaximo(v: Boolean) {}
        override fun mudarFiltroLivre(minH: Int?, descansoH: Int?) {}
        override fun lancar(p: Plantao) {}
        override fun editarRegra(local: String) {}
        override fun editarMeta() {}
        override fun marcarRecebido(chave: String, local: String, mesTxt: String, esperado: Double) {}
        override fun exportar(ano: Int, mes: Int?) {}
        override fun editarFixo(r: RendaFixa?) {}
        override fun editarMetaMes() {}
        override fun bloqueio(ligar: Boolean) {}
        override fun backup() {}
        override fun restaurar() {}
        override fun editarSerie(s: Serie) {}
        override fun abrirDia(dia: LocalDate) {}
        override fun tornarFixo(p: Plantao) {}
        override fun automatizar() {}
    }

    private fun dados(faltas: List<String> = emptyList(), dias: Long = 60, antes: Long = 400) =
        Dados(lista.filter { it.inicio < hoje.plusDays(dias).ms() && it.inicio > hoje.minusDays(antes).ms() }, agora, 0, 6, faltas, true, "1.5",
            mapOf("Santa Clara Sul" to Regra(50.0, 150.0, 40, "carimbo e jaleco"), "Santa Maria" to Regra(50.0, 0.0, 25),
                "Hospital Central" to Regra(pagamento = Regra.PLANTAO, valorPlantao = 1200.0, deslocMin = 30),
                "Clínica Sul" to Regra(pagamento = Regra.HORA, valorHora = 110.0)), 100.0,
            mapOf(Recebimentos.chave(2026, 11, "Hospital Central") to Recebido(3600.0, hoje.minusDays(1).ms()),
                Recebimentos.chave(2026, 11, "Santa Clara Sul") to Recebido(3300.0, hoje.minusDays(1).ms()),
                Recebimentos.chave(2026, 11, "fixo:hr") to Recebido(9000.0, hoje.minusDays(3).ms())),
            listOf(RendaFixa("hr", "Contrato Hospital Regional", 9000.0, "2026-06", "", "Hospital Regional")), 0.0,
            series = listOf(Serie("sm", "Santa Maria", "", "08:00", 720, "2026-05-20", 7), Serie("cn", "Clínica Norte", "Manhã", "07:00", 360, "2026-12-03", 14)))

    @Before fun cores() {
        Paleta.mapa.clear()
        listOf("Clínica Norte", "Hospital Central", "Santa Clara Sul", "Santa Maria", "Hospital Regional").forEachIndexed { i, l -> Paleta.mapa[l] = i }
    }

    private fun tela(tema: String = "azul", f: (Telas) -> View): View {
        val ctx = paparazzi.context
        val ui = Ui(ctx, Pal(Temas.porId(tema)))
        val t = Telas(ui, nada)
        t.semana = t.inicioSemana(hoje); t.mes = YearMonth.from(hoje); t.diaSel = hoje.plusDays(3)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6F9.toInt())
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24))
            addView(f(t))
        }
    }

    @Test fun inicio() = paparazzi.snapshot(tela { it.inicio(dados(antes = 3)) })
    @Test fun agenda() = paparazzi.snapshot(tela { it.agenda(dados(listOf("notificações"), dias = 5, antes = 1)) })
    @Test fun semana() = paparazzi.snapshot(tela { it.calendario(dados()) })
    @Test fun mes() = paparazzi.snapshot(tela { t -> t.calMes = true; t.calendario(dados()) })
    @Test fun livres() = paparazzi.snapshot(tela { t -> t.calModo = 2; t.calendario(dados(dias = 4)) })
    @Test fun financeiroMes() = paparazzi.snapshot(tela { t -> t.mesGanhos = YearMonth.of(2026, 11); t.financeiro(dados(antes = 40).let { dd -> dd.copy(lista = dd.lista.filter { it.inicio >= LocalDate.of(2026, 11, 22).ms() || it.inicio < LocalDate.of(2026, 11, 1).ms() }) }) })
    @Test fun financeiroPrevisao() = paparazzi.snapshot(tela { t -> t.mesGanhos = YearMonth.of(2026, 12); t.financeiro(dados(dias = 8, antes = 40)) })
    @Test fun linhaDoTempo() = paparazzi.snapshot(tela { t -> t.finModo = 1; t.mesGanhos = YearMonth.of(2026, 12); t.financeiro(dados()) })
    @Test fun painelDia() = paparazzi.snapshot(tela { it.diaConteudo(dados(), hoje.plusDays(3)) })
    @Test fun ajustesVerde() = paparazzi.snapshot(tela("verde") { it.ajustes(dados(), "verde") })
    @Test fun inicioPendente() = paparazzi.snapshot(tela { t ->
        val d = dados(antes = 3)
        t.inicio(d.copy(lista = d.lista.map { if (it.fim < agora) it.copy(fichas = null, valor = 0.0) else it }))
    })
}
