package br.med.plantoes

import android.app.NotificationManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Tela cheia que acende sobre a tela bloqueada: fundo piscando, lanterna piscando, som de alarme e vibração. */
class AlarmActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vib: Vibrator? = null
    private var torchId: String? = null
    private var cam: CameraManager? = null
    private var piscando = true
    private var fase = false
    private var volAnterior = -1
    private lateinit var root: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra("id") ?: "teste"
        val kind = intent.getIntExtra("kind", 2)
        getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("nid", 0))
        val p = Store.all(this).find { it.id == id }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(Cores.vermelho)
        }
        fun t(s: String, sp: Float, bold: Boolean = false, top: Int = 0) {
            root.addView(TextView(this).apply {
                text = s; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        }
        t(if (kind == 1) "PLANTÃO AMANHÃ" else "PLANTÃO EM 2 HORAS", 24f, true)
        if (p != null) {
            t(p.local, 40f, true, 14)
            if (p.setor.isNotBlank()) t(p.setor, 22f)
            t(diaLongo(p.inicio.ldt().toLocalDate()), 20f, top = 14)
            t("${hm(p.inicio)} às ${hm(p.fim)} · ${horas(p.durMin.toLong())}", 26f, true)
            t("Começa em ${contagem(p.inicio - System.currentTimeMillis())}", 18f, top = 8)
            val r = Regras.de(this, p.local)
            if (kind == 2 && r.deslocMin > 0) t("Saia até ${hm(p.inicio - r.deslocMin * MIN)}", 22f, true, 10)
            if (r.levar.isNotBlank()) t("Não esquecer: ${r.levar}", 18f, top = 10)
            if (p.obs.isNotBlank()) t(p.obs, 16f, top = 10)
        } else {
            t("Teste de alarme", 36f, true, 14)
            t("É assim que os avisos vão aparecer.", 18f, top = 8)
        }
        root.addView(MaterialButton(this).apply {
            text = "CIENTE"; textSize = 24f; setTextColor(Color.BLACK)
            backgroundTintList = ColorStateList.valueOf(Color.WHITE); cornerRadius = dp(18)
            setOnClickListener { pararSom(); finish() }
        }, LinearLayout.LayoutParams(-1, dp(80)).apply { topMargin = dp(36) })
        root.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Soneca 10 min"; textSize = 18f; setTextColor(Color.WHITE)
            strokeColor = ColorStateList.valueOf(Color.WHITE); cornerRadius = dp(18)
            setOnClickListener { Alarms.soneca(this@AlarmActivity, id, kind); pararSom(); finish() }
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(10) })
        setContentView(root)

        iniciar()
        h.postDelayed({ pararSom() }, 5 * 60_000L)
    }

    private fun iniciar() {
        val am = getSystemService(AudioManager::class.java)
        if (Store.prefs(this).getBoolean("volMax", true)) {
            try {
                volAnterior = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (_: Exception) { volAnterior = -1 }
        }
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(this@AlarmActivity, Alarms.somUri())
                isLooping = true
                prepare(); start()
            }
        } catch (_: Exception) { null }
        vib = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else getSystemService(Vibrator::class.java)
        try { vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 500), 0)) } catch (_: Exception) { }
        cam = getSystemService(CameraManager::class.java)
        torchId = try {
            cam?.cameraIdList?.firstOrNull { cam?.getCameraCharacteristics(it)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) { null }
        h.post(pisca)
    }

    private val pisca = object : Runnable {
        override fun run() {
            if (!piscando) return
            fase = !fase
            root.setBackgroundColor(if (fase) Cores.vermelho else 0xFF2A0406.toInt())
            torchId?.let { try { cam?.setTorchMode(it, fase) } catch (_: Exception) { } }
            h.postDelayed(this, 500)
        }
    }

    private fun pararSom() {
        piscando = false
        try { player?.stop(); player?.release() } catch (_: Exception) { }
        player = null
        vib?.cancel()
        torchId?.let { try { cam?.setTorchMode(it, false) } catch (_: Exception) { } }
        if (volAnterior >= 0) {
            try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volAnterior, 0) } catch (_: Exception) { }
            volAnterior = -1
        }
        if (::root.isInitialized) root.setBackgroundColor(0xFF7A0E12.toInt())
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        pararSom()
        super.onDestroy()
    }
}
