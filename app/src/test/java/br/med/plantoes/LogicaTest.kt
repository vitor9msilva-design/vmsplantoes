package br.med.plantoes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicaTest {
    private fun p(local: String, data: String, h: Int, durH: Int) =
        Plantao(local = local, inicio = LocalDate.parse(data).atTime(h, 0).ms(), durMin = durH * 60)

    @Test fun janelasEntrePlantoes() {
        val l = listOf(p("A", "2027-01-04", 19, 12), p("B", "2027-01-05", 13, 18))
        val js = Livre.janelas(l, LocalDate.parse("2027-01-04").ms(), LocalDate.parse("2027-01-06").ms(), 0)
        // 00-19 livre, 07-13 livre, depois de 07h do dia 6 já está fora do intervalo
        assertEquals(listOf(19 * 60L, 6 * 60L), js.map { it.min })
        assertEquals("A", js[1].antes?.local); assertEquals("B", js[1].depois?.local)
    }

    @Test fun descansoReduzJanela() {
        val l = listOf(p("A", "2027-01-04", 19, 12), p("B", "2027-01-05", 13, 6))
        val js = Livre.janelas(l, LocalDate.parse("2027-01-05").ms(), LocalDate.parse("2027-01-06").ms(), 4 * 60)
        // A termina 07h + 4h descanso = 11h; livre 11-13; B 13-19 + 4h = 23h; livre 23-24
        assertEquals(listOf(2 * 60L, 60L), js.map { it.min })
    }

    @Test fun choqueSabado() {
        val l = listOf(p("Hospital B", "2027-01-09", 19, 12), p("Hospital C", "2027-01-09", 19, 6), p("X", "2027-01-10", 8, 2))
        assertEquals(setOf(l[0].id, l[1].id), Store.conflitos(l))
    }

    @Test fun diaLivreInteiro() {
        assertEquals(1440L, Livre.livreNoDia(emptyList(), LocalDate.parse("2027-01-06"), 0))
        val l = listOf(p("A", "2027-01-05", 19, 12))
        assertEquals(1440L - 7 * 60, Livre.livreNoDia(l, LocalDate.parse("2027-01-06"), 0))
        assertTrue(diaLongo(LocalDate.parse("2027-01-03")).startsWith("Domingo"))
    }
}

class AbrevTest {
    @Test fun nomesCurtos() {
        assertEquals("S. Clara", abrev("Santa Clara"))
        assertEquals("SM Norte", abrev("Santa Maria Norte"))
        assertEquals("SM Centená", abrev("Santa Maria Centenário"))
        assertEquals("Central", abrev("Central"))
    }
}

class GanhoTest {
    private val ini = LocalDate.parse("2027-01-04").atTime(19, 0).ms()

    @Test fun atendimentoMaisUti() {
        // 50 por atendimento + 150 por UTI, 12 h: 22 fichas e 1 UTI = 1.250 → 104,17/h
        val p = Plantao(local = "A", inicio = ini, durMin = 720, fichas = 22, uti = 1, porFicha = 50.0, porUti = 150.0)
        assertEquals(1250.0, p.ganho, 0.001)
        assertEquals(104.1667, p.porHora, 0.001)
    }

    @Test fun semUtiEValorFixo() {
        val p = Plantao(local = "B", inicio = ini, durMin = 360, fichas = 10, porFicha = 60.0)
        assertEquals(600.0, p.ganho, 0.001)
        val fixo = Plantao(local = "C", inicio = ini, durMin = 720, valor = 1200.0)
        assertTrue(fixo.temGanho); assertEquals(100.0, fixo.porHora, 0.001)
        val pendente = Plantao(local = "D", inicio = ini, durMin = 720)
        assertTrue(!pendente.temGanho)
    }
}

class JornadaTest {
    private fun p(dia: String, h: Int, dur: Int, sit: String = "") =
        Plantao(local = "X", inicio = LocalDate.parse(dia).atTime(h, 0).ms(), durMin = dur * 60, situacao = sit)

    @Test fun emendaComIntervaloCurto() {
        // terça 13h–07h, quarta 08h–20h: 1 h de intervalo conta como jornada contínua de 31 h
        val js = Jornadas.calcular(listOf(p("2027-01-05", 13, 18), p("2027-01-06", 8, 12)))
        assertEquals(1, js.size); assertEquals(31.0, js[0].horas, 0.01)
        assertEquals(1, Jornadas.longas(listOf(p("2027-01-05", 13, 18), p("2027-01-06", 8, 12))).size)
    }

    @Test fun repassadoNaoConta() {
        val l = listOf(p("2027-01-05", 13, 18), p("2027-01-06", 8, 12, Plantao.REPASSADO))
        assertTrue(Jornadas.longas(l).isEmpty())
        assertTrue(Store.conflitos(l + p("2027-01-06", 9, 2, Plantao.REPASSADO)).isEmpty())
        assertEquals(1440L, Livre.livreNoDia(listOf(p("2027-01-07", 8, 12, Plantao.REPASSADO)), LocalDate.parse("2027-01-07"), 0))
    }
}

