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
        0xFF1E63D6.toInt(), 0xFF8E3FD1.toInt(), 0xFFE07A12.toInt(), 0xFF00838F.toInt(),
        0xFFC2185B.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt(), 0xFF827717.toInt(),
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

class Pal(ctx: Context) {
    val n = ctx.noturno()
    val bg = if (n) 0xFF0B1110.toInt() else 0xFFF3F5F4.toInt()
    val panel = if (n) 0xFF121B19.toInt() else 0xFFFFFFFF.toInt()
    val ink = if (n) 0xFFE8EFEC.toInt() else 0xFF0F1A17.toInt()
    val mute = if (n) 0xFF93A5A0.toInt() else 0xFF5B6B66.toInt()
    val line = if (n) 0xFF24302D.toInt() else 0xFFDDE3E1.toInt()
    val verdeTxt = if (n) 0xFF2FD39A.toInt() else 0xFF0A7A5A.toInt()
    val livre = if (n) 0x332FD39A else 0x2A0A7A5A
    val descanso = if (n) 0x33FFFFFF else 0x1F000000
}
