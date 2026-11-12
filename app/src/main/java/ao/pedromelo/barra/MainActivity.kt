package ao.pedromelo.barra

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Ecrã de personalização da barra. Tudo o que muda aqui aplica-se logo. */
class MainActivity : Activity() {

    private lateinit var raiz: LinearLayout
    private lateinit var estado: TextView

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        sv.addView(raiz)
        setContentView(sv)
        construir()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private val relogioDiag = android.os.Handler(android.os.Looper.getMainLooper())
    private val tiqueDiag = object : Runnable {
        override fun run() {
            atualizarEstado()
            relogioDiag.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        arrancarServico()
        relogioDiag.post(tiqueDiag)
    }

    override fun onPause() {
        relogioDiag.removeCallbacks(tiqueDiag)
        super.onPause()
    }

    private fun arrancarServico() {
        try {
            startForegroundService(Intent(this, BarraService::class.java))
        } catch (e: Exception) {
            Prefs.p(this).edit().putString(Prefs.ERRO, "arrancar: ${e.javaClass.simpleName}: ${e.message}".take(400)).apply()
        }
    }

    private fun reiniciarServico() {
        Prefs.p(this).edit().remove(Prefs.ERRO).apply()
        try { stopService(Intent(this, BarraService::class.java)) } catch (_: Exception) {}
        relogioDiag.postDelayed({ arrancarServico() }, 600)
    }

    // ---------- Shizuku e teste das janelas ----------

    private fun pedirShizuku() {
        try {
            if (!Shell.ligado()) {
                Prefs.p(this).edit().putString(Prefs.TESTE, "O Shizuku não está a correr.").apply()
                return
            }
            if (!Shell.disponivel()) rikka.shizuku.Shizuku.requestPermission(77)
        } catch (e: Throwable) {
            Prefs.p(this).edit().putString(Prefs.TESTE, "Shizuku: ${e.message}").apply()
        }
    }

    private fun testarJanelas() {
        Prefs.p(this).edit().putString(Prefs.TESTE, "A testar…").apply()
        val calc = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.firstOrNull { it.contains("calculator") } ?: "com.android.chrome"
        val comp = packageManager.getLaunchIntentForPackage(calc)?.component?.flattenToShortString()
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        Thread {
            val log = StringBuilder()
            try {
                log.append("Shizuku ligado=").append(Shell.ligado()).append(" autorizado=").append(Shell.disponivel()).append('\n')
                log.append("1) abrir ").append(comp).append(" em janela:\n")
                log.append(Shell.correr("am start --windowingMode 5 -n $comp").take(300)).append('\n')
                Thread.sleep(1500)
                val id = Shell.idTarefa(calc)
                log.append("2) tarefa=").append(id).append('\n')
                if (id != null) {
                    log.append("3) redimensionar: ")
                    log.append(Shell.correr("am task resize $id ${w / 4} ${h / 4} ${w * 3 / 4} ${h * 3 / 4}").take(300)).append('\n')
                }
                Thread.sleep(800)
                log.append("4) modos:\n")
                log.append(Shell.correr("dumpsys activity activities | grep -iE 'windowingMode|mode=freeform|mBounds' | head -12").take(1200))
            } catch (e: Throwable) {
                log.append("ERRO: ").append(e.javaClass.simpleName).append(": ").append(e.message)
            }
            Prefs.p(this).edit().putString(Prefs.TESTE, log.toString().take(3000)).apply()
        }.start()
    }

    // ---------- permissões ----------

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

    private fun atualizarEstado() {
        val sobrepor = Settings.canDrawOverlays(this)
        val uso = temAcessoUso()
        estado.text = buildString {
            append(if (sobrepor) "✔ Sobrepor a outras apps\n" else "✘ Falta: sobrepor a outras apps\n")
            append(if (uso) "✔ Acesso a dados de utilização (apps abertas)" else "✘ Falta: acesso a dados de utilização (para mostrar as apps abertas)")
            append(if (Shell.disponivel()) "\n✔ Shizuku (janelas)" else if (Shell.ligado()) "\n✘ Falta: autorizar o Shizuku" else "\n✘ Shizuku não está a correr")
            append("\n\nBarra: ").append(BarraService.estado)
            append("\nTeclado/rato: ").append(BarraService.dispositivos.ifBlank { "?" })
            append("\nEcrã/carro: ").append(ModoCarro.estado)
            val erro = Prefs.p(this@MainActivity).getString(Prefs.ERRO, "") ?: ""
            if (erro.isNotBlank()) append("\nÚltimo erro: ").append(erro)
            val teste = Prefs.p(this@MainActivity).getString(Prefs.TESTE, "") ?: ""
            if (teste.isNotBlank()) append("\n\nTeste das janelas:\n").append(teste)
        }
    }

    // ---------- construção do ecrã ----------

    private fun titulo(t: String) = raiz.addView(TextView(this).apply {
        text = t
        textSize = 17f
        setTextColor(Color.WHITE)
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun nota(t: String) = raiz.addView(TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.LTGRAY)
        setPadding(0, 0, 0, dp(6))
    })

    private fun botao(t: String, acao: () -> Unit) = raiz.addView(Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { acao() }
    })

