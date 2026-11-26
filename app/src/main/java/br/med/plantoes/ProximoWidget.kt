package br.med.plantoes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate

/** Widget da tela inicial: próximo plantão, quando começa e a hora de sair de casa. */
class ProximoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = desenhar(ctx, mgr, ids)

    companion object {
        fun atualizar(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ProximoWidget::class.java))
            if (ids.isNotEmpty()) desenhar(ctx, mgr, ids)
        }

        private fun desenhar(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val p = Store.all(ctx).filter { it.ativo && it.fim > now }.minByOrNull { it.inicio }
            Paleta.carregar(ctx)
            val v = montar(ctx, p, p?.let { Regras.de(ctx, it.local) }, now)
            val abrir = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_raiz, abrir)
            mgr.updateAppWidget(ids, v)
        }

        /** Monta o conteúdo do widget (separado para poder ser testado sem celular). */
        fun montar(ctx: Context, p: Plantao?, r: Regra?, now: Long): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_proximo)
            if (p == null) {
                v.setTextViewText(R.id.w_rotulo, "Plantões")
                v.setTextViewText(R.id.w_local, "Nenhum plantão agendado")
                v.setTextViewText(R.id.w_quando, "Toque para abrir o app")
                v.setTextViewText(R.id.w_extra, "")
                v.setInt(R.id.w_faixa, "setBackgroundColor", 0xFFCBD3DE.toInt())
            } else {
                val emCurso = p.inicio <= now
                val dia = p.inicio.ldt().toLocalDate()
                val hoje = now.ldt().toLocalDate()
                val rel = when (dia) { hoje -> "Hoje"; hoje.plusDays(1) -> "Amanhã"; else -> diaCurto(p.inicio) }
                v.setTextViewText(R.id.w_rotulo, if (emCurso) "Em plantão · termina ${hm(p.fim)}" else "Próximo plantão · em ${contagem(p.inicio - now)}")
                v.setTextViewText(R.id.w_local, p.local)
                v.setTextViewText(R.id.w_quando, "$rel, ${hm(p.inicio)} – ${hm(p.fim)}")
                v.setTextViewText(R.id.w_extra, if (!emCurso && r != null && r.deslocMin > 0) "Saia até ${hm(p.inicio - r.deslocMin * MIN)}" else "")
                v.setInt(R.id.w_faixa, "setBackgroundColor", corDo(p.local))
            }
            return v
        }
    }
}
