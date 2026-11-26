package br.med.plantoes

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val PT: Locale = Locale("pt", "BR")
const val MIN = 60_000L
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR

fun zone(): ZoneId = ZoneId.systemDefault()
fun Long.ldt(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone())
fun LocalDateTime.ms(): Long = atZone(zone()).toInstant().toEpochMilli()
fun LocalDate.ms(): Long = atStartOfDay().ms()

private val F_HM = DateTimeFormatter.ofPattern("HH:mm", PT)
private val F_DIA = DateTimeFormatter.ofPattern("EEE dd/MM", PT)
private val F_LONGO = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PT)
private val F_MES = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", PT)

fun String.cap(): String = replaceFirstChar { it.titlecase(PT) }
fun hm(t: Long): String = t.ldt().format(F_HM)
fun diaCurto(t: Long): String = t.ldt().format(F_DIA).replace(".", "").cap()
fun diaCurto(d: LocalDate): String = d.format(F_DIA).replace(".", "").cap()
fun diaLongo(d: LocalDate): String = d.format(F_LONGO).cap()
fun mesAno(m: YearMonth): String = m.format(F_MES).cap()

fun horas(min: Long): String {
    val h = min / 60
    val m = min % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

fun horasCurto(min: Long): String {
    val h = min / 60
    val m = min % 60
    return if (m == 0L) "${h}h" else "${h}h${"%02d".format(m)}"
}

fun contagem(ms: Long): String {
    val m = maxOf(0L, ms / MIN)
    val d = m / 1440
    val h = (m % 1440) / 60
    val mi = m % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${"%02d".format(mi)}min"
        else -> "$mi min"
    }
}

fun brl(v: Double): String = NumberFormat.getCurrencyInstance(PT).format(v)

/** Reais sem centavos: "R$ 1.900". */
fun brl0(v: Double): String = NumberFormat.getCurrencyInstance(PT).apply { maximumFractionDigits = 0; minimumFractionDigits = 0 }.format(v)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.spf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun Context.noturno(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

object Cores {
    val verde = 0xFF0A7A5A.toInt()
    val vermelho = 0xFFE0242B.toInt()
    val ambar = 0xFFE8A10C.toInt()
}

/**
 * Cada hospital ganha uma cor fixa. A primeira vez que os hospitais aparecem, as cores são dadas em
 * ordem alfabética; hospitais novos recebem a próxima cor livre, sem mudar as cores dos antigos.
 */
object Paleta {
    val CORES = intArrayOf(
        0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFEA580C.toInt(), 0xFF0891B2.toInt(),
        0xFFDB2777.toInt(), 0xFF65A30D.toInt(), 0xFF4F46E5.toInt(), 0xFFB45309.toInt(),
    )
    val mapa = linkedMapOf<String, Int>()

    fun carregar(ctx: Context) {
        val p = Store.prefs(ctx)
        mapa.clear()
        p.getString("cores", "")!!.split("\n").filter { it.contains("=") }.forEach {
            val i = it.lastIndexOf("=")
            mapa[it.substring(0, i)] = it.substring(i + 1).toIntOrNull() ?: 0
        }
    }

    /** Dá cor aos hospitais que ainda não têm. Retorna true se mudou algo. */
    fun atribuir(ctx: Context, locais: Collection<String>): Boolean {
        val novos = locais.map { it.trim() }.filter { it.isNotEmpty() && it !in mapa }.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        if (novos.isEmpty()) return false
        for (l in novos) {
            val usados = mapa.values.toSet()
            mapa[l] = (CORES.indices).firstOrNull { it !in usados } ?: (mapa.size % CORES.size)
        }
        Store.prefs(ctx).edit().putString("cores", mapa.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
        return true
    }

    fun cor(local: String): Int {
        val i = mapa[local.trim()] ?: ((local.trim().lowercase(PT).hashCode() and 0x7fffffff) % CORES.size)
        return CORES[i % CORES.size]
    }
}

fun corDo(local: String): Int = Paleta.cor(local)

/** Nome curto para caber nas colunas estreitas do calendário. Ex.: "Santa Maria Norte" vira "SM Norte". */
fun abrev(local: String): String {
    val curtas = setOf("santa", "santo", "são", "sao", "hospital", "hosp.", "hosp", "clínica", "clinica", "de", "da", "do", "dos", "das")
    val ws = local.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (ws.size <= 1) return local.trim().take(10)
    val ultima = ws.last()
    val antes = ws.dropLast(1).filter { it.lowercase(PT) !in setOf("de", "da", "do", "dos", "das") }
    return if (ws.size == 2 && ws[0].lowercase(PT) in curtas) "${ws[0].first().uppercaseChar()}. ${ultima.take(8)}"
    else if (ws.size == 2) "${ws[0].take(4)} ${ultima.take(6)}"
    else antes.joinToString("") { it.first().uppercaseChar().toString() } + " " + ultima.take(7)
}

/** Cores da interface (sempre clara). O tom de destaque vem do tema escolhido em Ajustes. */
class Pal(val tema: Tema) {
    constructor(ctx: Context) : this(Temas.atual(ctx))
    val bg = 0xFFF4F6F9.toInt()
    val surface = 0xFFFFFFFF.toInt()
    val surface2 = 0xFFEEF1F5.toInt()
    val ink = 0xFF0B1220.toInt()
    val ink2 = 0xFF334155.toInt()
    val mute = 0xFF64748B.toInt()
    val line = 0xFFE2E7EE.toInt()
    val acc = tema.primaria
    val accClaro = tema.clara
    val accEscuro = tema.escura
    val livre = 0xFF10B981.toInt()
    val livreFundo = 0xFFE9F8F1.toInt()
    val livreTexto = 0xFF047857.toInt()
    val choque = 0xFFDC2626.toInt()
    val choqueFundo = 0xFFFEF2F2.toInt()
    val choqueBorda = 0xFFFCA5A5.toInt()
    val descanso = 0x12000000
    val alerta = 0xFFB45309.toInt()
}
