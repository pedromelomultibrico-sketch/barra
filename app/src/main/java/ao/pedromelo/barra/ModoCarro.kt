package ao.pedromelo.barra

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.provider.Settings

/**
 * Botão de apagar o ecrã (ao lado dos 3 botões de navegação) e "modo carro":
 *  - em carga, o ecrã não se apaga sozinho (só quando o Pedro o apaga);
 *  - tira a corrente (desliga a chave)  -> apaga e bloqueia;
 *  - volta a corrente (liga a chave)    -> acende e desbloqueia.
 * Tudo pelo Shizuku (o mesmo que as janelas usam).
 */
class ModoCarro(private val s: Service) {

    private val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val pm = s.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())

    private var botao: View? = null
    private var barraPorCima = false

    companion object {
        @Volatile var estado: String = "parado"
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), s.resources.displayMetrics).toInt()

    private val recetor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> aoLigarCorrente()
                Intent.ACTION_POWER_DISCONNECTED -> aoTirarCorrente()
            }
        }
    }

    fun arrancar() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            s.registerReceiver(recetor, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            s.registerReceiver(recetor, f)
        }
        aplicarManterAceso(false)
        atualizarBotao()
        estado = "ativo"
    }

    fun parar() {
        handler.removeCallbacksAndMessages(null)
        try { s.unregisterReceiver(recetor) } catch (_: Exception) {}
        tirarBotao()
    }

    /** Chamado quando muda uma definição do botão ou do modo carro. */
    fun definicaoMudou(chave: String?) {
        if (chave == Prefs.MANTER_ACESO) aplicarManterAceso(true)
        tirarBotao()
        atualizarBotao()
    }

    /** A barra de tarefas (aberta) fica por cima da barra de navegação: esconder o botão solto. */
    fun barraPorCima(sim: Boolean) {
        if (barraPorCima == sim) return
        barraPorCima = sim
        atualizarBotao()
    }

    fun reposicionar() {
        tirarBotao()
        atualizarBotao()
    }

    // ---------- ecrã ----------

    /** Apaga o ecrã (como o botão de ligar). */
    fun apagarEcra() {
        correr("apagar", "input keyevent 223")
    }

    private fun correr(oque: String, vararg cmds: String) {
        Thread {
            try {
                if (!Shell.disponivel()) {
                    estado = "$oque: falta o Shizuku"
                    return@Thread
                }
                for (c in cmds) {
                    if (c.startsWith("espera ")) {
                        Thread.sleep(c.removePrefix("espera ").toLongOrNull() ?: 300)
                    } else {
                        Shell.correr(c)
                    }
                }
                estado = "$oque às " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
            } catch (e: Throwable) {
                estado = "$oque: ${e.javaClass.simpleName}: ${e.message}".take(200)
            }
        }.start()
    }

    private fun aCarregar(): Boolean {
        val i = s.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private val apagarSemCorrente = Runnable {
        if (!aCarregar()) correr("bloqueado (sem corrente)", "input keyevent 223")
    }

    // o Android às vezes acende o ecrã ao tirar o cabo: voltar a confirmar
    private val confirmarApagado = Runnable {
        if (!aCarregar() && pm.isInteractive) correr("bloqueado (sem corrente)", "input keyevent 223")
    }

    private val acordarComCorrente = Runnable {
        if (aCarregar()) {
            correr(
                "acordado (corrente)",
                "input keyevent 224",
                "espera 500",
                "wm dismiss-keyguard"
            )
        }
    }

    private fun aoTirarCorrente() {
        handler.removeCallbacks(acordarComCorrente)
        if (!Prefs.bloquearSemCorrente(s)) return
        handler.postDelayed(apagarSemCorrente, 1200)
        handler.postDelayed(confirmarApagado, 3500)
    }

    private fun aoLigarCorrente() {
        handler.removeCallbacks(apagarSemCorrente)
        handler.removeCallbacks(confirmarApagado)
        if (!Prefs.acordarComCorrente(s)) return
        handler.postDelayed(acordarComCorrente, 600)
    }

    /** Em carga o ecrã fica sempre aceso (definição do Android "Manter ativo"). */
    private fun aplicarManterAceso(mudou: Boolean) {
        val ligado = Prefs.manterAceso(s)
        if (!ligado && !mudou) return // não mexer no que o Pedro tem, se nunca ligou a opção
        val valor = if (ligado) 7 else 0 // 7 = carregador, USB e sem fios
        Thread {
            try {
                if (!Shell.disponivel()) { estado = "manter aceso: falta o Shizuku"; return@Thread }
                val atual = try {
                    Settings.Global.getInt(s.contentResolver, "stay_on_while_plugged_in", 0)
                } catch (_: Exception) { -1 }
                if (atual != valor) Shell.correr("settings put global stay_on_while_plugged_in $valor")
            } catch (e: Throwable) {
                estado = "manter aceso: ${e.message}".take(200)
            }
        }.start()
    }

    // ---------- botão solto junto aos botões de navegação ----------

    private fun alturaNavegacao(): Int {
        val id = s.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val h = if (id > 0) s.resources.getDimensionPixelSize(id) else 0
        return if (h > 0) h else dp(48)
    }

    private fun tamanhoEcra(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun tirarBotao() {
        botao?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        botao = null
    }

    private fun atualizarBotao() {
        val quer = Prefs.botaoDesligar(s) && !barraPorCima && Settings.canDrawOverlays(s)
        if (!quer) { tirarBotao(); return }
        if (botao != null) return

        val tam = dp(Prefs.botaoTam(s).coerceIn(20, 64))
        val nav = alturaNavegacao()
        val (w, h) = tamanhoEcra()

        val iv = ImageView(s).apply {
            setImageResource(R.drawable.ic_desligar)
            setColorFilter(Color.WHITE)
            val p = tam / 5
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(150, 0, 0, 0))
            }
            contentDescription = "Apagar o ecrã"
            setOnClickListener { apagarEcra() }
            setOnLongClickListener {
                val i = Intent(s, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { s.startActivity(i) } catch (_: Exception) {}
                true
            }
        }

        val lp = WindowManager.LayoutParams(
            tam, tam,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = ((w - tam) * Prefs.botaoX(s).coerceIn(0, 100) / 100)
        // centrado na altura da barra de navegação, encostado ao fundo
        lp.y = (h - nav + (nav - tam) / 2).coerceIn(0, (h - tam).coerceAtLeast(0))
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        lp.title = "Barra-desligar"
        try {
            wm.addView(iv, lp)
            botao = iv
        } catch (e: Exception) {
            estado = "botão: ${e.message}".take(200)
        }
    }
}
