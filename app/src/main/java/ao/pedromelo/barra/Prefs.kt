package ao.pedromelo.barra

import android.content.Context
import android.content.SharedPreferences

/** Todas as definições da barra, num só sítio (versão com janelas). */
object Prefs {
    const val NOME = "barra"

    const val MODO = "modo"                 // "auto" | "sempre" | "nunca"
    const val POSICAO = "posicao"           // "baixo" | "cima"
    const val TAM_ICONE = "tamIcone"        // dp
    const val ALTURA = "altura"             // dp
    const val OPACIDADE = "opacidade"       // 0..100
    const val COR = "cor"                   // "#RRGGBB"
    const val RELOGIO = "relogio"
    const val RECENTES = "recentes"
    const val MAX_RECENTES = "maxRecentes"
    const val FIXAS = "fixas"               // pacotes separados por vírgula
    const val COLUNAS_MENU = "colunasMenu"
    const val ERRO = "ultimoErro"
    const val TESTE = "testeJanelas"
    const val AUTO_ESCONDER = "autoEsconder"

    fun autoEsconder(c: Context) = p(c).getBoolean(AUTO_ESCONDER, true)

    // botão de apagar o ecrã e modo carro
    const val BOTAO_DESLIGAR = "botaoDesligar"
    const val BOTAO_X = "botaoX"            // 0..100 % da largura
    const val BOTAO_TAM = "botaoTam"        // dp
    const val MANTER_ACESO = "manterAceso"
    const val BLOQUEAR_SEM_CORRENTE = "bloquearSemCorrente"
    const val ACORDAR_COM_CORRENTE = "acordarComCorrente"
    val CHAVES_CARRO = setOf(BOTAO_DESLIGAR, BOTAO_X, BOTAO_TAM, MANTER_ACESO, BLOQUEAR_SEM_CORRENTE, ACORDAR_COM_CORRENTE)

    fun botaoDesligar(c: Context) = p(c).getBoolean(BOTAO_DESLIGAR, true)
    fun botaoX(c: Context) = p(c).getInt(BOTAO_X, 70)
    fun botaoTam(c: Context) = p(c).getInt(BOTAO_TAM, 34)
    fun manterAceso(c: Context) = p(c).getBoolean(MANTER_ACESO, true)
    fun bloquearSemCorrente(c: Context) = p(c).getBoolean(BLOQUEAR_SEM_CORRENTE, true)
    fun acordarComCorrente(c: Context) = p(c).getBoolean(ACORDAR_COM_CORRENTE, true)

    /** Como a app ficou da última vez: "janela", "max" ou "" (normal). */
    fun modoJanela(c: Context, pkg: String) = p(c).getString("janela_modo_$pkg", "") ?: ""

    fun gravarModoJanela(c: Context, pkg: String, modo: String) {
        p(c).edit().putString("janela_modo_$pkg", modo).apply()
    }

    /** Limites guardados da janela (esq, topo, dir, baixo) ou null. */
    fun limitesJanela(c: Context, pkg: String): List<Int>? {
        val t = p(c).getString("janela_lim_$pkg", "") ?: ""
        val n = t.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (n.size == 4) n else null
    }

    fun gravarLimitesJanela(c: Context, pkg: String, l: Int, t: Int, r: Int, b: Int) {
        p(c).edit().putString("janela_lim_$pkg", "$l,$t,$r,$b").apply()
    }

    fun p(c: Context): SharedPreferences = c.getSharedPreferences(NOME, Context.MODE_PRIVATE)

    fun modo(c: Context) = p(c).getString(MODO, "auto") ?: "auto"
    fun posicao(c: Context) = p(c).getString(POSICAO, "baixo") ?: "baixo"
    fun tamIcone(c: Context) = p(c).getInt(TAM_ICONE, 24)
    fun altura(c: Context) = p(c).getInt(ALTURA, 36)
    fun opacidade(c: Context) = p(c).getInt(OPACIDADE, 85)
    fun cor(c: Context) = p(c).getString(COR, "#101418") ?: "#101418"
    fun relogio(c: Context) = p(c).getBoolean(RELOGIO, true)
    fun recentes(c: Context) = p(c).getBoolean(RECENTES, true)
    fun maxRecentes(c: Context) = p(c).getInt(MAX_RECENTES, 8)
    fun colunasMenu(c: Context) = p(c).getInt(COLUNAS_MENU, 5)

    fun fixas(c: Context): MutableList<String> =
        (p(c).getString(FIXAS, "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()

    fun gravarFixas(c: Context, l: List<String>) {
        p(c).edit().putString(FIXAS, l.joinToString(",")).apply()
    }

    /** Fixa ou desafixa; devolve true se ficou fixa. */
    fun alternarFixa(c: Context, pkg: String): Boolean {
        val l = fixas(c)
        val ficou = if (l.contains(pkg)) { l.remove(pkg); false } else { l.add(pkg); true }
        gravarFixas(c, l)
        return ficou
    }
}