    private fun deslizador(rotulo: String, chave: String, min: Int, max: Int, atual: Int, sufixo: String) {
        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            text = "$rotulo: $atual$sufixo"
            setPadding(0, dp(12), 0, 0)
        }
        raiz.addView(tv)
        raiz.addView(SeekBar(this).apply {
            this.max = max - min
            progress = atual - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$rotulo: ${p + min}$sufixo"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Prefs.p(this@MainActivity).edit().putInt(chave, (s?.progress ?: 0) + min).apply()
                }
            })
        })
    }

    private fun escolha(chave: String, atual: String, opcoes: List<Pair<String, String>>) {
        val rg = RadioGroup(this)
        for ((valor, texto) in opcoes) {
            val rb = RadioButton(this).apply {
                text = texto
                id = View.generateViewId()
                isChecked = valor == atual
                setTextColor(Color.WHITE)
                setOnClickListener { Prefs.p(this@MainActivity).edit().putString(chave, valor).apply() }
            }
            rg.addView(rb)
        }
        raiz.addView(rg)
    }

    private fun caixa(texto: String, chave: String, atual: Boolean) = raiz.addView(CheckBox(this).apply {
        text = texto
        isChecked = atual
        setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, v -> Prefs.p(this@MainActivity).edit().putBoolean(chave, v).apply() }
    })

    private fun construir() {
        raiz.addView(TextView(this).apply {
            text = "Barra"
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        nota("Barra de tarefas tipo Windows. Toque longo num ícone (na barra ou no menu Iniciar) para o fixar ou retirar. Toque longo no botão Iniciar abre este ecrã.")

        titulo("Permissões")
        estado = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE) }
        raiz.addView(estado)
        botao("Reiniciar a barra") { reiniciarServico() }
        botao("Autorizar o Shizuku (janelas)") { pedirShizuku() }
        botao("Testar janelas") { testarJanelas() }
        botao("Dar permissão: sobrepor a outras apps") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
        botao("Dar permissão: dados de utilização") {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) {}
        }
        botao("Informação da app (permitir definições restritas)") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }

        titulo("Quando aparece")
        escolha(Prefs.MODO, Prefs.modo(this), listOf(
            "auto" to "Automático — só com teclado ou rato ligado",
            "sempre" to "Sempre",
            "nunca" to "Nunca"
        ))

        titulo("Posição")
        escolha(Prefs.POSICAO, Prefs.posicao(this), listOf(
            "baixo" to "Em baixo",
            "cima" to "Em cima"
        ))

        titulo("Tamanhos")
        deslizador("Tamanho dos ícones", Prefs.TAM_ICONE, 12, 64, Prefs.tamIcone(this), " dp")
        deslizador("Altura da barra", Prefs.ALTURA, 20, 96, Prefs.altura(this), " dp")
        deslizador("Colunas do menu Iniciar", Prefs.COLUNAS_MENU, 3, 10, Prefs.colunasMenu(this), "")

        titulo("Aspeto")
        deslizador("Opacidade do fundo", Prefs.OPACIDADE, 0, 100, Prefs.opacidade(this), " %")
        nota("Cor do fundo:")
        val cores = listOf("#101418", "#2B2F36", "#0D2A4A", "#C8102E", "#1B5E20", "#FFFFFF")
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (c in cores) {
            linha.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor(c))
                    setStroke(dp(2), Color.GRAY)
                }
                setOnClickListener { Prefs.p(this@MainActivity).edit().putString(Prefs.COR, c).apply() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        }
        raiz.addView(linha)

        titulo("Conteúdo")
        caixa("Esconder a barra sozinha (aparece ao passar o rato no fundo do ecrã)", Prefs.AUTO_ESCONDER, Prefs.autoEsconder(this))
        caixa("Mostrar relógio e data", Prefs.RELOGIO, Prefs.relogio(this))
        caixa("Mostrar apps abertas / recentes", Prefs.RECENTES, Prefs.recentes(this))
        deslizador("Máximo de apps recentes", Prefs.MAX_RECENTES, 0, 20, Prefs.maxRecentes(this), "")

        titulo("Versão")
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        nota("Barra $v")
    }
}
