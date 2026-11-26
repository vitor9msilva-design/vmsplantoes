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
        val rv = ProximoWidget.montar(ctx, p, Regra(deslocMin = 40), agora)
        val pai = FrameLayout(ctx)
        val v = rv.apply(ctx, pai)
        paparazzi.snapshot(FrameLayout(ctx).apply {
            setBackgroundColor(0xFF3B4A5A.toInt()); setPadding(40, 40, 40, 40)
            addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 300))
        })
    }
}
