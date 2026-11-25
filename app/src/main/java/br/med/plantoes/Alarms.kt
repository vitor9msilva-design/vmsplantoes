package br.med.plantoes

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Dois alarmes por plantão: 24 h antes e 2 h antes.
 * Usa setAlarmClock, o mesmo mecanismo do app de despertador: toca no horário exato,
 * mesmo com o celular bloqueado, em economia de energia ou no modo soneca do Android.
 */
object Alarms {
    const val CANAL = "alarme_plantao_v1"
    const val ACAO = "br.med.plantoes.ALARME"
    const val CIENTE = "br.med.plantoes.CIENTE"
    private const val MAX_ALARMES = 400

    fun somUri(): Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: Settings.System.DEFAULT_NOTIFICATION_URI

    fun canal(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) != null) return
        val ch = NotificationChannel(CANAL, "Alarmes de plantão", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avisos 1 dia antes e 2 horas antes de cada plantão"
            setSound(
                somUri(),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            enableLights(true)
            lightColor = Color.RED
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun base(id: String) = (id.hashCode() and 0x0FFFFFFF) * 4

    private fun intent(ctx: Context, id: String, kind: Int) =
        Intent(ctx, AlarmReceiver::class.java).setAction(ACAO).putExtra("id", id).putExtra("kind", kind)

    private fun agendar(ctx: Context, t: Long, code: Int, id: String, kind: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, code, intent(ctx, id, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val abrir = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, abrir), pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    fun reagendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = Store.prefs(ctx)
        for (c in prefs.getStringSet("codes", emptySet()) ?: emptySet()) {
            val pi = PendingIntent.getBroadcast(
                ctx, c.toInt(), Intent(ctx, AlarmReceiver::class.java).setAction(ACAO),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
        val now = System.currentTimeMillis()
        val codes = mutableSetOf<String>()
        loop@ for (p in Store.all(ctx).filter { it.inicio > now }.sortedBy { it.inicio }) {
            for ((kind, antes) in listOf(1 to DAY, 2 to 2 * HOUR)) {
                val t = p.inicio - antes
                if (t <= now) continue
                val code = base(p.id) + kind
                agendar(ctx, t, code, p.id, kind)
                codes.add(code.toString())
                if (codes.size >= MAX_ALARMES) break@loop
            }
        }
        prefs.edit().putStringSet("codes", codes).apply()
    }

    fun soneca(ctx: Context, id: String, kind: Int, min: Int = 10) =
        agendar(ctx, System.currentTimeMillis() + min * MIN, base(id) + 3, id, kind)

    fun teste(ctx: Context) {
        val prox = Store.all(ctx).firstOrNull { it.inicio > System.currentTimeMillis() }
        val id = prox?.id ?: "teste"
        agendar(ctx, System.currentTimeMillis() + 5_000, base(id) + 3, id, 2)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (i.action == Alarms.CIENTE) { nm.cancel(i.getIntExtra("nid", 0)); return }
        val id = i.getStringExtra("id") ?: return
        val kind = i.getIntExtra("kind", 2)
        val p = Store.all(ctx).find { it.id == id }
        if (p == null && id != "teste") return
        Alarms.canal(ctx)
        val nid = id.hashCode() xor kind

        val titulo = (if (kind == 1) "Plantão amanhã" else "Plantão em 2 horas") + (p?.let { " · ${it.local}" } ?: "")
        val texto = p?.let {
            "${diaCurto(it.inicio)}, ${hm(it.inicio)} às ${hm(it.fim)} · ${horas(it.durMin.toLong())}" +
                (if (it.setor.isNotBlank()) " · ${it.setor}" else "")
        } ?: "Teste de alarme"

        val tela = Intent(ctx, AlarmActivity::class.java)
            .putExtra("id", id).putExtra("kind", kind).putExtra("nid", nid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val pTela = PendingIntent.getActivity(
            ctx, nid, tela, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pCiente = PendingIntent.getBroadcast(
            ctx, nid, Intent(ctx, AlarmReceiver::class.java).setAction(Alarms.CIENTE).putExtra("nid", nid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, Alarms.CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(Cores.vermelho)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto + (p?.obs?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(Alarms.somUri(), AudioManager.STREAM_ALARM)
            .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
            .setLights(Color.RED, 500, 500)
            .setFullScreenIntent(pTela, true)
            .setContentIntent(pTela)
            .addAction(0, "Ciente", pCiente)
            .setAutoCancel(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        try { nm.notify(nid, n) } catch (_: SecurityException) { }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Alarms.canal(ctx)
        Alarms.reagendar(ctx)
    }
}
