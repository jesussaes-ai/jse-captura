package mx.jse.captura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Botón flotante sobre las demás apps (permiso «Mostrar sobre otras apps»).
 * - Listo: círculo rojo «● REC». Tocarlo empieza a grabar lo que haya en pantalla.
 * - Grabando: dos botones pequeños y casi transparentes (salen en el video, por eso son discretos):
 *   [❚❚] pausa / [▶] seguir, y [■] terminar.
 * Se puede arrastrar a cualquier orilla; la posición se recuerda.
 */
class Burbuja(private val ctx: Context, private val alTocar: (boton: Int) -> Unit) {
    companion object { const val PRINCIPAL = 0; const val DETENER = 1 }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = ctx.getSharedPreferences("burbuja", Context.MODE_PRIVATE)
    private var raiz: LinearLayout? = null
    private var principal: TextView? = null
    private var detener: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var ultimoToque = 0L

    val visible get() = raiz != null

    @SuppressLint("ClickableViewAccessibility")
    fun mostrar(): Boolean {
        if (raiz != null) return true
        if (!Settings.canDrawOverlays(ctx)) return false
        fun boton(desc: String) = TextView(ctx).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            contentDescription = desc
            includeFontPadding = false
        }
        val a = boton("Botón de grabación de JSE Captura")
        val b = boton("Terminar grabación").apply { visibility = View.GONE }
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(a); addView(b) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "JSE Captura burbuja"
            val m = ctx.resources.displayMetrics
            x = prefs.getInt("x", m.widthPixels - Ui.dp(ctx, 80))
            y = prefs.getInt("y", m.heightPixels / 3)
        }
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var px = 0; var py = 0; var arrastrando = false
        fun arrastre(v: View, cual: Int) = v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; px = p.x; py = p.y; arrastrando = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!arrastrando && (abs(dx) > slop || abs(dy) > slop)) arrastrando = true
                    if (arrastrando) { p.x = px + dx.toInt(); p.y = py + dy.toInt(); runCatching { wm.updateViewLayout(r, p) } }
                }
                MotionEvent.ACTION_UP -> {
                    if (arrastrando) prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
                    else {
                        val ahora = SystemClock.elapsedRealtime()
                        android.util.Log.i("JSECaptura", "toque en la burbuja ($cual)")
                        if (ahora - ultimoToque > 700) { ultimoToque = ahora; alTocar(cual) }
                    }
                }
            }
            true
        }
        arrastre(a, PRINCIPAL); arrastre(b, DETENER)
        return runCatching { wm.addView(r, p) }.map {
            raiz = r; principal = a; detener = b; lp = p; modoListo(); true
        }.getOrDefault(false)
    }

    private fun circulo(v: TextView, texto: String, tamDp: Int, colorFondo: Int, tamTexto: Float) {
        v.text = texto; v.textSize = tamTexto
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorFondo); setStroke(Ui.dp(ctx, 2), Color.WHITE) }
        v.layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, tamDp), Ui.dp(ctx, tamDp)).apply { marginEnd = Ui.dp(ctx, 4) }
    }

    private fun pintar(alfa: Float, uno: (TextView) -> Unit, dos: ((TextView) -> Unit)?) {
        val r = raiz ?: return
        r.alpha = alfa
        uno(principal!!)
        detener!!.visibility = if (dos != null) View.VISIBLE else View.GONE
        dos?.invoke(detener!!)
        runCatching { wm.updateViewLayout(r, lp) }
    }

    fun modoListo() = pintar(1f, {
        it.contentDescription = "Botón de grabación de JSE Captura"; circulo(it, "● REC", 64, Ui.ROJO, 13f)
    }, null)

    fun modoGrabando(pausado: Boolean) = pintar(if (pausado) 1f else 0.45f, {
        it.contentDescription = if (pausado) "Seguir grabando" else "Pausar grabación"
        circulo(it, if (pausado) "▶" else "❚❚", 40, if (pausado) 0xFF2E7D32.toInt() else 0xFF444444.toInt(), 14f)
    }, { circulo(it, "■", 40, Ui.ROJO, 14f) })

    fun cuenta(n: Int) = pintar(1f, { it.contentDescription = "Empieza en $n"; circulo(it, "$n", 64, Ui.ROJO, 22f) }, null)

    fun modoGuardando() = pintar(0.6f, { it.contentDescription = "Guardando"; circulo(it, "…", 40, 0xFF555555.toInt(), 14f) }, null)

    fun quitar() {
        raiz?.let { runCatching { wm.removeView(it) } }
        raiz = null; principal = null; detener = null; lp = null
    }
}
