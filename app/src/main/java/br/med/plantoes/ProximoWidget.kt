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
            val prox = Store.all(ctx).filter { it.ativo && it.fim > now }.sortedBy { it.inicio }
            val p = prox.firstOrNull()
            Paleta.carregar(ctx)
            val v = montar(ctx, p, p?.let { Regras.de(ctx, it.local) }, now, prox.getOrNull(1))
            val abrir = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_raiz, abrir)
            mgr.updateAppWidget(ids, v)
        }

        /** Monta o conteúdo do widget (separado para poder ser testado sem celular). */
        fun montar(ctx: Context, p: Plantao?, r: Regra?, now: Long, depois: Plantao? = null): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_proximo)
            if (p == null) {
                v.setTextViewText(R.id.w_rotulo, "Plantões")
                v.setTextViewText(R.id.w_dia, "Livre")
                v.setTextViewText(R.id.w_local, "Nenhum plantão agendado")
                v.setTextViewText(R.id.w_quando, "Toque para abrir o app")
                v.setTextViewText(R.id.w_cont_rot, ""); v.setTextViewText(R.id.w_cont, "")
                v.setViewVisibility(R.id.w_saia_bloco, android.view.View.GONE)
                v.setTextViewText(R.id.w_depois, "")
                v.setInt(R.id.w_faixa, "setBackgroundColor", 0xFFCBD3DE.toInt())
                return v
            }
            val emCurso = p.inicio <= now
            val dia = p.inicio.ldt().toLocalDate()
            val hoje = now.ldt().toLocalDate()
            val rel = when (dia) { hoje -> "Hoje"; hoje.plusDays(1) -> "Amanhã"; else -> diaCurto(p.inicio) }
            v.setTextViewText(R.id.w_rotulo, if (emCurso) "Em plantão agora" else "Próximo plantão")
            v.setTextViewText(R.id.w_dia, if (emCurso) "Agora" else rel)
            v.setTextViewText(R.id.w_local, p.local)
            v.setTextViewText(R.id.w_quando, "${hm(p.inicio)} – ${hm(p.fim)} · ${horas(p.durMin.toLong())}" + if (p.setor.isNotBlank()) " · ${p.setor}" else "")
            v.setTextViewText(R.id.w_cont_rot, if (emCurso) "Termina em" else "Começa em")
            v.setTextViewText(R.id.w_cont, contagem(if (emCurso) p.fim - now else p.inicio - now))
            val sair = if (!emCurso && r != null && r.deslocMin > 0) p.inicio - r.deslocMin * MIN else null
            if (sair != null) {
                v.setViewVisibility(R.id.w_saia_bloco, android.view.View.VISIBLE)
                v.setTextViewText(R.id.w_saia, hm(sair))
            } else v.setViewVisibility(R.id.w_saia_bloco, android.view.View.GONE)
            v.setTextViewText(R.id.w_depois, depois?.let { "Depois: ${diaCurto(it.inicio)}, ${hm(it.inicio)} · ${it.local}" } ?: "")
            v.setInt(R.id.w_faixa, "setBackgroundColor", corDo(p.local))
            return v
        }
    }
}
