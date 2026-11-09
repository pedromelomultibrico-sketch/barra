package ao.pedromelo.barra

import android.content.Context
import android.view.MotionEvent
import android.widget.LinearLayout

/** LinearLayout que avisa quando o rato passa por cima (para o auto-esconder). */
class BarraLayout(c: Context) : LinearLayout(c) {
    var aoPairar: ((MotionEvent) -> Unit)? = null
    var aoTocar: (() -> Boolean)? = null

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        aoPairar?.invoke(event)
        return super.dispatchHoverEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // barra recolhida: o primeiro toque só a abre
        if (ev.action == MotionEvent.ACTION_DOWN && aoTocar?.invoke() == true) return true
        return super.onInterceptTouchEvent(ev)
    }
}
