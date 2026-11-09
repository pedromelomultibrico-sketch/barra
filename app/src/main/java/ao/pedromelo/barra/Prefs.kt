package ao.pedromelo.barra

import android.content.Context
import android.content.SharedPreferences

/** Todas as definições da barra, num só sítio. */
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
