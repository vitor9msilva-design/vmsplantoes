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
    /** Valor fixo do plantão (R$), quando o hospital paga um valor fechado. */
    val valor: Double = 0.0,
    val obs: String = "",
    /** Produtividade lançada no fim do plantão. null = ainda não lançado. */
    val fichas: Int? = null,
    val uti: Int? = null,
    /** Valores usados no cálculo, guardados no lançamento: mudar a tabela depois não altera o passado. */
    val porFicha: Double = 0.0,
    val porUti: Double = 0.0,
    /** "" = normal; "repassado" = passei para um colega; "cobertura" = estou cobrindo um colega. */
    val situacao: String = "",
    val colega: String = "",
    /** "" ou "fixo" = da escala fixa; "avulso" = plantão extra pego à parte. */
    val tipo: String = "",
    /** Escala automática que gerou este plantão ("" = cadastrado à mão). */
    val serieId: String = "",
) {
    val avulso: Boolean get() = tipo == AVULSO

    /** Repassado não é mais meu: sai dos alarmes, choques, horários livres e ganhos. */
    val ativo: Boolean get() = situacao != REPASSADO

    val fim: Long get() = inicio + durMin * MIN
    val horas: Double get() = durMin / 60.0
    val lancado: Boolean get() = fichas != null

    /** Ganho do plantão: valor fixo + fichas × valor da ficha + UTI × valor da UTI. */
    val ganho: Double get() = valor + (fichas ?: 0) * porFicha + (uti ?: 0) * porUti

    /** Tem ganho conhecido (lançou fichas ou tem valor fixo). */
    val temGanho: Boolean get() = lancado || valor > 0

    val porHora: Double get() = if (durMin > 0) ganho / horas else 0.0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("local", local).put("setor", setor)
        .put("inicio", inicio).put("durMin", durMin).put("valor", valor).put("obs", obs)
        .apply {
            if (fichas != null) put("fichas", fichas)
            if (uti != null) put("uti", uti)
            put("porFicha", porFicha); put("porUti", porUti)
            if (situacao.isNotEmpty()) put("situacao", situacao)
            if (colega.isNotEmpty()) put("colega", colega)
            if (tipo.isNotEmpty()) put("tipo", tipo)
            if (serieId.isNotEmpty()) put("serieId", serieId)
        }

    companion object {
        fun fromJson(o: JSONObject) = Plantao(
            id = o.getString("id"),
            local = o.getString("local"),
            setor = o.optString("setor", ""),
            inicio = o.getLong("inicio"),
            durMin = o.getInt("durMin"),
            valor = o.optDouble("valor", 0.0),
            obs = o.optString("obs", ""),
            fichas = if (o.has("fichas")) o.getInt("fichas") else null,
            uti = if (o.has("uti")) o.getInt("uti") else null,
            porFicha = o.optDouble("porFicha", 0.0),
            porUti = o.optDouble("porUti", 0.0),
            situacao = o.optString("situacao", ""),
            colega = o.optString("colega", ""),
            tipo = o.optString("tipo", ""),
            serieId = o.optString("serieId", ""),
        )

        const val AVULSO = "avulso"
        const val FIXO = "fixo"
        const val REPASSADO = "repassado"
        const val COBERTURA = "cobertura"
    }
}

/** Quanto cada hospital paga. Guardado nas preferências do celular (nunca no código, que é público). */
data class Regra(
    val porFicha: Double = 0.0,
    val porUti: Double = 0.0,
    /** Minutos de casa até o hospital (para "saia até"). */
    val deslocMin: Int = 0,
    /** Lembrete fixo do hospital, ex.: "levar carimbo e jaleco". */
    val levar: String = "",
) {
    val configurada: Boolean get() = porFicha > 0 || porUti > 0
}

object Regras {
    fun todas(ctx: Context): Map<String, Regra> {
        val o = try { JSONObject(Store.prefs(ctx).getString("regras", "{}")!!) } catch (_: Exception) { JSONObject() }
        return o.keys().asSequence().associateWith { k ->
            val r = o.getJSONObject(k)
            Regra(r.optDouble("porFicha", 0.0), r.optDouble("porUti", 0.0), r.optInt("deslocMin", 0), r.optString("levar", ""))
        }
    }

