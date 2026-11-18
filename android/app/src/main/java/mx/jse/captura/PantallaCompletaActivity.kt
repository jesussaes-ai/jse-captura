package mx.jse.captura

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Reproductor a pantalla completa para leer tablas y cursos:
 * - Se ajusta a toda la pantalla y gira sola a horizontal si el video es horizontal.
 * - «1:1» o doble toque: tamaño original (un píxel del video = un píxel de la pantalla).
 * - Pellizcar para acercar (hasta 8×) y arrastrar para moverse.
 * - Un toque muestra u oculta los controles.
 */
class PantallaCompletaActivity : Activity() {
    private var mp: MediaPlayer? = null
    private lateinit var tv: TextureView
    private lateinit var controles: LinearLayout
    private lateinit var arriba: LinearLayout
    private lateinit var barra: SeekBar
    private lateinit var tiempo: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnTam: Button
    private var vw = 0
    private var vh = 0
    private var zoom = 1f
    private var px = 0f
    private var py = 0f
    private var posInicial = 0
    private var arrastrandoBarra = false
    private val h = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() {
            mp?.let { if (!arrastrandoBarra && it.duration > 0) { barra.max = it.duration; barra.progress = it.currentPosition }; pintarTiempo() }
            h.postDelayed(this, 250)
        }
    }
    private val ocultar = Runnable { mostrarControles(false) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = Biblioteca.obtener(intent.getStringExtra("id") ?: "") ?: return finish()
        val ruta = g.archivo?.takeIf { File(it).exists() } ?: return finish()
        posInicial = savedInstanceState?.getInt("pos") ?: intent.getIntExtra("pos", 0)
        val c = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.BLACK)
        if (savedInstanceState == null && g.ancho > g.alto) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val raiz = FrameLayout(c).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(c)
        raiz.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- barra superior: cerrar, título, versión (regla: versión arriba a la derecha) ----
        arriba = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0x99000000.toInt())
            setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4))
        }
        arriba.addView(Ui.boton(c, "✕") { finish() })
        arriba.addView(Ui.texto(c, g.titulo, 15f, Ui.TEXTO, true).apply { maxLines = 1; setPadding(Ui.dp(c, 8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        arriba.addView(TextView(c).apply { text = "v${BuildConfig.VERSION_NAME}"; textSize = 12f; setTextColor(Ui.SUTIL); tag = "version" })
        raiz.addView(arriba, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ---- controles inferiores ----
        controles = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0x99000000.toInt())
            setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 8))
        }
        barra = SeekBar(c).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, deUsuario: Boolean) { if (deUsuario) { mp?.seekTo(p); pintarTiempo(p) } }
                override fun onStartTrackingTouch(s: SeekBar) { arrastrandoBarra = true; h.removeCallbacks(ocultar) }
                override fun onStopTrackingTouch(s: SeekBar) { arrastrandoBarra = false; programarOcultar() }
            })
        }
        controles.addView(barra)
        btnPlay = Ui.boton(c, "❚❚") { alternarPlay() }
        btnTam = Ui.boton(c, "1:1") { alternarTamano() }
        tiempo = Ui.texto(c, "0:00 / 0:00", 13f, Ui.TEXTO)
        controles.addView(Ui.fila(c,
            Ui.boton(c, "−10 s") { mp?.let { it.seekTo(max(0, it.currentPosition - 10_000)) } },
            btnPlay,
            Ui.boton(c, "+10 s") { mp?.let { it.seekTo(min(it.duration, it.currentPosition + 10_000)) } },
            btnTam,
            Ui.boton(c, "⟳") { girar() },
            tiempo))
        raiz.addView(controles, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(raiz)
        pantallaInmersiva()
        // Respeta el recorte de la cámara y las barras cuando aparecen (Android 15 dibuja de borde a borde).
        raiz.setOnApplyWindowInsetsListener { _, ins ->
            val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
                val m = ins.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                listOf(m.left, m.top, m.right, m.bottom)
            } else @Suppress("DEPRECATION") listOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            arriba.setPadding(Ui.dp(c, 8) + l, Ui.dp(c, 4) + t, Ui.dp(c, 12) + r, Ui.dp(c, 4))
            controles.setPadding(Ui.dp(c, 12) + l, Ui.dp(c, 4), Ui.dp(c, 12) + r, Ui.dp(c, 8) + b)
            ins
        }

        // ---- gestos: pellizcar, arrastrar, toque y doble toque ----
        val escala = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val nuevo = (zoom * d.scaleFactor).coerceIn(1f, 8f)
                val k = nuevo / zoom
                val fx = d.focusX - tv.width / 2f; val fy = d.focusY - tv.height / 2f
                px = (px - fx) * k + fx; py = (py - fy) * k + fy
                zoom = nuevo; aplicar(); return true
            }
        })
        val gestos = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { mostrarControles(controles.visibility != View.VISIBLE); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { alternarTamano(); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom <= 1f) return false
                px -= dx; py -= dy; aplicar(); return true
            }
        })
        tv.setOnTouchListener { _, e -> escala.onTouchEvent(e); gestos.onTouchEvent(e); true }
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> aplicar() }

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, hh: Int) = abrir(ruta, Surface(st))
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, hh: Int) = aplicar()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { liberar(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        programarOcultar()
    }

    private fun abrir(ruta: String, s: Surface) {
        liberar()
        mp = MediaPlayer().apply {
            setDataSource(ruta)
            setSurface(s)
            setOnVideoSizeChangedListener { _, w, hh -> if (w > 0 && hh > 0) { vw = w; vh = hh; aplicar() } }
            setOnPreparedListener { p ->
                vw = p.videoWidth; vh = p.videoHeight; aplicar()
                barra.max = p.duration
                if (posInicial > 0) p.seekTo(posInicial)
                p.start(); btnPlay.text = "❚❚"
                h.post(tic)
            }
            setOnCompletionListener { btnPlay.text = "▶"; mostrarControles(true) }
            setOnErrorListener { _, _, _ -> tiempo.text = "No se pudo reproducir este MP4."; true }
            prepareAsync()
        }
    }

    private fun liberar() {
        h.removeCallbacks(tic)
        mp?.let { posInicial = runCatching { it.currentPosition }.getOrDefault(posInicial); runCatching { it.release() } }
        mp = null
    }

    /** Escala base = el video completo cabe en la pantalla; zoom y desplazamiento encima, sin salirse. */
    private fun aplicar() {
        val w = tv.width.toFloat(); val hh = tv.height.toFloat()
        if (vw == 0 || vh == 0 || w == 0f || hh == 0f) return
        val ajuste = min(w / vw, hh / vh)
        val cw = vw * ajuste * zoom; val ch = vh * ajuste * zoom
        val mx = max(0f, (cw - w) / 2); val my = max(0f, (ch - hh) / 2)
        px = px.coerceIn(-mx, mx); py = py.coerceIn(-my, my)
        val m = Matrix()
        // TextureView estira el video a todo su tamaño; se corrige a la proporción real y se centra.
        m.setScale(cw / w, ch / hh)
        m.postTranslate((w - cw) / 2 + px, (hh - ch) / 2 + py)
        tv.setTransform(m)
        tv.invalidate()
        val unoAUno = 1f / ajuste
        btnTam.text = if (zoom > 1.01f) "Ajustar" else if (unoAUno > 1.01f) "1:1" else "2×"
    }

    /** Ajustar a pantalla ↔ tamaño original (1:1). Si el video ya es más chico que la pantalla, acerca 2×. */
    private fun alternarTamano() {
        val w = tv.width.toFloat(); val hh = tv.height.toFloat()
        if (vw == 0 || w == 0f) return
        val ajuste = min(w / vw, hh / vh)
        zoom = if (zoom > 1.01f) 1f else (if (1f / ajuste > 1.01f) 1f / ajuste else 2f).coerceAtMost(8f)
        px = 0f; py = 0f
        aplicar()
    }

    private fun alternarPlay() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); btnPlay.text = "▶" } else { p.start(); btnPlay.text = "❚❚" }
        programarOcultar()
    }

    private fun girar() {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun pintarTiempo(pos: Int? = null) {
        val p = mp ?: return
        val d = runCatching { p.duration }.getOrDefault(0)
        tiempo.text = "${Ui.duracion((pos ?: p.currentPosition) / 1000.0)} / ${Ui.duracion(d / 1000.0)}" + if (zoom > 1.01f) "  ·  ${"%.1f".format(zoom)}×" else ""
    }

    private fun mostrarControles(si: Boolean) {
        val v = if (si) View.VISIBLE else View.GONE
        controles.visibility = v
        arriba.visibility = v
        if (si) programarOcultar()
    }

    private fun programarOcultar() {
        h.removeCallbacks(ocultar)
        if (mp?.isPlaying != false) h.postDelayed(ocultar, 3500)
    }

    private fun pantallaInmersiva() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pantallaInmersiva()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("pos", mp?.let { runCatching { it.currentPosition }.getOrNull() } ?: posInicial)
    }

    override fun onPause() {
        super.onPause()
        mp?.let { if (it.isPlaying) { it.pause(); btnPlay.text = "▶" } }
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacksAndMessages(null)
        liberar()
    }
}
