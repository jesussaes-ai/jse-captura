package mx.jse.captura

import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Notification

/** Ayudas de interfaz (vistas hechas en código, sin XML). */
object Ui {
    const val ROJO = 0xFFFF3B30.toInt()
    const val PANEL = 0xFF181E24.toInt()
    const val TEXTO = 0xFFE8EDF2.toInt()
    const val SUTIL = 0xFF94A1AE.toInt()
    const val OK = 0xFF2FBF71.toInt()
    const val PEND = 0xFFE0A400.toInt()
    const val MAL = 0xFFFF6B5E.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun duracion(s: Double): String {
        val t = s.toLong(); val h = t / 3600; val m = (t % 3600) / 60; val x = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }

    fun tamano(b: Long) = if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "%.1f MB".format(b / 1048576.0)

    fun nombreSeguro(s: String) = s.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim().take(60).ifEmpty { "Grabación" }

    fun fondo(color: Int, radio: Float = 24f, borde: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radio; borde?.let { setStroke(2, it) }
    }

    fun texto(ctx: Context, t: String, tam: Float = 15f, color: Int = TEXTO, negrita: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = tam; setTextColor(color); if (negrita) setTypeface(typeface, Typeface.BOLD)
    }

    fun boton(ctx: Context, t: String, principal: Boolean = false, alTocar: (View) -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE)
        background = fondo(if (principal) ROJO else PANEL, 40f, if (principal) null else 0xFF2A323B.toInt())
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        setOnClickListener(alTocar)
    }

    fun fila(ctx: Context, vararg hijos: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        hijos.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 8) }) }
    }

    /**
     * Envuelve cada pantalla: respeta la barra de estado, el recorte de la cámara y la barra de navegación
     * (Android 15 dibuja la app de borde a borde) y pone la versión arriba a la derecha, en su propio renglón
     * para que nunca tape botones (regla de Jesús: versión visible en todas las pantallas).
     */
    fun conVersion(ctx: Context, contenido: View): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ctx).apply {
            text = "v${BuildConfig.VERSION_NAME}"; textSize = 12f; setTextColor(SUTIL); gravity = Gravity.END
            setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), 0); tag = "version"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(contenido, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setOnApplyWindowInsetsListener { v, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val m = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(m.left, m.top + dp(ctx, 8), m.right, m.bottom)
            } else {
                @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop + dp(ctx, 8), ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
    }

    fun columna(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun chip(ctx: Context, nombre: String, d: Destino?): TextView {
        val e = d?.estado ?: "pendiente"
        val color = when (e) { "guardado", "visible" -> OK; "requiere_atencion" -> MAL; "pendiente", "copiando" -> PEND; "exportado" -> 0xFF7FB3FF.toInt(); else -> SUTIL }
        val pct = if (e == "copiando") " ${(d!!.progreso * 100).toInt()}%" else ""
        return texto(ctx, "$nombre: ${Destino.TEXTO[e] ?: e}$pct", 12f, color).apply {
            background = fondo(0, 30f, color); setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
        }
    }

    fun avisoSistema(ctx: Context, titulo: String, texto: String) {
        val n = Notification.Builder(ctx, App.CANAL_AVISOS).setSmallIcon(R.drawable.ic_rec).setContentTitle(titulo)
            .setContentText(texto).setStyle(Notification.BigTextStyle().bigText(texto)).setAutoCancel(true).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(titulo.hashCode(), n) }
    }
}
