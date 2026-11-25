package br.med.plantoes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Plantao(
    val id: String = UUID.randomUUID().toString(),
    val local: String,
    val setor: String = "",
    val inicio: Long,
    val durMin: Int,
    val valor: Double = 0.0,
    val obs: String = "",
) {
    val fim: Long get() = inicio + durMin * MIN

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("local", local).put("setor", setor)
        .put("inicio", inicio).put("durMin", durMin).put("valor", valor).put("obs", obs)

    companion object {
        fun fromJson(o: JSONObject) = Plantao(
            id = o.getString("id"),
            local = o.getString("local"),
            setor = o.optString("setor", ""),
            inicio = o.getLong("inicio"),
            durMin = o.getInt("durMin"),
            valor = o.optDouble("valor", 0.0),
            obs = o.optString("obs", ""),
        )
    }
}

/** Guarda os plantões num arquivo JSON dentro do app e reagenda os alarmes a cada mudança. */
object Store {
    private var cache: MutableList<Plantao>? = null

    fun all(ctx: Context): MutableList<Plantao> {
        cache?.let { return it }
        val list = mutableListOf<Plantao>()
        val f = File(ctx.filesDir, "plantoes.json")
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) list.add(Plantao.fromJson(arr.getJSONObject(i)))
            } catch (_: Exception) {
            }
        }
        list.sortBy { it.inicio }
        cache = list
        Paleta.carregar(ctx)
        Paleta.atribuir(ctx, list.map { it.local })
        return list
    }

    private fun salvar(ctx: Context) {
        val list = all(ctx)
        list.sortBy { it.inicio }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "plantoes.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(ctx.filesDir, "plantoes.json"))
        Paleta.atribuir(ctx, list.map { it.local })
        Alarms.reagendar(ctx)
    }

    fun adicionar(ctx: Context, novos: List<Plantao>) {
        all(ctx).addAll(novos); salvar(ctx)
    }

    fun atualizar(ctx: Context, p: Plantao) {
        val l = all(ctx)
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) l[i] = p else l.add(p)
        salvar(ctx)
    }

    fun remover(ctx: Context, ids: Set<String>) {
        all(ctx).removeAll { it.id in ids }; salvar(ctx)
    }

    fun substituir(ctx: Context, novos: List<Plantao>) {
        val l = all(ctx); l.clear(); l.addAll(novos); salvar(ctx)
    }

    // ---------- troca de arquivo com a página web (mesmo formato dos dois lados) ----------
    fun exportar(ctx: Context): String {
        val arr = JSONArray()
        for (p in all(ctx)) {
            val l = p.inicio.ldt()
            arr.put(
                JSONObject().put("local", p.local).put("setor", p.setor)
                    .put("data", l.toLocalDate().toString()).put("hora", "%02d:%02d".format(l.hour, l.minute))
                    .put("dur", p.durMin / 60.0).put("valor", p.valor).put("obs", p.obs)
            )
        }
        return JSONObject().put("formato", "plantoes-v1").put("origem", "app")
            .put("exportado", System.currentTimeMillis()).put("plantoes", arr).toString(1)
    }

    /** Lê o arquivo gerado pela página ou pelo app. Lança exceção se o formato não for reconhecido. */
    fun lerArquivo(txt: String): List<Plantao> {
        val o = JSONObject(txt)
        require(o.optString("formato") == "plantoes-v1") { "formato" }
        val arr = o.getJSONArray("plantoes")
        return (0 until arr.length()).map { i ->
            val x = arr.getJSONObject(i)
            val d = java.time.LocalDate.parse(x.getString("data"))
            val (hh, mm) = x.getString("hora").split(":").map { it.trim().toInt() }
            Plantao(
                local = x.getString("local").trim(), setor = x.optString("setor", "").trim(),
                inicio = d.atTime(hh, mm).ms(), durMin = (x.optDouble("dur", 12.0) * 60).toInt(),
                valor = x.optDouble("valor", 0.0), obs = x.optString("obs", ""),
            )
        }
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
    fun descansoMin(ctx: Context) = prefs(ctx).getInt("descansoH", 0) * 60

    /** Ids dos plantões que se sobrepõem a outro. */
    fun conflitos(list: List<Plantao>): Set<String> {
        val s = list.sortedBy { it.inicio }
        val out = mutableSetOf<String>()
        for (i in s.indices) {
            var j = i + 1
            while (j < s.size && s[j].inicio < s[i].fim) {
                out.add(s[i].id); out.add(s[j].id); j++
            }
        }
        return out
    }
}

data class Janela(val ini: Long, val fim: Long, val antes: Plantao?, val depois: Plantao?) {
    val min: Long get() = (fim - ini) / MIN
}

/** Calcula os intervalos livres entre plantões, opcionalmente reservando descanso após cada um. */
object Livre {
    private fun ocupado(list: List<Plantao>, restMin: Int): List<LongArray> {
        val iv = list.map { longArrayOf(it.inicio, it.fim + restMin * MIN) }.sortedBy { it[0] }
        val out = mutableListOf<LongArray>()
        for (x in iv) {
            val last = out.lastOrNull()
            if (last != null && x[0] <= last[1]) last[1] = maxOf(last[1], x[1]) else out.add(longArrayOf(x[0], x[1]))
        }
        return out
    }

    fun janelas(list: List<Plantao>, de: Long, ate: Long, restMin: Int): List<Janela> {
        val res = mutableListOf<Janela>()
        var cur = de
        for (b in ocupado(list, restMin)) {
            if (b[1] <= cur) continue
            if (b[0] >= ate) break
            if (b[0] > cur) res.add(mk(list, cur, b[0], restMin))
            cur = maxOf(cur, b[1])
            if (cur >= ate) break
        }
        if (cur < ate) res.add(mk(list, cur, ate, restMin))
        return res
    }

    private fun mk(list: List<Plantao>, a: Long, b: Long, r: Int) =
        Janela(a, b, list.lastOrNull { it.fim + r * MIN == a }, list.firstOrNull { it.inicio == b })

    fun livreNoDia(list: List<Plantao>, d: java.time.LocalDate, r: Int): Long =
        janelas(list, d.ms(), d.plusDays(1).ms(), r).sumOf { it.fim - it.ini } / MIN
}
