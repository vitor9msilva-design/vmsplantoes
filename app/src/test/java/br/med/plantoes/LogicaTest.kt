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