    fun de(ctx: Context, local: String): Regra = todas(ctx)[local.trim()] ?: Regra()

    fun salvar(ctx: Context, local: String, r: Regra) {
        val o = try { JSONObject(Store.prefs(ctx).getString("regras", "{}")!!) } catch (_: Exception) { JSONObject() }
        o.put(local.trim(), JSONObject().put("porFicha", r.porFicha).put("porUti", r.porUti)
            .put("deslocMin", r.deslocMin).put("levar", r.levar))
        Store.prefs(ctx).edit().putString("regras", o.toString()).apply()
    }

    fun meta(ctx: Context): Double = Store.prefs(ctx).getFloat("metaHora", 100f).toDouble()
    fun salvarMeta(ctx: Context, v: Double) = Store.prefs(ctx).edit().putFloat("metaHora", v.toFloat()).apply()
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
        ProximoWidget.atualizar(ctx)
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
                    .apply {
                        if (p.fichas != null) put("fichas", p.fichas)
                        if (p.uti != null) put("uti", p.uti)
                        if (p.lancado) { put("porFicha", p.porFicha); put("porUti", p.porUti) }
                        if (p.situacao.isNotEmpty()) put("situacao", p.situacao)
                        if (p.colega.isNotEmpty()) put("colega", p.colega)
                        if (p.tipo.isNotEmpty()) put("tipo", p.tipo)
                    }
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
                fichas = if (x.has("fichas")) x.getInt("fichas") else null,
                uti = if (x.has("uti")) x.getInt("uti") else null,
                porFicha = x.optDouble("porFicha", 0.0), porUti = x.optDouble("porUti", 0.0),
                situacao = x.optString("situacao", ""), colega = x.optString("colega", ""),
                tipo = x.optString("tipo", ""),
            )
        }
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
    fun descansoMin(ctx: Context) = prefs(ctx).getInt("descansoH", 0) * 60

    /** Ids dos plantões que se sobrepõem a outro. */
    fun conflitos(list: List<Plantao>): Set<String> {
        val s = list.filter { it.ativo }.sortedBy { it.inicio }
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
        val iv = list.filter { it.ativo }.map { longArrayOf(it.inicio, it.fim + restMin * MIN) }.sortedBy { it[0] }
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
        Janela(a, b, list.lastOrNull { it.ativo && it.fim + r * MIN == a }, list.firstOrNull { it.ativo && it.inicio == b })

    fun livreNoDia(list: List<Plantao>, d: java.time.LocalDate, r: Int): Long =
        janelas(list, d.ms(), d.plusDays(1).ms(), r).sumOf { it.fim - it.ini } / MIN
}

/** Pagamento recebido de um hospital num mês (para conferir com o esperado). */
data class Recebido(val valor: Double, val quando: Long)

object Recebimentos {
    fun chave(ano: Int, mes: Int, local: String) = "%04d-%02d|%s".format(ano, mes, local.trim())

    fun todos(ctx: Context): Map<String, Recebido> {
        val o = try { JSONObject(Store.prefs(ctx).getString("recebidos", "{}")!!) } catch (_: Exception) { JSONObject() }
        return o.keys().asSequence().associateWith { k -> val r = o.getJSONObject(k); Recebido(r.optDouble("valor"), r.optLong("quando")) }
    }

    fun salvar(ctx: Context, chave: String, r: Recebido?) {
        val o = try { JSONObject(Store.prefs(ctx).getString("recebidos", "{}")!!) } catch (_: Exception) { JSONObject() }
        if (r == null) o.remove(chave) else o.put(chave, JSONObject().put("valor", r.valor).put("quando", r.quando))
        Store.prefs(ctx).edit().putString("recebidos", o.toString()).apply()
    }
}

/** Jornada = plantões emendados (intervalo menor que 1 h entre eles) contados como um bloco só. */
data class Jornada(val ini: Long, val fim: Long, val plantoes: List<Plantao>) {
    val horas: Double get() = (fim - ini) / HOUR.toDouble()
}

