package br.med.plantoes

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class MainActivity : AppCompatActivity(), Acoes {
    private lateinit var box: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var nav: BottomNavigationView
    private lateinit var pal: Pal
    private lateinit var ui: Ui
    private lateinit var telas: Telas
    private var tab = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { telas.tick(); handler.postDelayed(this, 30_000) }
    }

    private val itens: List<Plantao> get() = Store.all(this)

    // ---------- arquivos de sincronização ----------
    private val criarArquivo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(Store.exportar(this).toByteArray()) }
            MaterialAlertDialogBuilder(this).setTitle("Arquivo salvo")
                .setMessage("Agora abra a página no navegador, toque em \"Receber do app\" e escolha este arquivo (${itens.size} plantões).")
                .setPositiveButton("OK", null).show()
        } catch (_: Exception) { Toast.makeText(this, "Não foi possível salvar o arquivo.", Toast.LENGTH_LONG).show() }
    }

    private var exportarPendente: Pair<Int, Int?>? = null
    private val criarPlanilha = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val (ano, mes) = exportarPendente ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(planilha(ano, mes).toByteArray(Charsets.UTF_8)) }
            Snackbar.make(box, "Planilha salva. Abra no Excel, Google Planilhas ou envie ao contador.", Snackbar.LENGTH_LONG).show()
        } catch (_: Exception) { Toast.makeText(this, "Não foi possível salvar a planilha.", Toast.LENGTH_LONG).show() }
    }

    /** Planilha CSV no padrão brasileiro (ponto e vírgula, vírgula decimal), abre direto no Excel. */
    private fun planilha(ano: Int, mes: Int?): String {
        fun n(v: Double) = "%.2f".format(java.util.Locale.US, v).replace(".", ",")
        fun q(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
        val ps = itens.filter { val l = it.inicio.ldt(); l.year == ano && (mes == null || l.monthValue == mes) }.sortedBy { it.inicio }
        val sb = StringBuilder("\uFEFF")
        sb.append("Data;Dia;Início;Fim;Hospital;Setor;Horas;Situação;Fichas;UTI;R$ por ficha;R$ por UTI;Valor fixo;Ganho;R$ por hora\n")
        for (p in ps) {
            val l = p.inicio.ldt()
            val sit = when (p.situacao) { Plantao.REPASSADO -> "Repassado " + p.colega; Plantao.COBERTURA -> "Cobertura " + p.colega; else -> "" }.trim()
            val ganho = if (p.ativo && p.temGanho) p.ganho else 0.0
            sb.append(listOf(
                "%02d/%02d/%04d".format(l.dayOfMonth, l.monthValue, l.year), q(diaCurto(p.inicio).substringBefore(" ")), hm(p.inicio), hm(p.fim),
                q(p.local), q(p.setor), n(p.horas), q(sit), (p.fichas ?: "").toString(), (p.uti ?: "").toString(),
                if (p.lancado) n(p.porFicha) else "", if (p.lancado) n(p.porUti) else "", if (p.valor > 0) n(p.valor) else "",
                if (p.ativo && p.temGanho) n(ganho) else "", if (p.ativo && p.temGanho) n(p.porHora) else "",
            ).joinToString(";")).append("\n")
        }
        val meus = ps.filter { it.ativo && it.temGanho }
        sb.append("\n;;;;Total;;${n(meus.sumOf { it.horas })};;${meus.sumOf { it.fichas ?: 0 }};${meus.sumOf { it.uti ?: 0 }};;;;${n(meus.sumOf { it.ganho })};")
        sb.append(if (meus.sumOf { it.horas } > 0) n(meus.sumOf { it.ganho } / meus.sumOf { it.horas }) else "").append("\n")
        val fixos = Fixos.todos(this)
        val mesesFx = if (mes != null) listOf(java.time.YearMonth.of(ano, mes)) else (1..12).map { java.time.YearMonth.of(ano, it) }
        val linhasFx = mesesFx.flatMap { ym -> fixos.filter { it.ativaEm(ym) }.map { ym to it } }
        if (linhasFx.isNotEmpty()) {
            sb.append("\nSalário fixo;Nome;Valor líquido\n")
            for ((ym, f) in linhasFx) sb.append("%02d/%04d;${q(f.nome)};${n(f.valor)}\n".format(ym.monthValue, ym.year))
            sb.append(";Total do período;${n(meus.sumOf { it.ganho } + linhasFx.sumOf { it.second.valor })}\n")
        }
        val rec = Recebimentos.todos(this)
        val chaves = rec.keys.filter { it.startsWith("%04d-".format(ano)) && (mes == null || it.startsWith("%04d-%02d".format(ano, mes))) }.sorted()
        if (chaves.isNotEmpty()) {
            sb.append("\nRecebimentos;Hospital;Valor recebido;Data\n")
            for (k in chaves) { val r = rec[k]!!; sb.append("${k.substringBefore("|")};${q(k.substringAfter("|"))};${n(r.valor)};${diaCurto(r.quando)}\n") }
        }
        return sb.toString()
    }

    // ---------- backup ----------
    private val salvarBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(Backup.gerar(this).toByteArray()) }
            Store.prefs(this).edit().putLong("ultimoBackup", System.currentTimeMillis()).apply()
            Snackbar.make(box, "Backup salvo. Guarde o arquivo no Drive ou envie para você mesmo.", Snackbar.LENGTH_LONG).show()
        } catch (_: Exception) { Toast.makeText(this, "Não foi possível salvar o backup.", Toast.LENGTH_LONG).show() }
    }

    private val lerBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val txt = try { contentResolver.openInputStream(uri)!!.bufferedReader().readText() } catch (_: Exception) { "" }
        MaterialAlertDialogBuilder(this).setTitle("Restaurar backup?")
            .setMessage("Os plantões e configurações deste celular serão substituídos pelos do arquivo.")
            .setPositiveButton("Restaurar") { _, _ ->
                try {
                    val n = Backup.restaurar(this, txt)
                    Toast.makeText(this, "Backup restaurado: $n plantões.", Toast.LENGTH_LONG).show()
                    recreate()
                } catch (_: Exception) {
                    MaterialAlertDialogBuilder(this).setTitle("Arquivo não reconhecido")
                        .setMessage("Escolha um arquivo criado em Ajustes › Fazer backup.").setPositiveButton("OK", null).show()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    override fun backup() = salvarBackup.launch("plantoes-backup-${LocalDate.now()}.json")
    override fun restaurar() = lerBackup.launch(arrayOf("*/*"))

    // ---------- bloqueio com digital / senha do celular ----------
    private val AUTENTICADORES = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private var desbloqueadoEm = 0L
    private var capa: View? = null
    private var pedindo = false

    override fun bloqueio(ligar: Boolean) {
        if (ligar && androidx.biometric.BiometricManager.from(this).canAuthenticate(AUTENTICADORES) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            MaterialAlertDialogBuilder(this).setTitle("Bloqueio indisponível")
                .setMessage("Cadastre uma digital ou um bloqueio de tela (PIN, padrão ou senha) nas configurações do celular e tente de novo.")
                .setPositiveButton("OK", null).show()
            render(false); return
        }
        Store.prefs(this).edit().putBoolean("bloqueio", ligar).apply()
        desbloqueadoEm = System.currentTimeMillis()
    }

    private fun verificarBloqueio() {
        if (!Store.prefs(this).getBoolean("bloqueio", false)) { capa?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; capa = null; return }
        if (System.currentTimeMillis() - desbloqueadoEm < 60_000 || pedindo) return
        if (capa == null) {
            val c = ui.col(32, 32)
            c.gravity = Gravity.CENTER
            c.setBackgroundColor(pal.bg)
            c.isClickable = true
            c.addView(ui.tv("Plantões", 30f, pal.ink, 700).apply { gravity = Gravity.CENTER })
            c.addView(ui.tv("Protegido com a digital ou a senha do celular", 14f, pal.mute).apply { gravity = Gravity.CENTER }, ui.lp(top = 8))
            c.addView(MaterialButton(this).apply { text = "Desbloquear"; isAllCaps = false; setOnClickListener { pedirDigital() } }, ui.lp(WRAP, WRAP, top = 24))
            (findViewById<android.view.ViewGroup>(android.R.id.content)).addView(c, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            capa = c
        }
        pedirDigital()
    }

    private fun pedirDigital() {
        if (pedindo) return
        pedindo = true
        val prompt = androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(codigo: Int, msg: CharSequence) { pedindo = false }
                override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    pedindo = false
                    desbloqueadoEm = System.currentTimeMillis()
                    capa?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
                        capa?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; capa = null
                    }?.start()
                }
            })
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear Plantões").setSubtitle("Use a digital ou a senha do celular")
            .setAllowedAuthenticators(AUTENTICADORES).build()
        try { prompt.authenticate(info) } catch (_: Exception) { pedindo = false }
    }

    // ---------- renda fixa e meta mensal ----------
    override fun editarMetaMes() {
        val v = ui.col(20, 4)
        val m = numero("R$ por mês", if (Fixos.metaMes(this) > 0) fmtNum(Fixos.metaMes(this)) else "", true)
        v.addView(ui.tv("Quanto você quer fechar no mês, somando salário fixo e produtividade. Deixe vazio para não usar.", 14f, pal.mute), ui.lp(top = 4))
        v.addView(campo("Meta do mês (R$)", m))
        MaterialAlertDialogBuilder(this).setTitle("Meta do mês").setView(v)
            .setPositiveButton("Salvar") { _, _ -> Fixos.salvarMetaMes(this, txt2num(m.text)); render(false) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun mesTexto(k: String) = if (k.length >= 7) "${k.substring(5, 7)}/${k.substring(0, 4)}" else ""
    private fun lerMes(t: String): String? {
        val m = Regex("^\\s*(\\d{1,2})\\s*/\\s*(\\d{4})\\s*$").find(t) ?: return null
        val mm = m.groupValues[1].toInt(); if (mm !in 1..12) return null
        return "%s-%02d".format(m.groupValues[2], mm)
    }

    override fun editarFixo(r: RendaFixa?) {
        val v = ui.col(20, 4)
        v.addView(ui.tv("Valor líquido que cai por mês. Os plantões do local vinculado não pedem fichas: já estão pagos pelo salário.", 14f, pal.mute), ui.lp(top = 4))
        val nome = TextInputEditText(this).apply { setText(r?.nome ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        v.addView(campo("Nome (ex.: contrato do hospital)", nome))
        val valor = numero("R$", r?.let { fmtNum(it.valor) } ?: "", true)
        v.addView(campo("Valor líquido por mês (R$)", valor))
        val hoje = java.time.YearMonth.now()
        val desde = TextInputEditText(this).apply { setText(r?.let { mesTexto(it.desde) } ?: "%02d/%04d".format(hoje.monthValue, hoje.year)); inputType = InputType.TYPE_CLASS_DATETIME }
        val ate = TextInputEditText(this).apply { setText(r?.let { mesTexto(it.ate) } ?: ""); inputType = InputType.TYPE_CLASS_DATETIME }
        val rl = ui.lin(Gravity.TOP)
        rl.addView(campo("Desde (mês/ano)", desde).apply { layoutParams = ui.lp(0, WRAP, top = 12, peso = 1f) })
        rl.addView(campo("Até (opcional)", ate).apply { layoutParams = ui.lp(0, WRAP, top = 12, start = 10, peso = 1f) })
        v.addView(rl)
        val nenhum = "Nenhum"
        val locais = listOf(nenhum) + itens.map { it.local.trim() }.distinct().sorted()
        val local = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL; setSimpleItems(locais.toTypedArray())
            setText(r?.local?.takeIf { it.isNotBlank() } ?: nenhum, false)
        }
        v.addView(campo("Plantões deste local fazem parte do salário", local, true))
        val sv = NestedScrollView(this); sv.addView(v)
        val dlg = MaterialAlertDialogBuilder(this).setTitle(if (r == null) "Salário ou renda fixa" else r.nome).setView(sv)
            .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null)
            .apply { if (r != null) setNeutralButton("Remover") { _, _ -> Fixos.remover(this@MainActivity, r.id); render(false) } }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = nome.text.toString().trim(); val vv = txt2num(valor.text)
                val d0 = lerMes(desde.text.toString()); val a0 = if (ate.text.isNullOrBlank()) "" else lerMes(ate.text.toString())
                when {
                    n.isEmpty() -> (nome.parent.parent as? TextInputLayout)?.error = "Dê um nome"
                    vv <= 0 -> (valor.parent.parent as? TextInputLayout)?.error = "Informe o valor"
                    d0 == null -> (desde.parent.parent as? TextInputLayout)?.error = "Use mês/ano, ex.: 01/2026"
                    a0 == null -> (ate.parent.parent as? TextInputLayout)?.error = "Use mês/ano"
                    else -> {
                        val loc = local.text.toString().let { if (it == nenhum) "" else it }
                        Fixos.salvar(this, RendaFixa(r?.id ?: java.util.UUID.randomUUID().toString(), n, vv, d0, a0, loc))
                        dlg.dismiss(); render(false)
                    }
                }
            }
        }
        dlg.show()
    }

    override fun exportar(ano: Int, mes: Int?) {
        exportarPendente = ano to mes
        criarPlanilha.launch(if (mes == null) "plantoes-$ano.csv" else "plantoes-%04d-%02d.csv".format(ano, mes))
    }

    override fun marcarRecebido(chave: String, local: String, mesTxt: String, esperado: Double) {
        val atual = Recebimentos.todos(this)[chave]
        val v = ui.col(20, 4)
        v.addView(ui.tv("Esperado: ${brl(esperado)}\nInforme quanto caiu na conta.", 14f, pal.mute), ui.lp(top = 4))
        val campoV = numero("Valor recebido", fmtNum(atual?.valor ?: esperado), true)
        v.addView(campo("Valor recebido (R$)", campoV))
        val b = MaterialAlertDialogBuilder(this).setTitle("$local · $mesTxt").setView(v)
            .setPositiveButton("Marcar recebido") { _, _ ->
                Recebimentos.salvar(this, chave, Recebido(txt2num(campoV.text), System.currentTimeMillis())); render(false)
            }
            .setNegativeButton("Cancelar", null)
        if (atual != null) b.setNeutralButton("Desmarcar") { _, _ -> Recebimentos.salvar(this, chave, null); render(false) }
        b.show()
    }

    private val abrirArquivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val txt = try { contentResolver.openInputStream(uri)!!.bufferedReader().readText() } catch (_: Exception) { "" }
        val novos = try {
            Store.lerArquivo(txt)
        } catch (_: Exception) {
            MaterialAlertDialogBuilder(this).setTitle("Arquivo não reconhecido")
                .setMessage("Escolha o arquivo gerado pelo botão \"Enviar para o app\" da página (plantoes-pagina.json).")
                .setPositiveButton("OK", null).show()
            return@registerForActivityResult
        }
        receberLista(novos, Store.lerFixos(txt))
    }

    override fun onCreate(s: Bundle?) {
        setTheme(Temas.atual(this).estilo)
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        pal = Pal(this)
        ui = Ui(this, pal)
        telas = Telas(ui, this)
        tab = s?.getInt("aba") ?: 0
        box = findViewById(R.id.box)
        scroll = findViewById(R.id.scroll)
        fab = findViewById(R.id.fab)
        nav = findViewById(R.id.nav)
        fab.setOnClickListener { editar(null) }
        scroll.setOnScrollChangeListener { _: NestedScrollView, _: Int, y: Int, _: Int, oy: Int ->
            if (y > oy + 8 && fab.isExtended) fab.shrink() else if (y < oy - 8 && !fab.isExtended) fab.extend()
        }
        nav.selectedItemId = listOf(R.id.t_inicio, R.id.t_agenda, R.id.t_cal, R.id.t_ganhos, R.id.t_ajustes)[tab]
        nav.setOnItemSelectedListener {
            val nova = when (it.itemId) { R.id.t_inicio -> 0; R.id.t_agenda -> 1; R.id.t_cal -> 2; R.id.t_ganhos -> 3; else -> 4 }
            if (nova != tab) { tab = nova; render(true); scroll.scrollTo(0, 0) }
            true
        }
        nav.setOnItemReselectedListener { scroll.smoothScrollTo(0, 0) }

        Alarms.canal(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Alarms.reagendar(this)
        val pr = Store.prefs(this)
        if (itens.isEmpty() && !pr.getBoolean("boasVindas", false)) {
            pr.edit().putBoolean("boasVindas", true).apply()
            MaterialAlertDialogBuilder(this).setTitle("Bem-vindo ao Plantões")
                .setMessage("Para trazer a escala que você já montou na página:\n\n1. Na página, toque em \"Enviar para o app\".\n2. Aqui, toque em \"Receber da página\" e escolha o arquivo baixado.\n\nOu cadastre plantões pelo botão Plantão.")
                .setPositiveButton("Receber da página") { _, _ -> receber() }
                .setNegativeButton("Agora não", null).show()
        }
        Atualizador.verificar(this, false)
        abrirLancamento(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        abrirLancamento(i)
    }

    /** Veio da notificação "Plantão encerrado": abre direto o lançamento de fichas. */
    private fun abrirLancamento(i: Intent?) {
        val id = i?.getStringExtra("lancar") ?: return
        i.removeExtra("lancar")
        itens.find { it.id == id }?.let { scroll.post { lancar(it) } }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("aba", tab) }

    override fun onResume() {
        super.onResume()
        verificarBloqueio()
        Series.gerar(this)
        ProximoWidget.atualizar(this)
        render(false)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 30_000)
    }

    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun dados() = Dados(
        lista = itens.toList(),
        now = System.currentTimeMillis(),
        descansoMin = Store.descansoMin(this),
        minLivreH = Store.prefs(this).getInt("minLivreH", 6),
        faltas = faltas(),
        volMax = Store.prefs(this).getBoolean("volMax", true),
        versao = Atualizador.nomeVersao(this),
        regras = Regras.todas(this),
        meta = Regras.meta(this),
        recebidos = Recebimentos.todos(this),
        fixos = Fixos.todos(this),
        metaMes = Fixos.metaMes(this),
        bloqueio = Store.prefs(this).getBoolean("bloqueio", false),
        series = Series.todas(this),
    )

    /** Redesenha a aba atual. Com `animar`, o conteúdo entra deslizando de leve (troca de aba). */
    private fun render(animar: Boolean) {
        val d = dados()
        val v = when (tab) {
            0 -> telas.inicio(d)
            1 -> telas.agenda(d)
            2 -> telas.calendario(d)
            3 -> telas.financeiro(d)
            else -> telas.ajustes(d, Temas.atual(this).id)
        }
        box.removeAllViews()
        box.addView(v)
        box.addView(View(this), ui.lp(h = ui.dp(96)))
        if (tab == 4) fab.hide() else fab.show()
        if (animar) {
            box.alpha = 0f; box.translationY = ui.dpf(10f)
            box.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ---------- Acoes chamadas pelas telas ----------
    override fun redesenhar() = render(false)

    override fun irPara(aba: Int) {
        nav.selectedItemId = listOf(R.id.t_inicio, R.id.t_agenda, R.id.t_cal, R.id.t_ganhos, R.id.t_ajustes)[aba]
    }

    override fun testarAlarme() {
        Alarms.teste(this)
        Toast.makeText(this, "O alarme toca em 5 segundos. Bloqueie a tela para ver como fica.", Toast.LENGTH_LONG).show()
    }

    override fun receber() = abrirArquivo.launch(arrayOf("*/*"))
    override fun enviar() = criarArquivo.launch("plantoes-app.json")
    override fun procurarAtualizacao() = Atualizador.verificar(this, true)

    override fun mudarTema(id: String) {
        Store.prefs(this).edit().putString("tema", id).apply()
        recreate()
    }

    override fun volumeMaximo(v: Boolean) { Store.prefs(this).edit().putBoolean("volMax", v).apply() }

    override fun mudarFiltroLivre(minH: Int?, descansoH: Int?) {
        val e = Store.prefs(this).edit()
        if (minH != null) e.putInt("minLivreH", minH)
        if (descansoH != null) e.putInt("descansoH", descansoH)
        e.apply()
        render(false)
    }

    // ---------- permissões ----------
    private fun faltas(): List<String> {
        val f = mutableListOf<String>()
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) f += "notificações"
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) f += "alarmes no horário exato"
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) f += "alarme em tela cheia"
        return f
    }

    private fun abrir(i: Intent) {
        try { startActivity(i) } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun permissoes() {
        val itensP = mutableListOf<Pair<String, () -> Unit>>()
        val notif = NotificationManagerCompat.from(this).areNotificationsEnabled()
        itensP += "${if (notif) "✓" else "✗"}  Notificações" to {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else abrir(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val ok = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            itensP += "${if (ok) "✓" else "✗"}  Alarmes no horário exato" to {
                abrir(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            itensP += "${if (ok) "✓" else "✗"}  Alarme em tela cheia" to {
                abrir(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        val bat = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        itensP += "${if (bat) "✓" else "•"}  Sem restrição de bateria (recomendado)" to {
            abrir(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        itensP += "     Som do canal de alarmes" to {
            abrir(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, Alarms.CANAL))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Toque para ajustar")
            .setItems(itensP.map { it.first }.toTypedArray()) { _, i -> itensP[i].second() }
            .setPositiveButton("Fechar", null).show()
    }

    override fun ajuda() {
        MaterialAlertDialogBuilder(this).setTitle("Como funcionam os alarmes").setMessage(
            "Cada plantão gera dois alarmes: 24 horas antes e 2 horas antes do início.\n\n" +
                "Eles usam o mesmo sistema do despertador do Android: tocam no horário exato mesmo com o celular bloqueado ou o app fechado, e voltam a ser agendados sozinhos se o celular reiniciar.\n\n" +
                "Quando o alarme dispara, a tela acende sobre o bloqueio, pisca em vermelho, a lanterna pisca, o som toca e o celular vibra até você tocar em CIENTE (ou Soneca 10 min). Depois de 5 minutos sem resposta o som para.\n\n" +
                "Em celulares com economia de bateria agressiva, deixe o app sem restrição de bateria em Permissões."
        ).setPositiveButton("Entendi", null).show()
    }

    // ---------- cadastro / edição (painel que desliza de baixo) ----------
    private val DURS = listOf(4, 5, 6, 8, 10, 12, 14, 16, 18, 24)

    private fun campo(dica: String, filho: android.widget.EditText, estiloMenu: Boolean = false): TextInputLayout {
        val estilo = if (estiloMenu) com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
        else com.google.android.material.R.attr.textInputOutlinedStyle
        return TextInputLayout(this, null, estilo).apply {
            hint = dica
            addView(filho, LinearLayout.LayoutParams(MATCH, WRAP))
            layoutParams = ui.lp(top = 12)
        }
    }

    override fun editar(p: Plantao?, preset: Long?) {
        fecharDia()
        val sheet = BottomSheetDialog(this)
        val v = ui.col(20, 8)
        var ini: LocalDateTime = (p?.inicio ?: preset ?: LocalDate.now().plusDays(1).atTime(19, 0).ms()).ldt().withSecond(0).withNano(0)

        val topo = ui.lin()
        topo.addView(ui.tv(if (p == null) "Novo plantão" else "Editar plantão", 22f, pal.ink, 700), ui.lp(0, WRAP, peso = 1f))
        if (p != null) topo.addView(ui.icone(R.drawable.ic_delete, "Excluir só este plantão", pal.choque) { sheet.dismiss(); excluir(p) },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        v.addView(topo, ui.lp(top = 8, bottom = 4))
        if (p != null && p.serieId.isEmpty() && p.ativo && p.inicio > System.currentTimeMillis()) {
            v.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (p.avulso) "↻ Tornar fixo e repetir sempre" else "↻ Repetir sempre (escala automática)"; isAllCaps = false
                setOnClickListener { sheet.dismiss(); tornarFixo(p) }
            }, ui.lp(top = 4, bottom = 4))
        }

        val locais = Regras.locais(this).toTypedArray()
        val local = MaterialAutoCompleteTextView(this).apply {
            setText(p?.local ?: ""); threshold = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSimpleItems(locais)
        }
        v.addView(campo("Local de trabalho", local))
        val infoLocal = ui.tv("", 12f, pal.mute)
        v.addView(infoLocal, ui.lp(top = 4))
        fun mostraLocal() {
            val n = local.text.toString().trim()
            val rg = Regras.todas(this)[n]
            infoLocal.text = when {
                n.isEmpty() -> "Escolha um local cadastrado ou digite um novo"
                n in Fixos.locais(this) -> "Salário mensal: este plantão não pede fichas"
                rg != null -> rg.resumo()
                else -> "Local novo: depois de salvar, você poderá informar como ele paga"
            }
        }
        local.setOnItemClickListener { _, _, _, _ -> mostraLocal() }
        local.setOnFocusChangeListener { _, tem -> if (!tem) mostraLocal() }
        mostraLocal()
        val setor = TextInputEditText(this).apply { setText(p?.setor ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        v.addView(campo("Setor ou turno (opcional)", setor))

        val data = TextInputEditText(this).apply { isFocusable = false; isCursorVisible = false }
        val hora = TextInputEditText(this).apply { isFocusable = false; isCursorVisible = false }
        fun atualiza() { data.setText(diaCurto(ini.toLocalDate())); hora.setText("%02d:%02d".format(ini.hour, ini.minute)) }
        atualiza()
        data.setOnClickListener {
            val sel = ini.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val dp = MaterialDatePicker.Builder.datePicker().setTitleText("Data do plantão").setSelection(sel).build()
            dp.addOnPositiveButtonClickListener { ms ->
                val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                ini = LocalDateTime.of(d, ini.toLocalTime()); atualiza()
            }
            dp.show(supportFragmentManager, "data")
        }
        hora.setOnClickListener {
            val tp = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(ini.hour).setMinute(ini.minute)
                .setTitleText("Início do plantão").build()
            tp.addOnPositiveButtonClickListener { ini = ini.withHour(tp.hour).withMinute(tp.minute); atualiza() }
            tp.show(supportFragmentManager, "hora")
        }
        val rdh = ui.lin(Gravity.TOP)
        rdh.addView(campo("Data", data).apply { layoutParams = ui.lp(0, WRAP, top = 12, peso = 1.4f) })
        rdh.addView(campo("Início", hora).apply { layoutParams = ui.lp(0, WRAP, top = 12, start = 10, peso = 1f) })
        v.addView(rdh)

        val durAtual = p?.durMin?.div(60) ?: 12
        val durs = (DURS + durAtual).distinct().sorted()
        val dur = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL; setSimpleItems(durs.map { "$it horas" }.toTypedArray()); setText("$durAtual horas", false)
        }
        val valor = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (p != null && p.valor > 0) setText(p.valor.toString())
        }
        val rdv = ui.lin(Gravity.TOP)
        rdv.addView(campo("Duração", dur, true).apply { layoutParams = ui.lp(0, WRAP, top = 12, peso = 1f) })
        rdv.addView(campo("Valor fixo (R$)", valor).apply { layoutParams = ui.lp(0, WRAP, top = 12, start = 10, peso = 1f) })
        v.addView(rdv)

        val obs = TextInputEditText(this).apply {
            setText(p?.obs ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        v.addView(campo("Observações (opcional)", obs))

        // fixo (da escala) ou avulso (extra)
        val tipos = listOf(Plantao.FIXO to "Fixo · da minha escala", Plantao.AVULSO to "Avulso · plantão extra")
        val tipo = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL; setSimpleItems(tipos.map { it.second }.toTypedArray())
            setText(if (p?.avulso == true) tipos[1].second else tipos[0].second, false)
        }
        v.addView(campo("Tipo", tipo, true))

        // repetição automática, sem fim: o app vai criando os próximos sozinho
        val serieAtual = p?.serieId?.takeIf { it.isNotEmpty() }?.let { Series.de(this, it) }
        val reps = listOf("Não repete" to 0, "Toda semana" to 7, "A cada 15 dias" to 14, "A cada 3 semanas" to 21, "A cada 4 semanas" to 28)
        val rep = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL; setSimpleItems(reps.map { it.first }.toTypedArray()); setText(reps[0].first, false)
        }
        val campoRep = campo("Repetir (fica automático até você mudar)", rep, true)
        val infoSerie = ui.tv(serieAtual?.let { "↻ Faz parte da escala fixa: ${it.descricao()}" } ?: "", 13f, pal.acc, 500)
        fun ajustaTipo() {
            val fixo = tipo.text.toString() == tipos[0].second
            campoRep.visibility = if (fixo && serieAtual == null) View.VISIBLE else View.GONE
            infoSerie.visibility = if (serieAtual != null) View.VISIBLE else View.GONE
        }
        tipo.setOnItemClickListener { _, _, _, _ -> ajustaTipo() }
        v.addView(campoRep)
        v.addView(infoSerie, ui.lp(top = 10))
        ajustaTipo()

        // troca de plantão: repassei para um colega / estou cobrindo um colega
        val sits = listOf("" to "Meu plantão", Plantao.COBERTURA to "Estou cobrindo um colega", Plantao.REPASSADO to "Passei para um colega")
        val sit = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL; setSimpleItems(sits.map { it.second }.toTypedArray())
            setText(sits.firstOrNull { it.first == (p?.situacao ?: "") }?.second ?: sits[0].second, false)
        }
        val colega = TextInputEditText(this).apply { setText(p?.colega ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val campoColega = campo("Nome do colega (opcional)", colega)
        fun ajustaColega() { campoColega.visibility = if (sit.text.toString() == sits[0].second) View.GONE else View.VISIBLE }
        sit.setOnItemClickListener { _, _, _, _ -> ajustaColega() }
        ajustaColega()
        v.addView(campo("Situação", sit, true))
        v.addView(campoColega)

        val botoes = ui.lin()
        botoes.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val cancelar = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "Cancelar"; isAllCaps = false }
        val salvar = MaterialButton(this).apply { text = "Salvar"; isAllCaps = false; minWidth = ui.dp(120) }
        botoes.addView(cancelar); botoes.addView(salvar, ui.lp(WRAP, WRAP, start = 8))
        v.addView(botoes, ui.lp(top = 20, bottom = 16))
        cancelar.setOnClickListener { sheet.dismiss() }
        salvar.setOnClickListener {
            val nome = local.text.toString().trim()
            if (nome.isEmpty()) { (local.parent.parent as? TextInputLayout)?.error = "Informe o local"; return@setOnClickListener }
            val horasDur = dur.text.toString().substringBefore(" ").toIntOrNull() ?: 12
            val rgLocal = Regras.todas(this)[nome]
            val vv = valor.text.toString().replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 }
                ?: if (p == null || p.valor <= 0) rgLocal?.valorPara(horasDur * 60) ?: 0.0 else 0.0
            val localNovo = rgLocal == null && nome !in Fixos.locais(this) && Regras.locais(this).none { it.equals(nome, true) }
            val antes = Store.conflitos(itens).size
            val longasAntes = Jornadas.longas(itens).size
            val situacao = sits.firstOrNull { it.second == sit.text.toString() }?.first ?: ""
            val nomeColega = if (situacao.isEmpty()) "" else colega.text.toString().trim()
            val tipoSel = if (tipo.text.toString() == tipos[1].second) Plantao.AVULSO else Plantao.FIXO
            val intervalo = if (tipoSel == Plantao.FIXO) reps.firstOrNull { it.first == rep.text.toString() }?.second ?: 0 else 0
            val setorTxt = setor.text.toString().trim(); val obsTxt = obs.text.toString().trim()
            fun serieNova(id: String = java.util.UUID.randomUUID().toString(), intervaloS: Int) = Serie(
                id = id, local = nome, setor = setorTxt, hora = "%02d:%02d".format(ini.hour, ini.minute), durMin = horasDur * 60,
                inicio = ini.toLocalDate().toString(), intervalo = intervaloS, valor = vv, obs = obsTxt)
            sheet.dismiss()
            if (p == null) {
                if (intervalo > 0) {
                    val n = Series.criar(this, serieNova(intervaloS = intervalo))
                    Snackbar.make(box, "Escala automática criada: $n plantões já na agenda, e o app continua criando os próximos.", Snackbar.LENGTH_LONG).setAnchorView(fab).show()
                } else Store.adicionar(this, listOf(Plantao(local = nome, setor = setorTxt, inicio = ini.ms(), durMin = horasDur * 60, valor = vv,
                    obs = obsTxt, situacao = situacao, colega = nomeColega, tipo = tipoSel)))
                depoisDeSalvar(antes, longasAntes, if (localNovo) nome else null)
            } else {
                val novoP = p.copy(local = nome, setor = setorTxt, inicio = ini.ms(), durMin = horasDur * 60, valor = vv,
                    obs = obsTxt, situacao = situacao, colega = nomeColega, tipo = tipoSel)
                if (serieAtual != null && tipoSel == Plantao.FIXO) {
                    // pertence a uma escala: mudar só este ou este e os próximos
                    MaterialAlertDialogBuilder(this).setTitle("Alterar a escala?")
                        .setMessage("Este plantão faz parte da escala fixa (${serieAtual.descricao()}).")
                        .setPositiveButton("Só este") { _, _ -> Store.atualizar(this, novoP); render(false); depoisDeSalvar(antes, longasAntes, if (localNovo) nome else null) }
                        .setNeutralButton("Este e os próximos") { _, _ ->
                            Series.encerrar(this, serieAtual.id, Series.dataDe(p))
                            Series.criar(this, serieNova(intervaloS = serieAtual.intervalo).copy(ate = serieAtual.ate))
                            render(false); depoisDeSalvar(antes, longasAntes, if (localNovo) nome else null)
                            Snackbar.make(box, "Escala atualizada a partir de ${diaCurto(ini.toLocalDate())}.", Snackbar.LENGTH_LONG).setAnchorView(fab).show()
                        }
                        .setNegativeButton("Cancelar", null).show()
                    return@setOnClickListener
                }
                if (serieAtual != null) { Series.excecao(this, serieAtual.id, Series.dataDe(p).toString()); Store.atualizar(this, novoP.copy(serieId = "")) }
                else Store.atualizar(this, novoP)
                if (intervalo > 0 && serieAtual == null) {
                    val n = Series.criar(this, serieNova(intervaloS = intervalo))
                    Snackbar.make(box, "Virou escala automática: $n plantões novos criados.", Snackbar.LENGTH_LONG).setAnchorView(fab).show()
                }
                depoisDeSalvar(antes, longasAntes, if (localNovo) nome else null)
            }
            render(false)
        }

        val sv = NestedScrollView(this); sv.addView(v)
        sheet.setContentView(sv)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Avisos depois de salvar: choque de horário ou jornada longa criada por este plantão. */
    private fun depoisDeSalvar(antes: Int, longasAntes: Int, localNovo: String? = null) {
            if (localNovo != null) {
                Snackbar.make(box, "Novo local: $localNovo. Informe como ele paga.", 8000)
                    .setAction("Configurar") { editarRegra(localNovo) }.apply { if (fab.isShown) setAnchorView(fab) }.show()
                return
            }
            val novasLongas = Jornadas.longas(itens)
            if (Store.conflitos(itens).size > antes)
                Snackbar.make(box, "Atenção: este plantão bate com outro já agendado.", Snackbar.LENGTH_LONG).setAnchorView(fab).show()
            else if (novasLongas.size > longasAntes) {
                val j = novasLongas.maxBy { it.horas }
                MaterialAlertDialogBuilder(this).setTitle("Jornada longa")
                    .setMessage("Com este plantão você fica ${horasCurto(Math.round(j.horas * 60))} seguidas trabalhando " +
                        "(${diaCurto(j.ini)} ${hm(j.ini)} até ${diaCurto(j.fim)} ${hm(j.fim)}).\n\nO plantão foi salvo. Se puder, reserve descanso depois.")
                    .setPositiveButton("Entendi", null).show()
            }
    }


    // ---------- produtividade: fichas, valores por hospital e meta ----------
    private fun numero(dica: String, valor: String, decimal: Boolean = false) = TextInputEditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        setText(valor); setSelectAllOnFocus(true)
    }

    private fun txt2num(t: CharSequence?) = t.toString().replace(",", ".").trim().toDoubleOrNull() ?: 0.0
    private fun fmtNum(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace(".", ",")

    /** Campo numérico com botões − e + ao lado (bom para digitar com uma mão no fim do plantão). */
    private fun contador(dica: String, entrada: TextInputEditText): View {
        val r = ui.lin(Gravity.CENTER_VERTICAL)
        fun passo(d: Int) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (d < 0) "−" else "+"; textSize = 20f; minWidth = ui.dp(52); minimumWidth = ui.dp(52)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                val n = (txt2num(entrada.text).toInt() + d).coerceAtLeast(0)
                entrada.setText(n.toString())
            }
        }
        r.addView(passo(-1), ui.lp(WRAP, ui.dp(56)))
        r.addView(campo(dica, entrada).apply { layoutParams = ui.lp(0, WRAP, start = 8, end = 8, peso = 1f) })
        r.addView(passo(+1), ui.lp(WRAP, ui.dp(56)))
        r.layoutParams = ui.lp(top = 4)
        return r
    }

    override fun lancar(p: Plantao) {
        fecharDia()
        val sheet = BottomSheetDialog(this)
        val v = ui.col(20, 8)
        val regra = Regras.de(this, p.local)
        val porFicha0 = if (p.lancado) p.porFicha else regra.porFicha
        val porUti0 = if (p.lancado) p.porUti else regra.porUti

        v.addView(ui.tv("Fichas do plantão", 22f, pal.ink, 700), ui.lp(top = 8))
        v.addView(ui.tv("${p.local} · ${diaCurto(p.inicio)} · ${hm(p.inicio)}–${hm(p.fim)} (${horas(p.durMin.toLong())})", 14f, pal.mute), ui.lp(top = 4, bottom = 4))

        val fichas = numero("Atendimentos", (p.fichas ?: 0).toString())
        val uti = numero("UTI", (p.uti ?: 0).toString())
        v.addView(contador("Atendimentos (fichas)", fichas))
        v.addView(contador("Pacientes de UTI", uti))

        // valores do hospital: abertos se ainda não configurados
        val vf = numero("R$ por atendimento", if (porFicha0 > 0) fmtNum(porFicha0) else "", true)
        val vu = numero("R$ por UTI", if (porUti0 > 0) fmtNum(porUti0) else "", true)
        val valores = ui.lin(Gravity.TOP)
        valores.addView(campo("R$ por atendimento", vf).apply { layoutParams = ui.lp(0, WRAP, top = 12, peso = 1f) })
        valores.addView(campo("R$ por UTI", vu).apply { layoutParams = ui.lp(0, WRAP, top = 12, start = 10, peso = 1f) })
        val padrao = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Usar estes valores sempre em ${p.local}"; isChecked = true
        }
        val abrirValores = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false; text = "Valores: ${brl(porFicha0)} por atendimento · ${brl(porUti0)} por UTI  ›"
            textSize = 13f; setPadding(0, 0, 0, 0)
        }
        val mostrarCampos = !regra.configurada && !p.lancado
        valores.visibility = if (mostrarCampos) View.VISIBLE else View.GONE
        padrao.visibility = valores.visibility
        abrirValores.visibility = if (mostrarCampos) View.GONE else View.VISIBLE
        abrirValores.setOnClickListener { valores.visibility = View.VISIBLE; padrao.visibility = View.VISIBLE; abrirValores.visibility = View.GONE }
        v.addView(abrirValores, ui.lp(WRAP, WRAP, top = 6))
        v.addView(valores)
        v.addView(padrao, ui.lp(WRAP, WRAP, top = 4))

        // resultado ao vivo
        val res = ui.card(pal.accClaro, pal.accClaro)
        val rc = ui.col(16, 14)
        val total = ui.tv("", 30f, pal.ink, 700, num = true)
        val hora = ui.tv("", 14f, pal.ink2, 600, num = true)
        val dica = ui.tv("", 13f, pal.mute, 400)
        rc.addView(ui.tv("Ganho do plantão", 13f, pal.accEscuro, 500))
        rc.addView(total, ui.lp(top = 4)); rc.addView(hora, ui.lp(top = 4)); rc.addView(dica, ui.lp(top = 6))
        res.addView(rc)
        v.addView(res, ui.lp(top = 14))
        val meta = Regras.meta(this)
        fun calcula() {
            val f = txt2num(fichas.text).toInt(); val u = txt2num(uti.text).toInt()
            val pf = txt2num(vf.text); val pu = txt2num(vu.text)
            val g = p.valor + f * pf + u * pu
            val ph = g / p.horas
            total.text = brl(g)
            hora.text = "${brl0(ph)}/h · meta ${brl0(meta)}/h"
            hora.setTextColor(if (ph >= meta) pal.livreTexto else pal.alerta)
            dica.text = if (pf <= 0) "Informe quanto o hospital paga por atendimento."
            else {
                val precisa = Math.ceil((meta * p.horas - p.valor - u * pu) / pf).toInt().coerceAtLeast(0)
                if (f >= precisa) "Meta batida neste plantão." else "Para a meta seriam $precisa fichas (faltaram ${precisa - f})."
            }
        }
        val observador = object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) = calcula()
        }
        listOf(fichas, uti, vf, vu).forEach { it.addTextChangedListener(observador) }
        calcula()

        val botoes = ui.lin(); botoes.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        if (p.lancado) botoes.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Apagar lançamento"; isAllCaps = false; setTextColor(pal.choque)
            setOnClickListener {
                Store.atualizar(this@MainActivity, p.copy(fichas = null, uti = null, porFicha = 0.0, porUti = 0.0))
                sheet.dismiss(); render(false)
            }
        }, ui.lp(0, WRAP, peso = 1f))
        val cancelar = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "Cancelar"; isAllCaps = false }
        val salvar = MaterialButton(this).apply { text = "Salvar"; isAllCaps = false; minWidth = ui.dp(120) }
        botoes.addView(cancelar); botoes.addView(salvar, ui.lp(WRAP, WRAP, start = 8))
        v.addView(botoes, ui.lp(top = 16, bottom = 16))
        cancelar.setOnClickListener { sheet.dismiss() }
        salvar.setOnClickListener {
            val pf = txt2num(vf.text); val pu = txt2num(vu.text)
            val novo = p.copy(fichas = txt2num(fichas.text).toInt(), uti = txt2num(uti.text).toInt(), porFicha = pf, porUti = pu)
            Store.atualizar(this, novo)
            if (padrao.visibility == View.VISIBLE && padrao.isChecked) Regras.salvar(this, p.local, Regra(pf, pu))
            sheet.dismiss(); render(false)
            Snackbar.make(box, "Lançado: ${brl(novo.ganho)} · ${brl0(novo.porHora)}/h", Snackbar.LENGTH_LONG)
                .apply { if (fab.isShown) setAnchorView(fab) }.show()
            getSystemService(NotificationManager::class.java).cancel(p.id.hashCode() xor 4)
        }

        val sv = NestedScrollView(this); sv.addView(v)
        sheet.setContentView(sv)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Cadastro de local de trabalho: nome, forma de pagamento, valores, deslocamento e lembrete. local vazio = novo. */
    override fun editarRegra(local: String) {
        fecharDia()
        val novoLocal = local.isBlank()
        val r = if (novoLocal) Regra() else Regras.de(this, local)
        val fxAtual = Fixos.todos(this).firstOrNull { it.local.trim() == local.trim() && local.isNotBlank() }
        val sheet = BottomSheetDialog(this)
        val v = ui.col(20, 8)
        v.addView(ui.tv(if (novoLocal) "Novo local de trabalho" else "Local de trabalho", 22f, pal.ink, 700), ui.lp(top = 8, bottom = 4))

        val nome = TextInputEditText(this).apply { setText(local); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        v.addView(campo("Nome (ex.: Hospital Regional, Clínica Sul)", nome))

        val formas = listOf(Regra.PRODUCAO to "Por produtividade (fichas e UTI)", Regra.PLANTAO to "Valor fixo por plantão",
            Regra.HORA to "Valor por hora", Regra.SALARIO to "Salário mensal (contrato)")
        val pagAtual = if (fxAtual != null) Regra.SALARIO else r.pagamento
        val forma = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL; setSimpleItems(formas.map { it.second }.toTypedArray())
            setText(formas.first { it.first == pagAtual }.second, false)
        }
        v.addView(campo("Como este local paga", forma, true))

        // campos de cada forma de pagamento
        val vf = numero("", if (r.porFicha > 0) fmtNum(r.porFicha) else "", true)
        val vu = numero("", if (r.porUti > 0) fmtNum(r.porUti) else "", true)
        val prod = ui.lin(Gravity.TOP).apply {
            addView(campo("R$ por ficha", vf).apply { layoutParams = ui.lp(0, WRAP, top = 12, peso = 1f) })
            addView(campo("R$ por UTI", vu).apply { layoutParams = ui.lp(0, WRAP, top = 12, start = 10, peso = 1f) })
        }
        val vp = numero("", if (r.valorPlantao > 0) fmtNum(r.valorPlantao) else "", true)
        val campoPlantao = campo("Valor líquido por plantão (R$)", vp)
        val vh = numero("", if (r.valorHora > 0) fmtNum(r.valorHora) else "", true)
        val campoHora = campo("Valor líquido por hora (R$)", vh)
        val hoje = java.time.YearMonth.now()
        val vs = numero("", fxAtual?.let { fmtNum(it.valor) } ?: "", true)
        val desde = TextInputEditText(this).apply {
            setText(fxAtual?.let { mesTexto(it.desde) } ?: "%02d/%04d".format(hoje.monthValue, hoje.year)); inputType = InputType.TYPE_CLASS_DATETIME
        }
        val sal = ui.lin(Gravity.TOP).apply {
            addView(campo("Salário líquido por mês (R$)", vs).apply { layoutParams = ui.lp(0, WRAP, top = 12, peso = 1.3f) })
            addView(campo("Desde (mês/ano)", desde).apply { layoutParams = ui.lp(0, WRAP, top = 12, start = 10, peso = 1f) })
        }
        val dica = ui.tv("", 13f, pal.mute)
        v.addView(prod); v.addView(campoPlantao); v.addView(campoHora); v.addView(sal); v.addView(dica, ui.lp(top = 6))
        fun ajusta() {
            val f = formas.firstOrNull { it.second == forma.text.toString() }?.first ?: Regra.PRODUCAO
            prod.visibility = if (f == Regra.PRODUCAO) View.VISIBLE else View.GONE
            campoPlantao.visibility = if (f == Regra.PLANTAO) View.VISIBLE else View.GONE
            campoHora.visibility = if (f == Regra.HORA) View.VISIBLE else View.GONE
            sal.visibility = if (f == Regra.SALARIO) View.VISIBLE else View.GONE
            dica.text = when (f) {
                Regra.PRODUCAO -> "No fim de cada plantão o app pergunta as fichas e calcula o ganho."
                Regra.PLANTAO -> "Cada plantão aqui já nasce com esse valor; não pede fichas."
                Regra.HORA -> "O valor do plantão é calculado pelas horas; não pede fichas."
                else -> "O salário entra todo mês no Financeiro; os plantões aqui não pedem fichas."
            }
        }
        forma.setOnItemClickListener { _, _, _, _ -> ajusta() }
        ajusta()

        val desloc = numero("", if (r.deslocMin > 0) r.deslocMin.toString() else "")
        v.addView(campo("Tempo de casa até lá (minutos)", desloc))
        val levar = TextInputEditText(this).apply { setText(r.levar); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        v.addView(campo("Não esquecer (ex.: carimbo, jaleco, crachá)", levar))

        val botoes = ui.lin(); botoes.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        if (!novoLocal) botoes.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Remover"; isAllCaps = false; setTextColor(pal.choque)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("Remover ${local}?")
                    .setMessage("Os valores e o salário deste local saem da configuração. Os plantões já cadastrados continuam na agenda.")
                    .setPositiveButton("Remover") { _, _ ->
                        Regras.remover(this@MainActivity, local)
                        fxAtual?.let { Fixos.remover(this@MainActivity, it.id) }
                        sheet.dismiss(); render(false)
                    }.setNegativeButton("Cancelar", null).show()
            }
        }, ui.lp(0, WRAP, peso = 1f))
        val cancelar = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "Cancelar"; isAllCaps = false }
        val salvar = MaterialButton(this).apply { text = "Salvar"; isAllCaps = false; minWidth = ui.dp(120) }
        botoes.addView(cancelar); botoes.addView(salvar, ui.lp(WRAP, WRAP, start = 8))
        v.addView(botoes, ui.lp(top = 20, bottom = 16))
        cancelar.setOnClickListener { sheet.dismiss() }
        salvar.setOnClickListener {
            val n = nome.text.toString().trim()
            if (n.isEmpty()) { (nome.parent.parent as? TextInputLayout)?.error = "Dê um nome ao local"; return@setOnClickListener }
            if (novoLocal && Regras.locais(this).any { it.equals(n, true) }) { (nome.parent.parent as? TextInputLayout)?.error = "Já existe um local com esse nome"; return@setOnClickListener }
            val f = formas.firstOrNull { it.second == forma.text.toString() }?.first ?: Regra.PRODUCAO
            var mesDesde: String? = null
            if (f == Regra.SALARIO) {
                if (txt2num(vs.text) <= 0) { (vs.parent.parent as? TextInputLayout)?.error = "Informe o salário"; return@setOnClickListener }
                mesDesde = lerMes(desde.text.toString())
                if (mesDesde == null) { (desde.parent.parent as? TextInputLayout)?.error = "Use mês/ano, ex.: 12/2026"; return@setOnClickListener }
            }
            if (!novoLocal && n != local.trim()) Regras.renomear(this, local, n)
            val nova = Regra(txt2num(vf.text), txt2num(vu.text), txt2num(desloc.text).toInt(), levar.text.toString().trim(),
                f, txt2num(vp.text), txt2num(vh.text))
            Regras.salvar(this, n, nova)
            // salário mensal: cria/atualiza o contrato vinculado; se deixou de ser salário, encerra no mês passado
            val fx = Fixos.todos(this).firstOrNull { it.local.trim() == n }
            if (f == Regra.SALARIO) {
                Fixos.salvar(this, (fx ?: RendaFixa(nome = n, valor = 0.0, desde = mesDesde!!, local = n))
                    .copy(valor = txt2num(vs.text), desde = mesDesde!!, ate = ""))
            } else if (fx != null && fx.ate.isEmpty()) {
                val ultimo = hoje.minusMonths(1)
                val k = "%04d-%02d".format(ultimo.year, ultimo.monthValue)
                if (k < fx.desde) Fixos.remover(this, fx.id) else Fixos.salvar(this, fx.copy(ate = k))
            }
            sheet.dismiss()
            ProximoWidget.atualizar(this); render(false)
            // valor fixo ou por hora: oferece aplicar aos plantões já cadastrados
            val semValor = itens.count { it.local.trim() == n && !it.lancado && it.ativo && Math.abs(it.valor - nova.valorPara(it.durMin)) > 0.5 }
            if ((f == Regra.PLANTAO || f == Regra.HORA) && nova.configurada && semValor > 0) {
                MaterialAlertDialogBuilder(this).setTitle("Aplicar aos plantões de $n?")
                    .setMessage("${pl(semValor, "plantão", "plantões")} deste local ainda ${if (semValor == 1) "está" else "estão"} sem esse valor. Aplicar ${nova.resumo().lowercase(PT)} a eles e às escalas?")
                    .setPositiveButton("Aplicar") { _, _ -> Regras.aplicarValor(this, n, nova); render(false) }
                    .setNegativeButton("Agora não", null).show()
            } else Snackbar.make(box, "Local \"$n\" salvo.", Snackbar.LENGTH_SHORT).apply { if (fab.isShown) setAnchorView(fab) }.show()
        }

        val sv = NestedScrollView(this); sv.addView(v)
        sheet.setContentView(sv)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    override fun editarMeta() {
        val v = ui.col(20, 4)
        val m = numero("R$ por hora", fmtNum(Regras.meta(this)), true)
        v.addView(ui.tv("Quanto você quer ganhar, em média, por hora de plantão.", 14f, pal.mute), ui.lp(top = 4))
        v.addView(campo("Meta (R$ por hora)", m))
        MaterialAlertDialogBuilder(this).setTitle("Meta por hora").setView(v)
            .setPositiveButton("Salvar") { _, _ -> val x = txt2num(m.text); if (x > 0) Regras.salvarMeta(this, x); render(false) }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Lixeira: apaga sempre UM plantão só. Para apagar vários, use "Excluir vários plantões" em Ajustes. */
    override fun excluir(p: Plantao) {
        fecharDia()
        val l = p.inicio.ldt()
        val serie = p.serieId.takeIf { it.isNotEmpty() }?.let { Series.de(this, it) }
        val b = MaterialAlertDialogBuilder(this).setTitle("Excluir este plantão?")
            .setMessage("${p.local}\n${diaLongo(l.toLocalDate())}, ${hm(p.inicio)} às ${hm(p.fim)}\n\n" +
                if (serie == null) "Só este plantão será apagado. Os outros continuam na agenda."
                else "Ele faz parte da escala fixa (${serie.descricao()}). \"Só este\" apaga apenas este dia; a escala continua.")
            .setPositiveButton(if (serie == null) "Excluir" else "Só este") { _, _ -> apagar(listOf(p)) }
            .setNegativeButton("Cancelar", null)
        if (serie != null) b.setNeutralButton("Encerrar escala aqui") { _, _ ->
            MaterialAlertDialogBuilder(this).setTitle("Encerrar a escala?")
                .setMessage("Os plantões desta escala a partir de ${diaCurto(Series.dataDe(p))} serão apagados (os que já têm fichas lançadas ficam).")
                .setPositiveButton("Encerrar") { _, _ ->
                    Series.encerrar(this, serie.id, Series.dataDe(p)); render(false)
                    Snackbar.make(box, "Escala encerrada a partir de ${diaCurto(Series.dataDe(p))}.", Snackbar.LENGTH_LONG).show()
                }.setNegativeButton("Cancelar", null).show()
        }
        b.show()
    }

    override fun editarSerie(s: Serie) {
        val v = ui.col(20, 4)
        v.addView(ui.tv("${s.descricao()}\nAs mudanças valem a partir do próximo plantão; os anteriores ficam como estão.", 14f, pal.mute), ui.lp(top = 4))
        val hora = TextInputEditText(this).apply { setText(s.hora); inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME }
        v.addView(campo("Início (HH:MM)", hora))
        val durs = (DURS + s.durMin / 60).distinct().sorted()
        val dur = MaterialAutoCompleteTextView(this).apply { inputType = InputType.TYPE_NULL; setSimpleItems(durs.map { "$it horas" }.toTypedArray()); setText("${s.durMin / 60} horas", false) }
        v.addView(campo("Duração", dur, true))
        val reps = listOf("Toda semana" to 7, "A cada 15 dias" to 14, "A cada 3 semanas" to 21, "A cada 4 semanas" to 28)
        val rep = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL; setSimpleItems(reps.map { it.first }.toTypedArray())
            setText(reps.firstOrNull { it.second == s.intervalo }?.first ?: reps[0].first, false)
        }
        v.addView(campo("Repetição", rep, true))
        val setor = TextInputEditText(this).apply { setText(s.setor); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        v.addView(campo("Setor ou turno", setor))
        val sv = NestedScrollView(this); sv.addView(v)
        val hoje = LocalDate.now()
        val prox = s.ocorrencias(hoje, hoje.plusDays(400)).firstOrNull()
        MaterialAlertDialogBuilder(this).setTitle(s.local).setView(sv)
            .setPositiveButton("Salvar") { _, _ ->
                val hh = Regex("^(\\d{1,2}):(\\d{2})$").find(hora.text.toString().trim())
                if (hh == null || prox == null) { Toast.makeText(this, "Horário inválido (use HH:MM).", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val nova = s.copy(id = java.util.UUID.randomUUID().toString(), hora = "%02d:%s".format(hh.groupValues[1].toInt(), hh.groupValues[2]),
                    durMin = (dur.text.toString().substringBefore(" ").toIntOrNull() ?: s.durMin / 60) * 60,
                    intervalo = reps.firstOrNull { it.first == rep.text.toString() }?.second ?: s.intervalo,
                    setor = setor.text.toString().trim(), inicio = prox.toString(), excecoes = emptySet())
                if (nova.copy(id = s.id, inicio = s.inicio, excecoes = s.excecoes) != s) {
                    Series.encerrar(this, s.id, prox); Series.criar(this, nova)
                    Snackbar.make(box, "Escala atualizada a partir de ${diaCurto(prox)}.", Snackbar.LENGTH_LONG).show()
                }
                render(false)
            }
            .setNeutralButton("Encerrar escala") { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle("Encerrar a escala?")
                    .setMessage("Os próximos plantões desta escala serão apagados (os que já têm fichas lançadas ficam).")
                    .setPositiveButton("Encerrar") { _, _ -> Series.encerrar(this, s.id, hoje); render(false) }
                    .setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Lista os plantões futuros com caixas de marcar: apaga exatamente os que o usuário marcar. */
    override fun excluirVarios() {
        val now = System.currentTimeMillis()
        val lista = itens.filter { it.fim > now }
        if (lista.isEmpty()) { Toast.makeText(this, "Não há plantões futuros.", Toast.LENGTH_LONG).show(); return }
        val nomes = lista.map { "${diaCurto(it.inicio)} ${hm(it.inicio)} · ${it.local}" + if (it.setor.isNotBlank()) " · ${it.setor}" else "" }.toTypedArray()
        val marcados = BooleanArray(lista.size)
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Marque os plantões para excluir")
            .setMultiChoiceItems(nomes, marcados) { _, i, x -> marcados[i] = x }
            .setPositiveButton("Excluir marcados", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val escolhidos = lista.filterIndexed { i, _ -> marcados[i] }
                if (escolhidos.isEmpty()) { Toast.makeText(this, "Nenhum plantão marcado.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                dlg.dismiss()
                MaterialAlertDialogBuilder(this).setTitle("Excluir ${escolhidos.size} plantão(ões)?")
                    .setMessage(escolhidos.joinToString("\n") { "• ${diaCurto(it.inicio)} ${hm(it.inicio)} · ${it.local}" } + "\n\nOs demais continuam na agenda.")
                    .setPositiveButton("Excluir") { _, _ -> apagar(escolhidos) }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
        dlg.show()
    }

    private fun apagar(ps: List<Plantao>) {
        for (p in ps) if (p.serieId.isNotEmpty()) Series.excecao(this, p.serieId, Series.dataDe(p).toString())
        Store.remover(this, ps.map { it.id }.toSet())
        render(false)
        val msg = if (ps.size == 1) "Plantão de ${diaCurto(ps[0].inicio)} excluído" else "${ps.size} plantões excluídos"
        Snackbar.make(box, msg, 8000)
            .setAction("Desfazer") { Store.adicionar(this, ps); render(false) }
            .apply { if (fab.isShown) setAnchorView(fab) }.show()
    }

    // ---------- painel do dia ----------
    private var folhaDia: BottomSheetDialog? = null

    private fun fecharDia() { folhaDia?.dismiss(); folhaDia = null }

    override fun abrirDia(dia: LocalDate) {
        fecharDia()
        val sheet = BottomSheetDialog(this)
        val sv = NestedScrollView(this)
        val c = telas.diaConteudo(dados(), dia)
        c.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        sv.addView(c)
        sheet.setContentView(sv)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.setOnDismissListener { if (folhaDia === sheet) folhaDia = null }
        folhaDia = sheet
        sheet.show()
    }

    // ---------- transformar em escala fixa ----------
    private val INTERVALOS = listOf("Toda semana" to 7, "A cada 15 dias" to 14, "A cada 3 semanas" to 21, "A cada 4 semanas" to 28)

    override fun tornarFixo(p: Plantao) {
        fecharDia()
        val l = p.inicio.ldt()
        // sugere o intervalo que a agenda já mostra para esse plantão, se houver
        val sugerido = Series.padroes(itens).firstOrNull {
            it.local.equals(p.local.trim(), true) && it.hora == "%02d:%02d".format(l.hour, l.minute) && it.durMin == p.durMin &&
                it.primeira.dayOfWeek == l.dayOfWeek
        }?.intervalo ?: 7
        var escolha = INTERVALOS.indexOfFirst { it.second == sugerido }.coerceAtLeast(0)
        val dia = l.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, PT)
        MaterialAlertDialogBuilder(this).setTitle("Repetir sempre")
            .setSingleChoiceItems(INTERVALOS.map { "${it.first} ($dia, ${hm(p.inicio)})" }.toTypedArray(), escolha) { _, i -> escolha = i }
            .setPositiveButton("Criar escala fixa") { _, _ ->
                val fixo = p.copy(tipo = Plantao.FIXO)
                Store.atualizar(this, fixo)
                val n = Series.criar(this, Serie(local = p.local.trim(), setor = p.setor, hora = "%02d:%02d".format(l.hour, l.minute), durMin = p.durMin,
                    inicio = l.toLocalDate().toString(), intervalo = INTERVALOS[escolha].second, valor = p.valor, obs = p.obs))
                render(false)
                Snackbar.make(box, "${p.local} virou escala fixa (${INTERVALOS[escolha].first.lowercase(PT)}). " +
                    (if (n > 0) "$n plantões criados à frente." else "Os plantões já cadastrados foram aproveitados."), Snackbar.LENGTH_LONG)
                    .apply { if (fab.isShown) setAnchorView(fab) }.show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Detecta os plantões que se repetem e transforma todos em escalas automáticas de uma vez. */
    override fun automatizar() {
        val ps = Series.padroes(itens)
        if (ps.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("Nada para automatizar")
                .setMessage("Não encontrei plantões repetidos fora de escala. Para criar uma, toque em \"↻ Repetir sempre\" num plantão.")
                .setPositiveButton("OK", null).show(); return
        }
        val marcados = BooleanArray(ps.size) { true }
        MaterialAlertDialogBuilder(this).setTitle("Automatizar sua escala")
            .setMultiChoiceItems(ps.map { "${it.local}${if (it.setor.isNotBlank()) " · " + it.setor else ""}\n${it.descricao()} · ${it.qtd} na agenda" }.toTypedArray(), marcados) { _, i, x -> marcados[i] = x }
            .setPositiveButton("Transformar em fixos") { _, _ ->
                val escolhidos = ps.filterIndexed { i, _ -> marcados[i] }
                var criados = 0
                for (pd in escolhidos) criados += Series.criar(this, pd.serie())
                render(false)
                MaterialAlertDialogBuilder(this).setTitle("Escala automática pronta")
                    .setMessage("${pl(escolhidos.size, "escala criada", "escalas criadas")}. Os plantões que já existiam foram aproveitados" +
                        (if (criados > 0) " e $criados novos foram criados à frente" else "") +
                        ".\n\nDaqui em diante o app renova sozinho. Para mudar, edite um plantão e escolha \"Este e os próximos\", ou use Ajustes › Escala fixa.")
                    .setPositiveButton("OK", null).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun receberLista(novos: List<Plantao>, fixos: List<RendaFixa> = emptyList()) {
        // salário fixo que veio da página: entra direto (sem duplicar pelo nome)
        val nomes = Fixos.todos(this).map { it.nome.trim().lowercase(PT) }.toSet()
        val fxNovos = fixos.filter { it.nome.trim().lowercase(PT) !in nomes }
        fxNovos.forEach { Fixos.salvar(this, it) }
        if (fxNovos.isNotEmpty()) {
            render(false)
            Snackbar.make(box, "Salário fixo adicionado: " + fxNovos.joinToString { "${it.nome} ${brl0(it.valor)}/mês" }, Snackbar.LENGTH_LONG).show()
        }
        if (novos.isEmpty()) { if (fxNovos.isEmpty()) Toast.makeText(this, "O arquivo não tem plantões.", Toast.LENGTH_LONG).show(); return }
        val chave = { p: Plantao -> p.local.lowercase(PT) + "|" + p.inicio }
        val tem = itens.map(chave).toSet()
        val faltam = novos.filter { chave(it) !in tem }
        MaterialAlertDialogBuilder(this).setTitle("Receber ${novos.size} plantões da página")
            .setMessage("O app tem ${itens.size} plantões agora.\n\n• Substituir: o app fica igual à página (o que você apagou na página some daqui também).\n• Só adicionar novos: junta ${faltam.size} plantões que ainda não estão no app.")
            .setPositiveButton("Substituir") { _, _ ->
                Store.substituir(this, novos); render(false)
                Toast.makeText(this, "Agenda atualizada: ${novos.size} plantões. Alarmes reagendados.", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Só adicionar novos") { _, _ ->
                Store.adicionar(this, faltam); render(false)
                Toast.makeText(this, "${faltam.size} plantões adicionados.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }
}
