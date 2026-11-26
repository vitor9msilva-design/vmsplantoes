package br.med.plantoes

import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Aplica o widget do mesmo jeito que a tela inicial do Android faz (RemoteViews.apply). */
class WidgetShot {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "Theme.Plantoes.Azul")

    @Test fun widget() {
        val ctx = paparazzi.context
        val agora = LocalDate.of(2026, 12, 2).atTime(14, 0).ms()
        val p = Plantao(local = "Hospital Central", inicio = LocalDate.of(2026, 12, 2).atTime(19, 0).ms(), durMin = 720)
        val depois = Plantao(local = "Santa Maria", inicio = LocalDate.of(2026, 12, 3).atTime(8, 0).ms(), durMin = 720)
        val rv = ProximoWidget.montar(ctx, p.copy(setor = "Noturno"), Regra(deslocMin = 35), agora, depois)
        // três alturas: widget baixo (4x2 apertado), médio e alto
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF3B4A5A.toInt()); setPadding(ctx.dp(14), ctx.dp(20), ctx.dp(14), ctx.dp(20)) }
        for (h in listOf(180, 200, 250)) col.addView(rv.apply(ctx, FrameLayout(ctx)),
            android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(h)).apply { bottomMargin = ctx.dp(16) })
        paparazzi.snapshot(col)
    }
}
