package br.med.plantoes

import android.content.Context

data class Tema(val id: String, val nome: String, val estilo: Int, val primaria: Int, val clara: Int, val escura: Int)

/** Temas claros. Trocar o tema em Ajustes recria a tela com o novo estilo. */
object Temas {
    val lista = listOf(
        Tema("azul", "Azul", R.style.Theme_Plantoes_Azul, 0xFF1D4ED8.toInt(), 0xFFDBE7FF.toInt(), 0xFF0B2A7A.toInt()),
        Tema("verde", "Verde", R.style.Theme_Plantoes_Verde, 0xFF047857.toInt(), 0xFFD3F5E6.toInt(), 0xFF064E3B.toInt()),
        Tema("grafite", "Grafite", R.style.Theme_Plantoes_Grafite, 0xFF1F2937.toInt(), 0xFFE6E9EE.toInt(), 0xFF111827.toInt()),
    )

    fun porId(id: String?): Tema = lista.firstOrNull { it.id == id } ?: lista[0]
    fun atual(ctx: Context): Tema = porId(Store.prefs(ctx).getString("tema", "azul"))
}
