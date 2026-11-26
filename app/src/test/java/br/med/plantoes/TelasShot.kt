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
        var d = hoje.minusDays(30)
        while (d.isBefore(hoje.plusDays(40))) {
            when (d.dayOfWeek.value) {
                1, 4 -> out += p("Santa Clara Sul", "Noturno", d, 19, 12)
                2 -> out += p("Hospital Central", "Tarde e noite", d, 13, 18)
                3 -> out += p("Santa Maria", "", d, 8, 12)
                6 -> { out += p("Hospital Central", "Manhã", d, 7, 6); out += p("Hospital Central", "Noite", d, 19, 12) }
                7 -> out += p("Santa Maria", "", d, 7, 12)
            }
            d = d.plusDays(1)
        }
        out += p("Clínica Norte", "Cinderela", hoje.plusDays(3), 19, 6) // sábado: choca com Hospital Central noite
        // fichas lançadas nos plantões já encerrados (valores fictícios)
        out.sortedBy { it.inicio }.mapIndexed { i, x ->
            if (x.fim > agora || i % 4 == 3) x
            else when (x.local) {
                "Santa Clara Sul" -> x.copy(fichas = 20 + i % 9, uti = i % 3, porFicha = 50.0, porUti = 150.0)
                "Santa Maria" -> x.copy(fichas = 18 + i % 7, porFicha = 50.0)
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
    }

    private fun dados(faltas: List<String> = emptyList(), dias: Long = 60, antes: Long = 400) =
        Dados(lista.filter { it.inicio < hoje.plusDays(dias).ms() && it.inicio > hoje.minusDays(antes).ms() }, agora, 0, 6, faltas, true, "1.5",
            mapOf("Santa Clara Sul" to Regra(50.0, 150.0), "Santa Maria" to Regra(50.0, 0.0)), 100.0)

    @Before fun cores() {
        Paleta.mapa.clear()
        listOf("Clínica Norte", "Hospital Central", "Santa Clara Sul", "Santa Maria").forEachIndexed { i, l -> Paleta.mapa[l] = i }
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
    @Test fun ganhos() = paparazzi.snapshot(tela { t -> t.mesGanhos = YearMonth.of(2026, 11); t.ganhos(dados(antes = 9)) })
    @Test fun ajustesVerde() = paparazzi.snapshot(tela("verde") { it.ajustes(dados(), "verde") })
    @Test fun inicioPendente() = paparazzi.snapshot(tela { t ->
        val d = dados(antes = 3)
        t.inicio(d.copy(lista = d.lista.map { if (it.fim < agora) it.copy(fichas = null, valor = 0.0) else it }))
    })
}
