package br.med.plantoes

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Atualização automática: ao abrir (no máximo uma vez a cada 6 h) o app lê release/versao.json no GitHub.
 * Se houver versão nova, mostra as novidades e, com um toque, baixa o APK e abre o instalador do Android.
 */
object Atualizador {
    private const val BASE = "https://raw.githubusercontent.com/vitor9msilva-design/vmsplantoes/main/release/"

    fun versaoAtual(a: Activity): Long =
        if (Build.VERSION.SDK_INT >= 28) a.packageManager.getPackageInfo(a.packageName, 0).longVersionCode
        else @Suppress("DEPRECATION") a.packageManager.getPackageInfo(a.packageName, 0).versionCode.toLong()

    fun nomeVersao(a: Activity): String = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: ""

    private fun baixar(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }

    fun verificar(a: Activity, manual: Boolean) {
        val pr = Store.prefs(a)
        if (!manual && System.currentTimeMillis() - pr.getLong("ultimaVerificacao", 0) < 6 * HOUR) return
        pr.edit().putLong("ultimaVerificacao", System.currentTimeMillis()).apply()
        thread {
            try {
                val o = JSONObject(baixar(BASE + "versao.json?t=" + System.currentTimeMillis()).inputStream.bufferedReader().readText())
                val code = o.getLong("versionCode")
                a.runOnUiThread {
                    if (a.isFinishing) return@runOnUiThread
                    if (code > versaoAtual(a)) oferecer(a, o)
                    else if (manual) Toast.makeText(a, "Você já está na versão mais nova (${nomeVersao(a)}).", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                if (manual) a.runOnUiThread { Toast.makeText(a, "Sem conexão para procurar atualização. Tente mais tarde.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun oferecer(a: Activity, o: JSONObject) {
        MaterialAlertDialogBuilder(a)
            .setTitle("Nova versão ${o.optString("versionName")} disponível")
            .setMessage(o.optString("novidades", "Melhorias no app.") + "\n\nSeus plantões e alarmes continuam salvos.")
            .setPositiveButton("Atualizar agora") { _, _ -> instalar(a, o.getLong("versionCode")) }
            .setNegativeButton("Depois", null).show()
    }

    private fun instalar(a: Activity, code: Long) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(a).setTitle("Permitir atualizações")
                .setMessage("Na próxima tela, ative \"Permitir desta fonte\" para o app Plantões. Depois volte e toque em Atualizar de novo.")
                .setPositiveButton("Abrir ajuste") { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))) } catch (_: Exception) { }
                    Store.prefs(a).edit().putLong("ultimaVerificacao", 0).apply()
                }.setNegativeButton("Cancelar", null).show()
            return
        }
        val aviso = Toast.makeText(a, "Baixando atualização…", Toast.LENGTH_LONG); aviso.show()
        thread {
            try {
                val dir = File(a.cacheDir, "atualizacao").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "Plantoes-$code.apk")
                baixar(BASE + "Plantoes.apk?t=" + System.currentTimeMillis()).inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                val uri = FileProvider.getUriForFile(a, "${a.packageName}.arquivos", f)
                a.runOnUiThread {
                    a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (_: Exception) {
                a.runOnUiThread { Toast.makeText(a, "Não foi possível baixar a atualização. Tente de novo.", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
