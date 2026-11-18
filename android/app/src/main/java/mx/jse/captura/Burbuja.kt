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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Botón flotante sobre las demás apps (permiso «Mostrar sobre otras apps»).
 * - Listo: círculo rojo grande «● REC». Tocarlo empieza a grabar.
 * - Grabando: punto pequeño y casi transparente «■» (sale en el video, por eso es discreto). Tocarlo detiene.
 * Se puede arrastrar a cualquier orilla; la posición se recuerda.
 */
class Burbuja(private val ctx: Context, private val alTocar: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = ctx.getSharedPreferences("burbuja", Context.MODE_PRIVATE)
    private var vista: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var ultimoToque = 0L

    val visible get() = vista != null

    @SuppressLint("ClickableViewAccessibility")
    fun mostrar(): Boolean {
        if (vista != null) return true
        if (!Settings.canDrawOverlays(ctx)) return false
        val v = TextView(ctx).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            contentDescription = "Botón de grabación de JSE Captura"
            setOnClickListener {
                val ahora = SystemClock.elapsedRealtime()
                if (ahora - ultimoToque > 800) { ultimoToque = ahora; alTocar() }
            }
        }
        val p = WindowManager.LayoutParams(
            Ui.dp(ctx, 64), Ui.dp(ctx, 64),
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
        v.setOnTouchListener { vv, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; px = p.x; py = p.y; arrastrando = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!arrastrando && (abs(dx) > slop || abs(dy) > slop)) arrastrando = true
                    if (arrastrando) { p.x = px + dx.toInt(); p.y = py + dy.toInt(); runCatching { wm.updateViewLayout(vv, p) } }
                }
                MotionEvent.ACTION_UP -> {
                    if (arrastrando) prefs.edit().putInt("x", p.x).putInt("y", p.y).apply() else vv.performClick()
                }
            }
            true
        }
        return runCatching { wm.addView(v, p) }.map { vista = v; lp = p; modoListo(); true }.getOrDefault(false)
    }

    private fun pintar(texto: String, tamDp: Int, alfa: Float, colorFondo: Int, tamTexto: Float) {
        val v = vista ?: return
        val p = lp ?: return
        v.text = texto; v.textSize = tamTexto; v.alpha = alfa
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorFondo); setStroke(Ui.dp(ctx, 2), Color.WHITE) }
        p.width = Ui.dp(ctx, tamDp); p.height = Ui.dp(ctx, tamDp)
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun modoListo() = pintar("● REC", 64, 1f, Ui.ROJO, 13f)
    fun modoGrabando() = pintar("■", 38, 0.4f, Ui.ROJO, 14f)
    fun modoGuardando() = pintar("…", 38, 0.6f, 0xFF555555.toInt(), 14f)

    fun quitar() {
        vista?.let { runCatching { wm.removeView(it) } }
        vista = null; lp = null
    }
}