class FixoTest {
    @Test fun mesesAtivos() {
        val r = RendaFixa(nome = "Contrato", valor = 5000.0, desde = "2026-03", ate = "2026-12")
        assertTrue(!r.ativaEm(java.time.YearMonth.of(2026, 2)))
        assertTrue(r.ativaEm(java.time.YearMonth.of(2026, 3)))
        assertTrue(r.ativaEm(java.time.YearMonth.of(2026, 12)))
        assertTrue(!r.ativaEm(java.time.YearMonth.of(2027, 1)))
        assertTrue(RendaFixa(nome = "C", valor = 1.0, desde = "2026-03").ativaEm(java.time.YearMonth.of(2030, 5)))
    }
}

class SerieTest {
    private val s = Serie(id = "x", local = "Hospital A", hora = "07:00", durMin = 360, inicio = "2026-12-03", intervalo = 14)

    @Test fun quinzenalSemFim() {
        val ds = s.ocorrencias(LocalDate.parse("2026-12-01"), LocalDate.parse("2027-02-01")).map { it.toString() }
        assertEquals(listOf("2026-12-03", "2026-12-17", "2026-12-31", "2027-01-14", "2027-01-28"), ds)
        // continua no futuro distante, sempre no mesmo dia da semana
        val longe = s.ocorrencias(LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-31"))
        assertTrue(longe.isNotEmpty() && longe.all { it.dayOfWeek == LocalDate.parse("2026-12-03").dayOfWeek })
    }

    @Test fun comecaNoMeioEPulaExcecoes() {
        val ds = s.copy(excecoes = setOf("2026-12-31")).ocorrencias(LocalDate.parse("2026-12-18"), LocalDate.parse("2027-01-20"))
        assertEquals(listOf(LocalDate.parse("2027-01-14")), ds)
        val fim = s.copy(ate = "2026-12-20").ocorrencias(LocalDate.parse("2026-12-01"), LocalDate.parse("2027-03-01"))
        assertEquals(2, fim.size)
    }

    @Test fun plantaoGeradoTemIdEstavel() {
        val p = Series.plantao(s, LocalDate.parse("2026-12-17"))
        assertEquals("s:x:2026-12-17", p.id); assertEquals(Plantao.FIXO, p.tipo); assertEquals(LocalDate.parse("2026-12-17"), Series.dataDe(p))
        assertTrue(s.descricao().contains("a cada 15 dias"))
    }
}

class PadraoTest {
    private fun p(d: String, h: Int, local: String = "Hospital A", dur: Int = 12) =
        Plantao(local = local, inicio = LocalDate.parse(d).atTime(h, 0).ms(), durMin = dur * 60)

    @Test fun achaSemanalEQuinzenal() {
        val l = listOf(
            p("2027-01-06", 8), p("2027-01-13", 8), p("2027-01-20", 8),                  // quartas, toda semana
            p("2027-01-07", 7, "Hospital B", 6), p("2027-01-21", 7, "Hospital B", 6),      // quintas, a cada 15 dias
            p("2027-01-08", 19, "Hospital C"),                                             // só uma vez: não é padrão
        )
        val ps = Series.padroes(l, LocalDate.parse("2027-01-01"))
        assertEquals(2, ps.size)
        assertEquals(7, ps.first { it.local == "Hospital A" }.intervalo)
        assertEquals(14, ps.first { it.local == "Hospital B" }.intervalo)
        assertEquals("2027-01-07", ps.first { it.local == "Hospital B" }.primeira.toString())
    }

    @Test fun ignoraAvulsoEEscala() {
        val l = listOf(p("2027-01-06", 8).copy(tipo = Plantao.AVULSO), p("2027-01-13", 8).copy(tipo = Plantao.AVULSO),
            p("2027-01-07", 8).copy(serieId = "x"), p("2027-01-14", 8).copy(serieId = "x"))
        assertTrue(Series.padroes(l, LocalDate.parse("2027-01-01")).isEmpty())
    }
}

class RegraTest {
    @Test fun formasDePagamento() {
        assertEquals(1200.0, Regra(pagamento = Regra.PLANTAO, valorPlantao = 1200.0).valorPara(720), 0.001)
        assertEquals(660.0, Regra(pagamento = Regra.HORA, valorHora = 110.0).valorPara(360), 0.001)
        assertEquals(0.0, Regra(porFicha = 50.0).valorPara(720), 0.001)
        assertTrue(Regra(pagamento = Regra.SALARIO).configurada)
        assertTrue(!Regra().configurada)
    }
}
