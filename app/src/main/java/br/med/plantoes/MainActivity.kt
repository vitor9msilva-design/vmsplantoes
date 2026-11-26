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

    private val abrirArquivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val novos = try {
            Store.lerArquivo(contentResolver.openInputStream(uri)!!.bufferedReader().readText())
        } catch (_: Exception) {
            MaterialAlertDialogBuilder(this).setTitle("Arquivo não reconhecido")
                .setMessage("Escolha o arquivo gerado pelo botão \"Enviar para o app\" da página (plantoes-pagina.json).")
                .setPositiveButton("OK", null).show()
            return@registerForActivityResult
        }
        receberLista(novos)
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
        nav.selectedItemId = listOf(R.id.t_inicio, R.id.t_agenda, R.id.t_cal, R.id.t_livre, R.id.t_ajustes)[tab]
        nav.setOnItemSelectedListener {
            val nova = when (it.itemId) { R.id.t_inicio -> 0; R.id.t_agenda -> 1; R.id.t_cal -> 2; R.id.t_livre -> 3; else -> 4 }
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
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("aba", tab) }

    override fun onResume() {
        super.onResume()
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
    )

    /** Redesenha a aba atual. Com `animar`, o conteúdo entra deslizando de leve (troca de aba). */
    private fun render(animar: Boolean) {
        val d = dados()
        val v = when (tab) {
            0 -> telas.inicio(d)
            1 -> telas.agenda(d)
            2 -> telas.calendario(d)
            3 -> telas.livres(d)
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
        nav.selectedItemId = listOf(R.id.t_inicio, R.id.t_agenda, R.id.t_cal, R.id.t_livre, R.id.t_ajustes)[aba]
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
        val sheet = BottomSheetDialog(this)
        val v = ui.col(20, 8)
        var ini: LocalDateTime = (p?.inicio ?: preset ?: LocalDate.now().plusDays(1).atTime(19, 0).ms()).ldt().withSecond(0).withNano(0)

        val topo = ui.lin()
        topo.addView(ui.tv(if (p == null) "Novo plantão" else "Editar plantão", 22f, pal.ink, 700), ui.lp(0, WRAP, peso = 1f))
        if (p != null) topo.addView(ui.icone(R.drawable.ic_delete, "Excluir só este plantão", pal.choque) { sheet.dismiss(); excluir(p) },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        v.addView(topo, ui.lp(top = 8, bottom = 4))

        val locais = itens.map { it.local }.distinct().sorted().toTypedArray()
        val local = MaterialAutoCompleteTextView(this).apply {
            setText(p?.local ?: ""); threshold = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSimpleItems(locais)
        }
        v.addView(campo("Local / hospital", local))
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
        rdv.addView(campo("Valor (R$)", valor).apply { layoutParams = ui.lp(0, WRAP, top = 12, start = 10, peso = 1f) })
        v.addView(rdv)

        val obs = TextInputEditText(this).apply {
            setText(p?.obs ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        v.addView(campo("Observações (opcional)", obs))

        val reps = listOf(
            "Não repetir" to (1 to 0), "Toda semana · 4 vezes" to (4 to 7), "Toda semana · 8 vezes" to (8 to 7),
            "Toda semana · 12 vezes" to (12 to 7), "A cada 15 dias · 4 vezes" to (4 to 14), "A cada 15 dias · 6 vezes" to (6 to 14)
        )
        val rep = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL; setSimpleItems(reps.map { it.first }.toTypedArray()); setText(reps[0].first, false)
        }
        if (p == null) v.addView(campo("Repetir", rep, true))

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
            val vv = valor.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
            val antes = Store.conflitos(itens).size
            if (p != null) {
                Store.atualizar(this, p.copy(local = nome, setor = setor.text.toString().trim(), inicio = ini.ms(), durMin = horasDur * 60, valor = vv, obs = obs.text.toString().trim()))
            } else {
                val (n, passo) = reps.firstOrNull { it.first == rep.text.toString() }?.second ?: (1 to 0)
                Store.adicionar(this, (0 until n).map { i ->
                    Plantao(local = nome, setor = setor.text.toString().trim(), inicio = ini.plusDays((i * passo).toLong()).ms(),
                        durMin = horasDur * 60, valor = vv, obs = obs.text.toString().trim())
                })
            }
            sheet.dismiss(); render(false)
            if (Store.conflitos(itens).size > antes)
                Snackbar.make(box, "Atenção: este plantão bate com outro já agendado.", Snackbar.LENGTH_LONG).setAnchorView(fab).show()
        }

        val sv = NestedScrollView(this); sv.addView(v)
        sheet.setContentView(sv)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Lixeira: apaga sempre UM plantão só. Para apagar vários, use "Excluir vários plantões" em Ajustes. */
    override fun excluir(p: Plantao) {
        val l = p.inicio.ldt()
        MaterialAlertDialogBuilder(this).setTitle("Excluir este plantão?")
            .setMessage("${p.local}\n${diaLongo(l.toLocalDate())}, ${hm(p.inicio)} às ${hm(p.fim)}\n\nSó este plantão será apagado. Os outros continuam na agenda.")
            .setPositiveButton("Excluir") { _, _ -> apagar(listOf(p)) }
            .setNegativeButton("Cancelar", null)
            .show()
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
        Store.remover(this, ps.map { it.id }.toSet())
        render(false)
        val msg = if (ps.size == 1) "Plantão de ${diaCurto(ps[0].inicio)} excluído" else "${ps.size} plantões excluídos"
        Snackbar.make(box, msg, 8000)
            .setAction("Desfazer") { Store.adicionar(this, ps); render(false) }
            .apply { if (fab.isShown) setAnchorView(fab) }.show()
    }

    private fun receberLista(novos: List<Plantao>) {
        if (novos.isEmpty()) { Toast.makeText(this, "O arquivo não tem plantões.", Toast.LENGTH_LONG).show(); return }
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
