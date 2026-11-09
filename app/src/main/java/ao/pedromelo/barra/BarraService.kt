package ao.pedromelo.barra

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serviço que desenha a barra de tarefas por cima das apps.
 * Em modo "auto" só aparece quando há um teclado ou rato externo (Bluetooth/USB) ligado.
 */
class BarraService : Service(), InputManager.InputDeviceListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var im: InputManager
    private val handler = Handler(Looper.getMainLooper())

    private var barra: LinearLayout? = null
    private var zonaRecentes: LinearLayout? = null
    private var relogio: TextView? = null
    private var menu: View? = null

    private var recentesMostrados: List<String> = emptyList()
    private var appAtual: String? = null
    private val icones = HashMap<String, Drawable>()
    private var launchers: Set<String> = emptySet()

    private val tique = object : Runnable {
        override fun run() {
            atualizar()
            handler.postDelayed(this, 2000)
        }
    }

    companion object {
        @Volatile var estado: String = "parado"
        @Volatile var dispositivos: String = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registarErro(onde: String, e: Throwable) {
        val txt = "$onde: ${e.javaClass.simpleName}: ${e.message}".take(400)
        try { Prefs.p(this).edit().putString(Prefs.ERRO, txt).commit() } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            registarErro("falha", e)
            anterior?.uncaughtException(t, e)
        }
        estado = "a arrancar"
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            im = getSystemService(INPUT_SERVICE) as InputManager
            arrancarPrimeiroPlano()
            im.registerInputDeviceListener(this, handler)
            Prefs.p(this).registerOnSharedPreferenceChangeListener(this)
            launchers = pacotesLauncher()
            avaliar()
            handler.post(tique)
        } catch (e: Exception) {
            registarErro("arranque", e)
            estado = "erro no arranque"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        avaliar()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { im.unregisterInputDeviceListener(this) } catch (_: Exception) {}
        Prefs.p(this).unregisterOnSharedPreferenceChangeListener(this)
        esconder()
        estado = "parado"
        super.onDestroy()
    }

    // ---------- primeiro plano ----------

    private fun arrancarPrimeiroPlano() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val canal = "barra"
        nm.createNotificationChannel(
            NotificationChannel(canal, "Barra de tarefas", NotificationManager.IMPORTANCE_MIN)
        )
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, canal)
            .setSmallIcon(R.drawable.ic_inicio)
            .setContentTitle("Barra ativa")
            .setContentText("Toque para personalizar")
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- teclado / rato ----------

    override fun onInputDeviceAdded(deviceId: Int) = avaliar()
    override fun onInputDeviceRemoved(deviceId: Int) = avaliar()
    override fun onInputDeviceChanged(deviceId: Int) = avaliar()

    private fun temTecladoOuRato(): Boolean {
        var encontrou = false
        val nomes = StringBuilder()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) continue
            // a caneta (ex.: Redmi Smart Pen) não conta como rato
            if ((d.sources and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) continue
            if (d.name.contains("pen", ignoreCase = true) || d.name.contains("stylus", ignoreCase = true)) continue
            val externo = Build.VERSION.SDK_INT < 29 || d.isExternal
            val s = d.sources
            val rato = (s and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (s and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
            val teclado = d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            if (externo && (rato || teclado)) {
                encontrou = true
                nomes.append(d.name).append(if (rato) " (rato)" else " (teclado)").append("; ")
            }
        }
        dispositivos = if (encontrou) nomes.toString() else "nenhum teclado/rato externo"
        return encontrou
    }

    private fun avaliar() {
        try {
            val tem = temTecladoOuRato()
            val mostrar = when (Prefs.modo(this)) {
                "sempre" -> true
                "nunca" -> false
                else -> tem
            }
            if (!Settings.canDrawOverlays(this)) {
                estado = "sem permissão para sobrepor"
                esconder()
            } else if (mostrar) {
                mostrar()
            } else {
                esconder()
                estado = "escondida (" + Prefs.modo(this) + ")"
            }
        } catch (e: Exception) {
            registarErro("avaliar", e)
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == Prefs.ERRO || key == Prefs.TESTE || (key ?: "").startsWith("janela_")) return
        // qualquer mudança de definição: redesenhar
        esconder()
        avaliar()
    }

    // ---------- desenho da barra ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun corFundo(): Int {
        val base = try { Color.parseColor(Prefs.cor(this)) } catch (_: Exception) { Color.BLACK }
        val a = (Prefs.opacidade(this).coerceIn(0, 100) * 255 / 100)
        return Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun corTexto(): Int {
        val base = try { Color.parseColor(Prefs.cor(this)) } catch (_: Exception) { Color.BLACK }
        val luz = (Color.red(base) * 299 + Color.green(base) * 587 + Color.blue(base) * 114) / 1000
        return if (luz > 150) Color.BLACK else Color.WHITE
    }

    private fun emCima() = Prefs.posicao(this) == "cima"

    private fun alturaEcra(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height()
        else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.heightPixels
        }

    private fun larguraEcra(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels
        }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // rodou o ecrã: voltar a medir e redesenhar
        esconder()
        avaliar()
    }

    private fun parametrosBarra(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(Prefs.altura(this)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        // posição absoluta, medida a partir do topo do ecrã real
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = if (emCima()) 0 else (alturaEcra() - dp(Prefs.altura(this))).coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // ignora as barras do sistema: fica encostada à borda do ecrã
            lp.fitInsetsTypes = 0
        }
        lp.title = "Barra"
        return lp
    }

    private fun mostrar() {
        if (barra != null) return
        val tam = dp(Prefs.tamIcone(this))
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(corFundo())
            setPadding(dp(6), 0, dp(6), 0)
        }

        // botão Iniciar
        val inicio = ImageView(this).apply {
            setImageResource(R.drawable.ic_inicio)
            setColorFilter(corTexto())
            layoutParams = LinearLayout.LayoutParams(tam, tam).apply { marginEnd = dp(8) }
            setOnClickListener { alternarMenu() }
            setOnLongClickListener { abrirDefinicoes(); true }
        }
        b.addView(inicio)
        b.addView(separador())

        // fixas
        for (pkg in Prefs.fixas(this)) {
            if (packageManager.getLaunchIntentForPackage(pkg) == null) continue
            b.addView(iconeApp(pkg, tam))
        }

        // recentes
        val zr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (Prefs.fixas(this).isNotEmpty()) b.addView(separador())
        b.addView(zr)
        zonaRecentes = zr

        // espaço
        b.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // relógio
        if (Prefs.relogio(this)) {
            val r = TextView(this).apply {
                setTextColor(corTexto())
                textSize = (Prefs.altura(this@BarraService) / 3.6f).coerceIn(8f, 18f)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 0.9f)
            }
            b.addView(r)
            relogio = r
        }

        try {
            val lpB = parametrosBarra()
            wm.addView(b, lpB)
            barra = b
            estado = "adicionada (y=${lpB.y}, ecrã=${alturaEcra()})"
            b.postDelayed({
                val pos = IntArray(2)
                b.getLocationOnScreen(pos)
                estado = "visível? pos=${pos[0]},${pos[1]} tam=${b.width}x${b.height} " +
                    "mostrada=${b.isShown} janela=${b.windowVisibility} ecrã=${alturaEcra()}"
            }, 800)
            recentesMostrados = emptyList()
            atualizar()
        } catch (e: Exception) {
            barra = null
            estado = "erro ao desenhar"
            registarErro("desenhar", e)
        }
    }

    private fun esconder() {
        fecharMenu()
        fecharPopup()
        barra?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        barra = null
        zonaRecentes = null
        relogio = null
    }

    private fun separador(): View = View(this).apply {
        setBackgroundColor(Color.argb(90, Color.red(corTexto()), Color.green(corTexto()), Color.blue(corTexto())))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp((Prefs.altura(this@BarraService) * 0.55f).toInt()))
            .apply { marginStart = dp(4); marginEnd = dp(4) }
    }

    private fun icone(pkg: String): Drawable? = icones[pkg] ?: try {
        packageManager.getApplicationIcon(pkg).also { icones[pkg] = it }
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun iconeApp(pkg: String, tam: Int): View {
        val moldura = FrameLayout(this)
        val pad = dp(3)
        moldura.setPadding(pad, pad, pad, pad)
        if (pkg == appAtual) {
            moldura.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.argb(70, 255, 255, 255))
            }
        }
        val iv = ImageView(this).apply {
            setImageDrawable(icone(pkg))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        moldura.addView(iv, FrameLayout.LayoutParams(tam, tam))
        moldura.setOnClickListener { cliqueApp(pkg) }
        // toque longo (dedo) ou botão direito do rato: menu da janela
        moldura.setOnLongClickListener { menuApp(pkg, moldura); true }
        moldura.setOnContextClickListener { menuApp(pkg, moldura); true }
        moldura.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(2) }
        return moldura
    }

    // ---------- janelas (via Shizuku) ----------

    /** Clique numa app da barra: se já está à frente, minimiza; senão abre como ficou da última vez. */
    private fun cliqueApp(pkg: String) {
        fecharMenu()
        if (pkg == appAtual && Shell.disponivel()) {
            minimizar()
            return
        }
        val modo = Prefs.modoJanela(this, pkg)
        if (modo.isNotEmpty() && Shell.disponivel()) abrirComModo(pkg, modo) else abrirApp(pkg)
    }

    private fun componente(pkg: String): String? =
        packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()

    /** modo: "janela" (janela flutuante) ou "max" (ocupa o ecrã todo menos a barra) */
    private fun abrirComModo(pkg: String, modo: String) {
        Prefs.gravarModoJanela(this, pkg, modo)
        val comp = componente(pkg) ?: return abrirApp(pkg)
        val w = larguraEcra()
        val h = alturaEcra() - dp(Prefs.altura(this))
        Thread {
            try {
                val log = StringBuilder("janela $modo $pkg\n")
                log.append(Shell.correr("am start --windowingMode 5 -n $comp").take(200)).append('\n')
                var id: Int? = null
                for (i in 1..6) {
                    Thread.sleep(400)
                    id = Shell.idTarefa(pkg)
                    if (id != null) break
                }
                log.append("tarefa=").append(id).append('\n')
                if (id != null) {
                    val cmd = if (modo == "max") {
                        "am task resize $id 0 0 $w $h"
                    } else {
                        val b = Prefs.limitesJanela(this, pkg)
                        val (l, t, r, bo) = b ?: listOf(w / 6, h / 8, w * 5 / 6, h * 7 / 8)
                        "am task resize $id $l $t $r $bo"
                    }
                    log.append(cmd).append(" -> ").append(Shell.correr(cmd).take(300)).append('\n')
                }
                Prefs.p(this).edit().putString(Prefs.TESTE, log.toString()).apply()
            } catch (e: Exception) {
                registarErro("janela", e)
            }
        }.start()
    }

    private fun minimizar() {
        Thread { try { Shell.correr("input keyevent 3") } catch (_: Exception) {} }.start()
    }

    private fun fecharApp(pkg: String) {
        Thread { try { Shell.correr("am force-stop $pkg") } catch (_: Exception) {} }.start()
    }

    private var popup: View? = null

    private fun fecharPopup() {
        popup?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        popup = null
    }

    private fun menuApp(pkg: String, ancora: View) {
        fecharMenu()
        fecharPopup()
        val nome = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        val shizuku = Shell.disponivel()
        val painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(245, 30, 34, 40))
            }
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        fun item(t: String, acao: () -> Unit) {
            painel.addView(TextView(this).apply {
                text = t
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(14), dp(8), dp(24), dp(8))
                setOnClickListener { fecharPopup(); acao() }
            })
        }
        painel.addView(TextView(this).apply {
            text = nome
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(14), dp(2), dp(24), dp(6))
        })
        if (shizuku) {
            item("❐  Abrir em janela") { abrirComModo(pkg, "janela") }
            item("□  Maximizar") { abrirComModo(pkg, "max") }
            item("—  Minimizar") { minimizar() }
            item("✕  Fechar") { fecharApp(pkg) }
        } else {
            item("Abrir") { abrirApp(pkg) }
            item("(Ligue o Shizuku nas definições da Barra para janelas)") { abrirDefinicoes() }
        }
        val fixa = pkg in Prefs.fixas(this)
        item(if (fixa) "📌  Retirar da barra" else "📌  Fixar na barra") {
            Prefs.alternarFixa(this, pkg)
        }

        val pos = IntArray(2)
        ancora.getLocationOnScreen(pos)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        painel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = pos[0].coerceAtMost(larguraEcra() - painel.measuredWidth).coerceAtLeast(0)
        lp.y = if (emCima()) dp(Prefs.altura(this)) + dp(4)
            else (alturaEcra() - dp(Prefs.altura(this)) - dp(4) - painel.measuredHeight).coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        painel.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { fecharPopup(); true } else false
        }
        try {
            wm.addView(painel, lp)
            popup = painel
        } catch (e: Exception) {
            registarErro("menu", e)
        }
    }

    private fun abrirApp(pkg: String) {
        fecharMenu()
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(i) } catch (_: Exception) {}
    }

    private fun abrirDefinicoes() {
        fecharMenu()
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) {}
    }

    // ---------- atualização periódica ----------

    private fun atualizar() {
        if (barra == null) return
        relogio?.text = SimpleDateFormat("HH:mm\ndd/MM/yy", Locale.getDefault()).format(Date())

        val zr = zonaRecentes ?: return
        val lista = if (Prefs.recentes(this)) recentes() else emptyList()
        val chave = lista + listOf("atual:" + (appAtual ?: ""))
        if (chave == recentesMostrados) return
        recentesMostrados = chave
        zr.removeAllViews()
        val tam = dp(Prefs.tamIcone(this))
        for (pkg in lista) zr.addView(iconeApp(pkg, tam))
    }

    private fun temAcessoUso(): Boolean {
        val ao = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val modo = if (Build.VERSION.SDK_INT >= 29) {
            ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return modo == AppOpsManager.MODE_ALLOWED
    }

    private fun pacotesLauncher(): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun recentes(): List<String> {
        if (!temAcessoUso()) return emptyList()
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val fim = System.currentTimeMillis()
        val ini = fim - 1000L * 60 * 60 * 12
        val ev = usm.queryEvents(ini, fim) ?: return emptyList()
        val e = UsageEvents.Event()
        val ultimo = HashMap<String, Long>()
        var atual: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                ultimo[e.packageName] = e.timeStamp
                atual = e.packageName
            }
        }
        appAtual = atual
        val fixas = Prefs.fixas(this).toSet()
        return ultimo.entries.sortedByDescending { it.value }.map { it.key }
            .filter {
                it != packageName && it !in launchers && it !in fixas &&
                    packageManager.getLaunchIntentForPackage(it) != null
            }
            .take(Prefs.maxRecentes(this).coerceAtLeast(0))
    }

    // ---------- menu Iniciar ----------

    private var fechadoEm = 0L

    private fun alternarMenu() {
        if (menu != null) { fecharMenu(); return }
        // o toque no botão Iniciar já fechou o menu (toque fora): não voltar a abrir
        if (System.currentTimeMillis() - fechadoEm < 400) return
        abrirMenu()
    }

    private fun fecharMenu() {
        if (menu != null) fechadoEm = System.currentTimeMillis()
        menu?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        menu = null
    }

    private fun abrirMenu() {
        val dm = resources.displayMetrics
        val colunas = Prefs.colunasMenu(this).coerceIn(3, 10)
        val cel = dp(Prefs.tamIcone(this).coerceAtLeast(28) + 44)

        val painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(240, 24, 28, 34))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val topo = TextView(this).apply {
            text = "⚙  Definições da Barra"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(6), dp(4), dp(6), dp(10))
            setOnClickListener { abrirDefinicoes() }
        }
        painel.addView(topo)

        val grelha = GridLayout(this).apply { columnCount = colunas }
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
        val fixas = Prefs.fixas(this).toSet()
        val tamIcone = dp(Prefs.tamIcone(this).coerceAtLeast(28))
        for ((pkg, nome) in apps) {
            val cx = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            cx.addView(ImageView(this).apply { setImageDrawable(icone(pkg)) },
                LinearLayout.LayoutParams(tamIcone, tamIcone))
            cx.addView(TextView(this).apply {
                text = (if (pkg in fixas) "• " else "") + nome
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(cel, LinearLayout.LayoutParams.WRAP_CONTENT))
            cx.setOnClickListener { abrirApp(pkg) }
            cx.setOnLongClickListener {
                val ficou = Prefs.alternarFixa(this, pkg)
                Toast.makeText(this, if (ficou) "$nome fixada na barra" else "$nome retirada da barra", Toast.LENGTH_SHORT).show()
                true
            }
            grelha.addView(cx)
        }
        val sv = ScrollView(this)
        sv.addView(grelha)
        painel.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))

        val largura = (cel + dp(8)) * colunas + dp(20)
        val altura = (dm.heightPixels * 0.6f).toInt()
        val lp = WindowManager.LayoutParams(
            largura.coerceAtMost(dm.widthPixels), altura,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(4)
        lp.y = if (emCima()) dp(Prefs.altura(this)) + dp(4)
            else (alturaEcra() - dp(Prefs.altura(this)) - dp(4) - altura).coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        painel.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { fecharMenu(); true } else false
        }
        try {
            wm.addView(painel, lp)
            menu = painel
        } catch (_: Exception) {}
    }
}
