package br.med.plantoes

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.ImageButton
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

class MainActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pal: Pal
    private var tab = 0
    private var calMes = false
    private var semana: LocalDate = inicioSemana(LocalDate.now())
    private var mes: YearMonth = YearMonth.now()
    private var diaSel: LocalDate = LocalDate.now()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (tab == 0) render()
            handler.postDelayed(this, 30_000)
        }
    }

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
        receber(novos)
    }

    private fun inicioSemana(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    private val itens: List<Plantao> get() = Store.all(this)
    private val descanso: Int get() = Store.descansoMin(this)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        pal = Pal(this)
        supportActionBar?.title = "Meus plantões"
        box = findViewById(R.id.box)
        scroll = findViewById(R.id.scroll)
        scroll.setBackgroundColor(pal.bg)
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { editar(null) }
        findViewById<BottomNavigationView>(R.id.nav).setOnItemSelectedListener {
            tab = when (it.itemId) { R.id.t_agenda -> 0; R.id.t_cal -> 1; else -> 2 }
            render(); scroll.scrollTo(0, 0); true
        }
        Alarms.canal(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Alarms.reagendar(this)
        val pr = Store.prefs(this)
        if (itens.isEmpty() && !pr.getBoolean("boasVindas", false)) {
            pr.edit().putBoolean("boasVindas", true).apply()
            MaterialAlertDialogBuilder(this).setTitle("Bem-vindo ao Plantões")
                .setMessage("Para trazer a escala que você já montou na página:\n\n1. Na página, toque em \"Enviar para o app\".\n2. Aqui, toque em \"Receber da página\" e escolha o arquivo baixado.\n\nOu cadastre plantões pelo botão +.")
                .setPositiveButton("Receber da página") { _, _ -> abrirArquivo.launch(arrayOf("*/*")) }
                .setNegativeButton("Agora não", null).show()
        }
        Atualizador.verificar(this, false)
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 30_000)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu); return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.m_vol)?.isChecked = Store.prefs(this).getBoolean("volMax", true)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_excluir -> excluirVarios()
            R.id.m_receber -> abrirArquivo.launch(arrayOf("*/*"))
            R.id.m_enviar -> criarArquivo.launch("plantoes-app.json")
            R.id.m_atualizar -> Atualizador.verificar(this, true)
            R.id.m_testar -> {
                Alarms.teste(this)
                Toast.makeText(this, "O alarme toca em 5 segundos. Bloqueie a tela para ver como fica.", Toast.LENGTH_LONG).show()
            }
            R.id.m_perm -> permissoes()
            R.id.m_vol -> {
                val v = !item.isChecked; item.isChecked = v
                Store.prefs(this).edit().putBoolean("volMax", v).apply()
            }
            R.id.m_ajuda -> ajuda()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- helpers de interface ----------
    private fun tv(t: CharSequence, sp: Float = 15f, cor: Int = pal.ink, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(cor)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun col(pad: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun lin() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    private fun card(bg: Int = pal.panel, stroke: Int = pal.line) = MaterialCardView(this).apply {
        radius = dpf(14f); cardElevation = 0f; strokeWidth = dp(1); strokeColor = stroke
        setCardBackgroundColor(bg); layoutParams = lp(bottom = 8)
    }

    private fun chip(t: String, cor: Int) = tv(t, 12f, cor, true).apply {
        background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(ColorUtils.setAlphaComponent(cor, 40)) }
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }

    private fun btn(t: String, outlined: Boolean = true, onClick: () -> Unit) = MaterialButton(
        this, null,
        if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle
    ).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

    private fun titulo(t: String) = box.addView(tv(t, 24f, bold = true), lp(top = 4, bottom = 6))

    private fun render() {
        box.removeAllViews()
        when (tab) { 0 -> agenda(); 1 -> calendario(); else -> livres() }
        box.addView(View(this), lp(h = dp(96)))
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

    private fun permissoes() {
        val itensP = mutableListOf<Pair<String, () -> Unit>>()
        val notif = NotificationManagerCompat.from(this).areNotificationsEnabled()
        itensP += "${if (notif) "✓" else "✗"} Notificações" to {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else abrir(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val ok = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            itensP += "${if (ok) "✓" else "✗"} Alarmes no horário exato" to {
                abrir(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            itensP += "${if (ok) "✓" else "✗"} Alarme em tela cheia (sobre a tela bloqueada)" to {
                abrir(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        val bat = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        itensP += "${if (bat) "✓" else "•"} Sem restrição de bateria (recomendado)" to {
            abrir(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        itensP += "Canal de som dos alarmes" to {
            abrir(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, Alarms.CANAL))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Toque para ajustar")
            .setItems(itensP.map { it.first }.toTypedArray()) { _, i -> itensP[i].second() }
            .setPositiveButton("Fechar", null).show()
    }

    private fun ajuda() {
        MaterialAlertDialogBuilder(this).setTitle("Como funcionam os alarmes").setMessage(
            "Cada plantão gera dois alarmes: 24 horas antes e 2 horas antes do início.\n\n" +
                "Eles usam o mesmo sistema do despertador do Android: tocam no horário exato mesmo com o celular bloqueado ou o app fechado, e voltam a ser agendados sozinhos se o celular reiniciar.\n\n" +
                "Quando o alarme dispara, a tela acende sobre o bloqueio, pisca em vermelho, a lanterna pisca, o som de alarme toca no volume máximo e o celular vibra até você tocar em CIENTE (ou Soneca 10 min). Depois de 5 minutos sem resposta o som para.\n\n" +
                "Em celulares Xiaomi, Motorola, Samsung e outros com economia de bateria agressiva, use \"Permissões dos alarmes\" e deixe o app sem restrição de bateria. Use \"Testar alarme\" para conferir."
        ).setPositiveButton("Entendi", null).show()
    }

    // ---------- aba Agenda ----------
    private fun agenda() {
        val now = System.currentTimeMillis()
        val faltam = faltas()
        if (faltam.isNotEmpty()) {
            val c = card(ColorUtils.setAlphaComponent(Cores.vermelho, 34), Cores.vermelho)
            val v = col(14)
            v.addView(tv("Os alarmes podem não tocar", 16f, pal.ink, true))
            v.addView(tv("Falta liberar: " + faltam.joinToString(", ") + ".", 14f))
            v.addView(btn("Corrigir agora") { permissoes() }, lp(WRAP, WRAP, 6))
            c.addView(v); box.addView(c)
        }
        val fut = itens.filter { it.fim > now }
        val prox = fut.firstOrNull()
        val claro = 0xFFD9F7EA.toInt(); val apagado = 0xFF8FBFAD.toInt()
        val mon = card(0xFF07110E.toInt(), 0xFF07110E.toInt())
        val m = col(18)
        if (prox == null) {
            m.addView(tv("Nenhum plantão agendado", 22f, claro, true))
            m.addView(tv("Toque em + para cadastrar, ou use o menu ⋮ › Receber plantões da página.", 14f, apagado), lp(top = 4))
        } else {
            val emCurso = prox.inicio <= now
            val alvo = if (emCurso) prox.fim - now else prox.inicio - now
            val cor = when {
                emCurso || alvo < 2 * HOUR -> 0xFFFF5A5F.toInt()
                alvo < DAY -> Cores.ambar
                else -> 0xFF3DFF9E.toInt()
            }
            m.addView(tv(if (emCurso) "Em plantão agora · termina em" else "Próximo plantão começa em", 14f, apagado))
            m.addView(tv(contagem(alvo), 46f, cor, true))
            m.addView(tv(prox.local + if (prox.setor.isNotBlank()) " · ${prox.setor}" else "", 20f, claro, true))
            m.addView(tv("${diaLongo(prox.inicio.ldt().toLocalDate())}\n${hm(prox.inicio)} às ${hm(prox.fim)} · ${horas(prox.durMin.toLong())}", 15f, 0xFFB5D9CB.toInt()), lp(top = 2))
            val r = lin(); r.setPadding(0, dp(14), 0, 0)
            for ((rot, t) in listOf("Aviso 1 dia antes" to prox.inicio - DAY, "Aviso 2 h antes" to prox.inicio - 2 * HOUR)) {
                val b = col(0)
                b.addView(tv(rot, 12f, apagado))
                b.addView(tv((if (t <= now) "✓ " else "⏰ ") + "${diaCurto(t)} ${hm(t)}", 15f, if (t <= now) apagado else claro, true))
                r.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            m.addView(r)
            val sem = fut.count { it.inicio - now < 7 * DAY }
            val h7 = fut.filter { it.inicio - now < 7 * DAY }.sumOf { it.durMin.toLong() }
            m.addView(tv("Próximos 7 dias: $sem plantões · ${horas(h7)}", 13f, apagado), lp(top = 12))
        }
        mon.addView(m); box.addView(mon)

        val conf = Store.conflitos(fut)
        if (conf.isNotEmpty()) {
            val c = card(ColorUtils.setAlphaComponent(Cores.vermelho, 26), ColorUtils.setAlphaComponent(Cores.vermelho, 120))
            c.addView(tv("Choque de horário: ${conf.size} plantões se sobrepõem. Estão marcados em vermelho abaixo.", 14f, pal.ink, true).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) })
            box.addView(c)
        }

        val lista = itens.filter { it.fim > now - 12 * HOUR }
        val hoje = LocalDate.now()
        var ultimo: LocalDate? = null
        for (p in lista) {
            val d = p.inicio.ldt().toLocalDate()
            if (d != ultimo) {
                ultimo = d
                val rel = when (d) { hoje -> " · hoje"; hoje.plusDays(1) -> " · amanhã"; else -> "" }
                box.addView(tv(diaLongo(d) + rel, 14f, pal.mute, true), lp(top = 14, bottom = 6))
            }
            box.addView(itemCard(p, p.id in conf, p.fim < now))
        }

        val ym = YearMonth.now()
        val doMes = itens.filter { YearMonth.from(it.inicio.ldt()) == ym }
        if (doMes.isNotEmpty()) {
            val c = card(); val v = col(16)
            v.addView(tv(mesAno(ym), 18f, pal.ink, true))
            v.addView(tv("${doMes.size} plantões · ${horas(doMes.sumOf { it.durMin.toLong() })}" +
                (doMes.sumOf { it.valor }.takeIf { it > 0 }?.let { " · ${brl(it)}" } ?: ""), 15f, pal.mute), lp(top = 4))
            c.addView(v); box.addView(c, lp(top = 16))
        }
    }

    private fun itemCard(p: Plantao, choque: Boolean, passado: Boolean): View {
        val c = if (choque) card(ColorUtils.setAlphaComponent(Cores.vermelho, 26), Cores.vermelho) else card(); if (passado) c.alpha = 0.5f
        val r = lin(); r.gravity = Gravity.TOP
        r.addView(View(this).apply { setBackgroundColor(if (choque) Cores.vermelho else corDo(p.local)) }, LinearLayout.LayoutParams(dp(6), MATCH))
        val t = col(12)
        t.addView(tv(hm(p.inicio), 22f, pal.ink, true))
        val vira = if (p.fim.ldt().toLocalDate() != p.inicio.ldt().toLocalDate()) " (+1)" else ""
        t.addView(tv("até ${hm(p.fim)}$vira", 12f, pal.mute))
        r.addView(t, LinearLayout.LayoutParams(WRAP, WRAP))
        val info = col(0); info.setPadding(0, dp(12), dp(12), dp(12))
        info.addView(tv(p.local, 17f, pal.ink, true))
        val tags = lin()
        tags.addView(chip(horas(p.durMin.toLong()), corDo(p.local)))
        if (choque) tags.addView(chip("choque de horário", Cores.vermelho), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        info.addView(tags, lp(top = 4))
        val sub = listOfNotNull(p.setor.takeIf { it.isNotBlank() }, if (p.valor > 0) brl(p.valor) else null).joinToString(" · ")
        if (sub.isNotEmpty()) info.addView(tv(sub, 14f, pal.mute), lp(top = 4))
        if (p.obs.isNotBlank()) info.addView(tv(p.obs, 14f, pal.mute), lp(top = 2))
        r.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete); setColorFilter(pal.mute)
            background = null; contentDescription = "Excluir este plantão"
            setOnClickListener { excluir(p) }
        }, LinearLayout.LayoutParams(dp(48), dp(56)))
        c.addView(r)
        c.setOnClickListener { editar(p) }
        c.setOnLongClickListener { excluir(p); true }
        return c
    }

    // ---------- aba Calendário ----------
    private fun calendario() {
        val tg = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val bS = btn("Semana") {}.apply { id = View.generateViewId() }
        val bM = btn("Mês") {}.apply { id = View.generateViewId() }
        tg.addView(bS, LinearLayout.LayoutParams(0, WRAP, 1f))
        tg.addView(bM, LinearLayout.LayoutParams(0, WRAP, 1f))
        tg.check(if (calMes) bM.id else bS.id)
        tg.addOnButtonCheckedListener { _, id, checked ->
            if (checked && (id == bM.id) != calMes) { calMes = id == bM.id; handler.post { render() } }
        }
        box.addView(tg, lp(bottom = 8))

        val nav = lin()
        val tituloTxt = if (calMes) mesAno(mes)
        else "${diaCurto(semana)} – ${diaCurto(semana.plusDays(6))}"
        nav.addView(btn("◀") { if (calMes) mes = mes.minusMonths(1) else semana = semana.minusWeeks(1); render() })
        nav.addView(tv(tituloTxt, 16f, pal.ink, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(btn("Hoje") { semana = inicioSemana(LocalDate.now()); mes = YearMonth.now(); diaSel = LocalDate.now(); render() })
        nav.addView(btn("▶") { if (calMes) mes = mes.plusMonths(1) else semana = semana.plusWeeks(1); render() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        box.addView(nav, lp(bottom = 8))

        val lista = itens.toList()
        if (!calMes) {
            val wv = WeekView(this, pal).apply {
                semanaIni = semana; dados = lista; descansoMin = descanso; choques = Store.conflitos(lista)
                onPlantao = { editar(it) }
                onDia = { d -> diaSel = d; mes = YearMonth.from(d); calMes = true; render() }
            }
            box.addView(wv, lp())
            val ini = semana.ms(); val fim = semana.plusDays(7).ms()
            val trab = lista.filter { it.inicio in ini until fim }
            val livre = Livre.janelas(lista, ini, fim, descanso).sumOf { it.fim - it.ini } / MIN
            val c = card(); val v = col(14)
            v.addView(tv("Nesta semana", 16f, pal.ink, true))
            v.addView(tv("${trab.size} plantões · ${horas(trab.sumOf { it.durMin.toLong() })} de trabalho · ${horas(livre)} livres", 14f, pal.mute), lp(top = 2))
            v.addView(tv("Cada hospital tem sua cor · vermelho = choque de horário · verde claro = livre · cinza = descanso reservado · toque num plantão para editar · toque no dia para ver detalhes", 12f, pal.mute), lp(top = 6))
            legenda(v, trab)
            c.addView(v); box.addView(c, lp(top = 10))
        } else {
            val mv = MonthView(this, pal).apply {
                mesAtual = mes; dados = lista; descansoMin = descanso; choques = Store.conflitos(lista); selecionado = diaSel
                onDia = { diaSel = it; if (YearMonth.from(it) != mes) mes = YearMonth.from(it); render() }
            }
            box.addView(mv, lp())
            val ini = mes.atDay(1).ms(); val fim = mes.plusMonths(1).atDay(1).ms()
            val noMes = lista.filter { it.inicio in ini until fim }
            val diasLivres = (1..mes.lengthOfMonth()).count { Livre.livreNoDia(lista, mes.atDay(it), descanso) >= 1440 }
            box.addView(tv("${noMes.size} plantões · ${horas(noMes.sumOf { it.durMin.toLong() })} · $diasLivres dias totalmente livres", 14f, pal.mute), lp(top = 8))
            legenda(box, lista)
            detalheDia(diaSel)
        }
    }

    private fun legenda(v: LinearLayout, ps: List<Plantao>) {
        val locais = ps.map { it.local }.distinct()
        if (locais.isEmpty()) return
        val g = ChipGroup(this)
        for (l in locais) g.addView(chip(l, corDo(l)))
        g.addView(chip("Choque de horário", Cores.vermelho))
        g.addView(chip("Livre", Cores.verde))
        v.addView(g, lp(top = 8))
    }

    private fun detalheDia(d: LocalDate) {
        box.addView(tv(diaLongo(d), 18f, pal.ink, true), lp(top = 14, bottom = 6))
        val ds = d.ms(); val de = d.plusDays(1).ms()
        val doDia = itens.filter { it.inicio < de && it.fim > ds }
        val js = Livre.janelas(itens, ds, de, descanso).filter { it.min >= 30 }
        if (doDia.isEmpty()) {
            box.addView(livreCard(Janela(ds, de, null, null), "Dia inteiro livre"))
            return
        }
        val conf = Store.conflitos(itens)
        val eventos = doDia.map { maxOf(it.inicio, ds) to (it as Any) } + js.map { it.ini to (it as Any) }
        for ((_, e) in eventos.sortedBy { it.first }) {
            if (e is Plantao) box.addView(itemCard(e, e.id in conf, e.fim < System.currentTimeMillis()))
            else if (e is Janela) box.addView(livreCard(e))
        }
    }

    // ---------- aba Horários livres ----------
    private fun livres() {
        titulo("Horários livres")
        box.addView(tv("Intervalos sem plantão nos próximos 30 dias. Use para encaixar um plantão extra, consultas ou compromissos pessoais.", 14f, pal.mute), lp(bottom = 12))
        val pr = Store.prefs(this)
        val minH = pr.getInt("minLivreH", 6)
        box.addView(tv("Mostrar intervalos de pelo menos", 14f, pal.ink, true))
        box.addView(escolhas(listOf(2, 4, 6, 12, 24), minH) { pr.edit().putInt("minLivreH", it).apply(); render() }, lp(bottom = 6))
        box.addView(tv("Reservar descanso depois de cada plantão", 14f, pal.ink, true))
        box.addView(escolhas(listOf(0, 4, 6, 8, 12), pr.getInt("descansoH", 0)) { pr.edit().putInt("descansoH", it).apply(); render() }, lp(bottom = 10))

        val now = System.currentTimeMillis()
        val ate = LocalDate.now().plusDays(31).ms()
        val js = Livre.janelas(itens, now, ate, descanso).filter { it.min >= minH * 60L }
        val total = js.sumOf { it.min }
        val c = card(0xFF07110E.toInt(), 0xFF07110E.toInt()); val v = col(16)
        v.addView(tv(horas(total), 36f, 0xFF3DFF9E.toInt(), true))
        v.addView(tv("livres em ${js.size} intervalos de ${minH} h ou mais", 14f, 0xFFB5D9CB.toInt()))
        js.maxByOrNull { it.min }?.let {
            v.addView(tv("Maior: ${diaCurto(it.ini)} ${hm(it.ini)} → ${diaCurto(it.fim)} ${hm(it.fim)} (${horas(it.min)})", 13f, 0xFF8FBFAD.toInt()), lp(top = 8))
        }
        c.addView(v); box.addView(c)

        if (js.isEmpty()) {
            box.addView(tv("Nenhum intervalo desse tamanho. Diminua o mínimo acima.", 15f, pal.mute), lp(top = 10))
            return
        }
        var ultimo: LocalDate? = null
        for (j in js) {
            val d = j.ini.ldt().toLocalDate()
            if (d != ultimo) { ultimo = d; box.addView(tv(diaLongo(d), 14f, pal.mute, true), lp(top = 12, bottom = 6)) }
            box.addView(livreCard(j))
        }
    }

    private fun escolhas(vals: List<Int>, sel: Int, cb: (Int) -> Unit): View {
        val g = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        for (v in vals) {
            val ch = layoutInflater.inflate(R.layout.chip_filtro, g, false) as Chip
            ch.text = if (v == 0) "nenhum" else "$v h"
            ch.isChecked = v == sel
            ch.setOnClickListener { if (v != sel) cb(v) }
            g.addView(ch)
        }
        return g
    }

    private fun livreCard(j: Janela, rotulo: String? = null): View {
        val c = card(ColorUtils.setAlphaComponent(Cores.verde, if (pal.n) 46 else 24), ColorUtils.setAlphaComponent(Cores.verde, 90))
        val v = col(14)
        val r = lin()
        val mesmoDia = j.ini.ldt().toLocalDate() == j.fim.ldt().toLocalDate() || j.fim == j.ini.ldt().toLocalDate().plusDays(1).ms()
        val fimTxt = if (j.fim == j.ini.ldt().toLocalDate().plusDays(1).ms()) "24:00" else hm(j.fim)
        val txt = rotulo ?: if (mesmoDia) "Livre ${hm(j.ini)} → $fimTxt" else "Livre ${diaCurto(j.ini)} ${hm(j.ini)} → ${diaCurto(j.fim)} ${hm(j.fim)}"
        r.addView(tv(txt, 16f, pal.ink, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(tv(horas(j.min), 18f, pal.verdeTxt, true))
        v.addView(r)
        val ctx = listOfNotNull(
            j.antes?.let { "depois de ${it.local} (termina ${hm(it.fim)})" },
            j.depois?.let { "antes de ${it.local} (começa ${hm(it.inicio)})" }
        ).joinToString(" · ")
        if (ctx.isNotEmpty()) v.addView(tv(ctx.cap(), 13f, pal.mute), lp(top = 2))
        var dias = 0; var d = j.ini.ldt().toLocalDate()
        while (d.plusDays(1).ms() <= j.fim) { if (d.ms() >= j.ini) dias++; d = d.plusDays(1) }
        if (dias > 0 && rotulo == null) v.addView(tv(if (dias == 1) "Inclui 1 dia inteiro livre" else "Inclui $dias dias inteiros livres", 13f, pal.verdeTxt, true), lp(top = 2))
        v.addView(btn("Encaixar plantão aqui") { editar(null, arred(j.ini)) }, lp(WRAP, WRAP, 6))
        c.addView(v)
        return c
    }

    private fun arred(t: Long): Long {
        val l = t.ldt()
        return (if (l.minute == 0 && l.second == 0) l else l.truncatedTo(ChronoUnit.HOURS).plusHours(1)).ms()
    }

    // ---------- cadastro / edição ----------
    private val DURS = listOf(4, 5, 6, 8, 10, 12, 14, 16, 18, 24)

    private fun rotulo(v: LinearLayout, t: String) = v.addView(tv(t, 13f, pal.mute, true), lp(top = 12))

    private fun editar(p: Plantao?, preset: Long? = null) {
        val v = col(20)
        var ini: LocalDateTime = (p?.inicio ?: preset ?: LocalDate.now().plusDays(1).atTime(19, 0).ms()).ldt().withSecond(0).withNano(0)
        val locais = itens.map { it.local }.distinct().sorted()

        rotulo(v, "Local / hospital")
        val local = AutoCompleteTextView(this).apply {
            setText(p?.local ?: "", false); threshold = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, locais))
        }
        v.addView(local)
        rotulo(v, "Setor / turno (opcional)")
        val setor = EditText(this).apply { setText(p?.setor ?: ""); hint = "Ex.: PS adulto, noturno"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        v.addView(setor)

        rotulo(v, "Data e hora de início")
        val bData = btn("") {}
        val bHora = btn("") {}
        fun atualiza() {
            bData.text = diaLongo(ini.toLocalDate())
            bHora.text = "%02d:%02d".format(ini.hour, ini.minute)
        }
        atualiza()
        bData.setOnClickListener {
            DatePickerDialog(this, { _, y, mo, d -> ini = LocalDateTime.of(LocalDate.of(y, mo + 1, d), ini.toLocalTime()); atualiza() },
                ini.year, ini.monthValue - 1, ini.dayOfMonth).show()
        }
        bHora.setOnClickListener {
            TimePickerDialog(this, { _, hh, mm -> ini = ini.withHour(hh).withMinute(mm); atualiza() }, ini.hour, ini.minute, true).show()
        }
        val rd = lin()
        rd.addView(bData, LinearLayout.LayoutParams(0, WRAP, 1f))
        rd.addView(bHora, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        v.addView(rd)

        rotulo(v, "Duração")
        val durAtual = p?.durMin?.div(60) ?: 12
        val durs = (DURS + durAtual).distinct().sorted()
        val sDur = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, durs.map { "$it horas" })
            setSelection(durs.indexOf(durAtual))
        }
        v.addView(sDur)

        rotulo(v, "Valor a receber (R$, opcional)")
        val valor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (p != null && p.valor > 0) setText(p.valor.toString())
        }
        v.addView(valor)
        rotulo(v, "Observações (opcional)")
        val obs = EditText(this).apply { setText(p?.obs ?: ""); hint = "Ex.: levar carimbo, passar plantão às 7h"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        v.addView(obs)

        val reps = listOf(
            "Não repetir" to (1 to 0), "Toda semana · 4 vezes" to (4 to 7), "Toda semana · 8 vezes" to (8 to 7),
            "Toda semana · 12 vezes" to (12 to 7), "A cada 15 dias · 4 vezes" to (4 to 14), "A cada 15 dias · 6 vezes" to (6 to 14)
        )
        val sRep = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, reps.map { it.first })
        }
        if (p == null) { rotulo(v, "Repetir"); v.addView(sRep) }

        val sv = ScrollView(this); sv.addView(v)
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (p == null) "Novo plantão" else "Editar plantão")
            .setView(sv).setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null)
        if (p != null) b.setNeutralButton("Excluir") { _, _ -> excluir(p) }
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nome = local.text.toString().trim()
                if (nome.isEmpty()) { local.error = "Informe o local"; return@setOnClickListener }
                val dur = durs[sDur.selectedItemPosition] * 60
                val vv = valor.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                val antes = Store.conflitos(itens).size
                if (p != null) {
                    Store.atualizar(this, p.copy(local = nome, setor = setor.text.toString().trim(), inicio = ini.ms(), durMin = dur, valor = vv, obs = obs.text.toString().trim()))
                } else {
                    val (n, passo) = reps[sRep.selectedItemPosition].second
                    Store.adicionar(this, (0 until n).map { i ->
                        Plantao(local = nome, setor = setor.text.toString().trim(), inicio = ini.plusDays((i * passo).toLong()).ms(),
                            durMin = dur, valor = vv, obs = obs.text.toString().trim())
                    })
                }
                dlg.dismiss(); render()
                if (Store.conflitos(itens).size > antes)
                    Toast.makeText(this, "Atenção: este plantão bate com outro já agendado.", Toast.LENGTH_LONG).show()
            }
        }
        dlg.show()
    }

    /** Lixeira: apaga sempre UM plantão só. Para apagar vários, use "Excluir vários plantões" no menu. */
    private fun excluir(p: Plantao) {
        val l = p.inicio.ldt()
        MaterialAlertDialogBuilder(this).setTitle("Excluir este plantão?")
            .setMessage("${p.local}\n${diaLongo(l.toLocalDate())}, ${hm(p.inicio)} às ${hm(p.fim)}\n\nSó este plantão será apagado. Os outros continuam na agenda.")
            .setPositiveButton("Excluir") { _, _ -> apagar(listOf(p)) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Lista os plantões futuros com caixas de marcar: apaga exatamente os que o usuário marcar. */
    private fun excluirVarios() {
        val now = System.currentTimeMillis()
        val lista = itens.filter { it.fim > now }
        if (lista.isEmpty()) { Toast.makeText(this, "Não há plantões futuros.", Toast.LENGTH_LONG).show(); return }
        val nomes = lista.map { "${diaCurto(it.inicio)} ${hm(it.inicio)} · ${it.local}" + if (it.setor.isNotBlank()) " · ${it.setor}" else "" }.toTypedArray()
        val marcados = BooleanArray(lista.size)
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Marque os plantões para excluir")
            .setMultiChoiceItems(nomes, marcados) { _, i, v -> marcados[i] = v }
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
        render()
        val msg = if (ps.size == 1) "Plantão de ${diaCurto(ps[0].inicio)} excluído" else "${ps.size} plantões excluídos"
        Snackbar.make(findViewById(R.id.box), msg, 8000)
            .setAction("Desfazer") { Store.adicionar(this, ps); render() }
            .setAnchorView(R.id.fab).show()
    }

    private fun receber(novos: List<Plantao>) {
        if (novos.isEmpty()) { Toast.makeText(this, "O arquivo não tem plantões.", Toast.LENGTH_LONG).show(); return }
        val chave = { p: Plantao -> p.local.lowercase(PT) + "|" + p.inicio }
        val tem = itens.map(chave).toSet()
        val faltam = novos.filter { chave(it) !in tem }
        MaterialAlertDialogBuilder(this).setTitle("Receber ${novos.size} plantões da página")
            .setMessage("O app tem ${itens.size} plantões agora.\n\n• Substituir: o app fica igual à página (o que você apagou na página some daqui também).\n• Só adicionar novos: junta ${faltam.size} plantões que ainda não estão no app.")
            .setPositiveButton("Substituir") { _, _ ->
                Store.substituir(this, novos); render()
                Toast.makeText(this, "Agenda atualizada: ${novos.size} plantões. Alarmes reagendados.", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Só adicionar novos") { _, _ ->
                Store.adicionar(this, faltam); render()
                Toast.makeText(this, "${faltam.size} plantões adicionados.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }
}
