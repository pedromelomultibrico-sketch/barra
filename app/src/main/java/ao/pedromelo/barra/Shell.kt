package ao.pedromelo.barra

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Comandos de sistema através do Shizuku (o mesmo que o Jarvis usa). */
object Shell {

    fun ligado(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun disponivel(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** Corre um comando e devolve o que ele escreveu (saída + erros). Não chamar na linha principal. */
    fun correr(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        return (out + err).trim()
    }

    private val padroes = listOf(
        Regex("""Task\{[0-9a-f]+ #(\d+)[^}]*?A=\d+:([\w.]+)"""),
        Regex("""taskId=(\d+)[^\n]*?([\w.]+)/"""),
        Regex("""\* Recent #\d+: Task\{[0-9a-f]+ #(\d+) [^\n]*?:([\w.]+)""")
    )

    /** Procura o número da tarefa (janela) de uma app. */
    fun idTarefa(pkg: String): Int? {
        val txt = correr("dumpsys activity recents")
        for (re in padroes) {
            for (m in re.findAll(txt)) {
                if (m.groupValues[2] == pkg) return m.groupValues[1].toIntOrNull()
            }
        }
        // último recurso: primeira linha com o pacote e um #número
        for (linha in txt.lineSequence()) {
            if (linha.contains(pkg)) {
                val n = Regex("""#(\d+)""").find(linha)?.groupValues?.get(1)?.toIntOrNull()
                if (n != null) return n
            }
        }
        return null
    }
}