object Jornadas {
    fun calcular(list: List<Plantao>): List<Jornada> {
        val out = mutableListOf<Jornada>()
        for (p in list.filter { it.ativo }.sortedBy { it.inicio }) {
            val ult = out.lastOrNull()
            if (ult != null && p.inicio <= ult.fim + HOUR) out[out.size - 1] = Jornada(ult.ini, maxOf(ult.fim, p.fim), ult.plantoes + p)
            else out.add(Jornada(p.inicio, p.fim, listOf(p)))
        }
        return out
    }

    /** Jornadas acima do limite (padrão 24 h seguidas). */
    fun longas(list: List<Plantao>, limiteH: Double = 24.0) = calcular(list).filter { it.horas > limiteH }
}

/** Renda fixa mensal (ex.: contrato CLT/estatutário). Valor líquido por mês. */
data class RendaFixa(
    val id: String = UUID.randomUUID().toString(),
    val nome: String,
    val valor: Double,
    /** Primeiro mês, "AAAA-MM". */
    val desde: String,
    /** Último mês, "AAAA-MM"; vazio = continua. */
    val ate: String = "",
    /** Local cujos plantões já estão pagos por este salário (não pedem fichas). Vazio = nenhum. */
    val local: String = "",
) {
    fun ativaEm(ym: java.time.YearMonth): Boolean {
        val k = "%04d-%02d".format(ym.year, ym.monthValue)
        return desde <= k && (ate.isEmpty() || k <= ate)
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("nome", nome).put("valor", valor)
        .put("desde", desde).put("ate", ate).put("local", local)

    companion object {
        fun fromJson(o: JSONObject) = RendaFixa(
            o.optString("id", UUID.randomUUID().toString()), o.getString("nome"), o.getDouble("valor"),
            o.getString("desde"), o.optString("ate", ""), o.optString("local", ""),
        )
    }
}

object Fixos {
    fun todos(ctx: Context): List<RendaFixa> {
        val arr = try { JSONArray(Store.prefs(ctx).getString("fixos", "[]")!!) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i -> try { RendaFixa.fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null } }
    }

    private fun gravar(ctx: Context, l: List<RendaFixa>) {
        val arr = JSONArray(); l.forEach { arr.put(it.toJson()) }
        Store.prefs(ctx).edit().putString("fixos", arr.toString()).apply()
    }

    fun salvar(ctx: Context, r: RendaFixa) {
        val l = todos(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l.add(r)
        gravar(ctx, l)
        Alarms.reagendar(ctx)
    }

    fun remover(ctx: Context, id: String) { gravar(ctx, todos(ctx).filter { it.id != id }); Alarms.reagendar(ctx) }

    /** Locais cujos plantões são pagos por salário fixo. */
    fun locais(ctx: Context): Set<String> = todos(ctx).map { it.local.trim() }.filter { it.isNotEmpty() }.toSet()

    fun metaMes(ctx: Context): Double = Store.prefs(ctx).getFloat("metaMes", 0f).toDouble()
    fun salvarMetaMes(ctx: Context, v: Double) = Store.prefs(ctx).edit().putFloat("metaMes", v.toFloat()).apply()
}

/** Cópia de segurança completa: plantões e todas as configurações. */
object Backup {
    private val CHAVES = listOf("series", "regras", "recebidos", "fixos", "cores", "tema", "metaHora", "metaMes", "descansoH", "minLivreH", "volMax")

    fun gerar(ctx: Context): String {
        val p = Store.prefs(ctx)
        val prefs = JSONObject()
        for (k in CHAVES) when (val v = p.all[k]) { null -> {}; else -> prefs.put(k, JSONObject().put("t", v.javaClass.simpleName).put("v", v.toString())) }
        val arr = JSONArray(); Store.all(ctx).forEach { arr.put(it.toJson()) }
        return JSONObject().put("formato", "plantoes-backup-v1").put("criado", System.currentTimeMillis())
            .put("plantoes", arr).put("prefs", prefs).toString(1)
    }

    /** Retorna quantos plantões vieram. Lança exceção se o arquivo não for um backup. */
    fun restaurar(ctx: Context, txt: String): Int {
        val o = JSONObject(txt)
        require(o.optString("formato") == "plantoes-backup-v1") { "formato" }
        val arr = o.getJSONArray("plantoes")
        val ps = (0 until arr.length()).map { Plantao.fromJson(arr.getJSONObject(it)) }
        val pr = o.optJSONObject("prefs") ?: JSONObject()
        val e = Store.prefs(ctx).edit()
        for (k in pr.keys()) {
            val x = pr.getJSONObject(k); val v = x.getString("v")
            when (x.getString("t")) {
                "Integer" -> e.putInt(k, v.toInt()); "Long" -> e.putLong(k, v.toLong()); "Float" -> e.putFloat(k, v.toFloat())
                "Boolean" -> e.putBoolean(k, v.toBoolean()); else -> e.putString(k, v)
            }
        }
        e.apply()
        Paleta.carregar(ctx)
        Store.substituir(ctx, ps)
        return ps.size
    }
}

/**
 * Escala automática: um plantão fixo que se repete (toda semana, a cada 15 dias...) sem data para acabar.
 * O app gera as próximas ocorrências sozinho, sempre [HORIZONTE_DIAS] à frente.
 */
data class Serie(
    val id: String = UUID.randomUUID().toString(),
    val local: String,
    val setor: String = "",
    val hora: String,            // "HH:MM"
    val durMin: Int,
    val inicio: String,          // primeira data, "AAAA-MM-DD"
    val intervalo: Int,          // dias entre ocorrências: 7, 14, 21, 28
    val ate: String = "",        // última data possível, "" = sem fim
    val valor: Double = 0.0,
    val obs: String = "",
    /** Datas removidas ou editadas à parte, que não devem ser recriadas. */
    val excecoes: Set<String> = emptySet(),
) {
    fun ocorrencias(de: java.time.LocalDate, ate2: java.time.LocalDate): List<java.time.LocalDate> {
        val ini = java.time.LocalDate.parse(inicio)
        val lim = if (ate.isEmpty()) ate2 else minOf(ate2, java.time.LocalDate.parse(ate))
        val out = mutableListOf<java.time.LocalDate>()
        var k = if (de <= ini) 0L else (java.time.temporal.ChronoUnit.DAYS.between(ini, de) + intervalo - 1) / intervalo
        while (true) {
            val d = ini.plusDays(k * intervalo)
            if (d > lim) break
            if (d >= de && d.toString() !in excecoes) out.add(d)
            k++
        }
        return out
    }

    fun descricao(): String {
        val d = java.time.LocalDate.parse(inicio)
        val dia = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, PT).replaceFirstChar { it.titlecase(PT) }
        val freq = when (intervalo) { 7 -> "toda semana"; 14 -> "a cada 15 dias"; 21 -> "a cada 3 semanas"; 28 -> "a cada 4 semanas"; else -> "a cada $intervalo dias" }
        return "$dia, $hora · ${horas(durMin.toLong())} · $freq"
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("local", local).put("setor", setor).put("hora", hora)
        .put("durMin", durMin).put("inicio", inicio).put("intervalo", intervalo).put("ate", ate).put("valor", valor)
        .put("obs", obs).put("excecoes", JSONArray(excecoes.toList()))

    companion object {
        const val HORIZONTE_DIAS = 120L
        fun fromJson(o: JSONObject) = Serie(
            o.getString("id"), o.getString("local"), o.optString("setor", ""), o.getString("hora"), o.getInt("durMin"),
            o.getString("inicio"), o.getInt("intervalo"), o.optString("ate", ""), o.optDouble("valor", 0.0), o.optString("obs", ""),
            o.optJSONArray("excecoes")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
        )
    }
}

object Series {
    fun todas(ctx: Context): List<Serie> {
        val arr = try { JSONArray(Store.prefs(ctx).getString("series", "[]")!!) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { try { Serie.fromJson(arr.getJSONObject(it)) } catch (_: Exception) { null } }
    }

    private fun gravar(ctx: Context, l: List<Serie>) {
        val arr = JSONArray(); l.forEach { arr.put(it.toJson()) }
        Store.prefs(ctx).edit().putString("series", arr.toString()).apply()
    }

    fun de(ctx: Context, id: String) = todas(ctx).firstOrNull { it.id == id }

    fun salvar(ctx: Context, s: Serie) {
        val l = todas(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l.add(s)
        gravar(ctx, l)
    }

    fun remover(ctx: Context, id: String) = gravar(ctx, todas(ctx).filter { it.id != id })

    /** Marca uma data como exceção (o plantão daquele dia foi apagado ou mudou à parte). */
    fun excecao(ctx: Context, id: String, data: String) {
        val s = de(ctx, id) ?: return
        salvar(ctx, s.copy(excecoes = s.excecoes + data))
    }

    fun idDe(serie: String, data: java.time.LocalDate) = "s:$serie:$data"

    fun plantao(s: Serie, d: java.time.LocalDate): Plantao {
        val (h, m) = s.hora.split(":").map { it.trim().toInt() }
        return Plantao(id = idDe(s.id, d), local = s.local, setor = s.setor, inicio = d.atTime(h, m).ms(), durMin = s.durMin,
            valor = s.valor, obs = s.obs, tipo = Plantao.FIXO, serieId = s.id)
    }

    /** Data da ocorrência de um plantão da escala (tirada do id; se não der, a data de início). */
    fun dataDe(p: Plantao): java.time.LocalDate =
        if (p.id.startsWith("s:")) try { java.time.LocalDate.parse(p.id.substringAfterLast(":")) } catch (_: Exception) { p.inicio.ldt().toLocalDate() }
        else p.inicio.ldt().toLocalDate()

    /** Liga à escala os plantões já cadastrados no mesmo local e horário de uma ocorrência (não duplica nada). */
    fun adotar(ctx: Context, s: Serie) {
        val fim = java.time.LocalDate.now().plusDays(Serie.HORIZONTE_DIAS)
        val porChave = s.ocorrencias(java.time.LocalDate.parse(s.inicio), fim)
            .associateBy { d -> plantao(s, d).let { it.local.trim().lowercase(PT) + "|" + it.inicio } }
        val l = Store.all(ctx).toMutableList()
        var mudou = false
        for (i in l.indices) {
            val p = l[i]
            if (p.serieId.isNotEmpty()) continue
            val d = porChave[p.local.trim().lowercase(PT) + "|" + p.inicio] ?: continue
            l[i] = p.copy(id = idDe(s.id, d), serieId = s.id, tipo = Plantao.FIXO); mudou = true
        }
        if (mudou) Store.substituir(ctx, l)
    }

    /** Salva a escala, aproveita os plantões que já existem e gera os que faltam. Retorna quantos foram criados. */
    fun criar(ctx: Context, s: Serie): Int { salvar(ctx, s); adotar(ctx, s); return gerar(ctx) }

    /** Encerra a escala a partir de uma data: apaga os plantões dela daí em diante que ainda não têm fichas lançadas. */
    fun encerrar(ctx: Context, id: String, aPartirDe: java.time.LocalDate) {
        val s = de(ctx, id) ?: return
        if (aPartirDe <= java.time.LocalDate.parse(s.inicio)) remover(ctx, id)
        else salvar(ctx, s.copy(ate = aPartirDe.minusDays(1).toString()))
        val corte = aPartirDe.ms()
        val rem = Store.all(ctx).filter { it.serieId == id && it.inicio >= corte && !it.lancado }.map { it.id }.toSet()
        if (rem.isNotEmpty()) Store.remover(ctx, rem)
    }

    /**
     * Cria os plantões que faltam de todas as escalas, de hoje até o horizonte.
     * Nunca duplica: pula datas que já têm plantão com o mesmo id, ou no mesmo local e horário.
     * Retorna quantos plantões novos foram criados.
     */
    fun gerar(ctx: Context): Int {
        val hoje = java.time.LocalDate.now()
        val fim = hoje.plusDays(Serie.HORIZONTE_DIAS)
        val lista = Store.all(ctx)
        val ids = lista.map { it.id }.toHashSet()
        val chaves = lista.map { it.local.trim().lowercase(PT) + "|" + it.inicio }.toHashSet()
        val novos = mutableListOf<Plantao>()
        for (s in todas(ctx)) for (d in s.ocorrencias(hoje, fim)) {
            val p = plantao(s, d)
            if (p.id in ids || (p.local.trim().lowercase(PT) + "|" + p.inicio) in chaves) continue
            novos.add(p); ids.add(p.id)
        }
        if (novos.isNotEmpty()) Store.adicionar(ctx, novos)
        return novos.size
    }
}
