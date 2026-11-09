package ao.pedromelo.barra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Arranca a barra quando o tablet liga ou quando a app é atualizada. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.startForegroundService(Intent(context, BarraService::class.java))
        } catch (_: Exception) {}
    }
}
