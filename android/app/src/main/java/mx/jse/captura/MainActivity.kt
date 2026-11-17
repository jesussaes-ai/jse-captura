package mx.jse.captura

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var aj: Ajustes
    private lateinit var btnGrabar: android.widget.Button
    private lateinit var estado: TextView
    private lateinit var resumen: TextView
    private lateinit var lista: RecyclerView
    private lateinit var vacio: TextView
    private lateinit var buscar: EditText
    private lateinit var fPend: CheckBox
    private lateinit var fFav: CheckBox
    private val h = Handler(Looper.getMainLooper())
    private val adaptador = Adaptador()
    private val oyenteBib: () -> Unit = { h.post { refrescar() } }
    private val oyenteCaptura: () -> Unit = { pintarEstado() }
    private val reloj = object : Runnable {
        override fun run() { pintarEstado(); if (CapturaService.activo) h.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aj = Ajustes(this)
        val c = this
        val raiz = Ui.columna(c).apply { setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), 0) }

        raiz.addView(Ui.fila(c, Ui.texto(c, "● JSE Captura", 20f, Ui.ROJO, true),
            Ui.boton(c, "NAS") { startActivity(Intent(c, NasActivity::class.java)) },
            Ui.boton(c, "Ajustes") { startActivity(Intent(c, AjustesActivity::class.java)) }))

        // ---- grabar ----
        val calidad = RadioGroup(c).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(c).apply { id = 1080; text = "Hasta 1080p"; setTextColor(Ui.TEXTO) })
            addView(RadioButton(c).apply { id = 720; text = "720p (ahorra espacio)"; setTextColor(Ui.TEXTO) })
            check(if (aj.calidad == "720") 720 else 1080)
            setOnCheckedChangeListener { _, id -> aj.calidad = id.toString(); pintarResumen() }
        }
        val mic = CheckBox(c).apply { text = "Micrófono"; setTextColor(Ui.TEXTO); isChecked = aj.microfono
            setOnCheckedChangeListener { _, v -> aj.microfono = v; if (v) pedirAudio(); pintarResumen() } }
        val interno = CheckBox(c).apply { text = "Audio interno (si la app lo permite)"; setTextColor(Ui.TEXTO); isChecked = aj.audioInterno
            setOnCheckedChangeListener { _, v -> aj.audioInterno = v; if (v) pedirAudio(); pintarResumen() } }
        resumen = Ui.texto(c, "", 13f, Ui.SUTIL)
        btnGrabar = Ui.boton(c, "● Grabar", true) { alternar() }.apply { textSize = 18f }
        estado = Ui.texto(c, "", 14f, Ui.TEXTO)
        raiz.addView(calidad)
        raiz.addView(Ui.fila(c, mic, interno))
        raiz.addView(resumen)
        raiz.addView(btnGrabar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 8) })
        raiz.addView(estado)

        // ---- biblioteca ----
        buscar = EditText(c).apply { hint = "Buscar por título, notas o etiquetas"; setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUTIL)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = refrescar()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            }) }
        fPend = CheckBox(c).apply { text = "Pendientes"; setTextColor(Ui.TEXTO); setOnCheckedChangeListener { _, _ -> refrescar() } }
        fFav = CheckBox(c).apply { text = "★ Favoritos"; setTextColor(Ui.TEXTO); setOnCheckedChangeListener { _, _ -> refrescar() } }
        raiz.addView(buscar)
        raiz.addView(Ui.fila(c, fPend, fFav, Ui.boton(c, "⟳ Sincronizar ahora") { Sincronizar.forzar(c); estado.text = "Sincronizando…" }))
        vacio = Ui.texto(c, "Aún no hay grabaciones. Toca Grabar, o usa el mosaico «JSE Captura» de Ajustes rápidos para grabar desde cualquier app.", 14f, Ui.SUTIL)
        raiz.addView(vacio)
        lista = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c); adapter = adaptador }
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        pintarResumen()
    }

    override fun onResume() {
        super.onResume()
        Biblioteca.alCambiar(oyenteBib)
        CapturaService.oyentes.add(oyenteCaptura)
        refrescar(); pintarEstado()
        if (CapturaService.activo) h.post(reloj)
    }

    override fun onPause() {
        super.onPause()
        Biblioteca.quitarOyente(oyenteBib)
        CapturaService.oyentes.remove(oyenteCaptura)
        h.removeCallbacks(reloj)
    }

    private fun pedirAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 1 && r.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            aj.microfono = false; aj.audioInterno = false
            estado.text = "Sin permiso de micrófono: se grabará sin audio. Puedes darlo en Ajustes de Android → Apps → JSE Captura → Permisos."
            recreate()
        }
    }

    private fun pintarResumen() {
        val audio = listOfNotNull(if (aj.microfono) "micrófono" else null, if (aj.audioInterno) "audio interno" else null)
        resumen.text = "Se grabará: pantalla (o la app que elijas en Android 14+) · ${if (aj.calidad == "720") "720p" else "hasta 1080p"} · 30 fps · " +
            (if (audio.isEmpty()) "🔇 sin audio" else "🔊 " + audio.joinToString(" + ")) +
            (if (aj.audioInterno) "\nAlgunas apps (Netflix, WhatsApp, llamadas) no permiten capturar su audio." else "")
    }

    private fun alternar() {
        if (CapturaService.activo) CapturaService.detener(this)
        else {
            CapturaService.ultimoError = null
            if ((aj.microfono || aj.audioInterno) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { pedirAudio(); return }
            startActivity(Intent(this, PermisoCapturaActivity::class.java))
            h.postDelayed(reloj, 800)
        }
    }

    private fun pintarEstado() {
        if (CapturaService.activo) {
            btnGrabar.text = "■ Detener  " + Ui.duracion((SystemClock.elapsedRealtime() - CapturaService.inicioMs) / 1000.0)
            estado.text = "Grabando. Puedes cambiar de app: detén desde la notificación o el mosaico de Ajustes rápidos." + (CapturaService.ultimoError?.let { "\n⚠ $it" } ?: "")
        } else {
            btnGrabar.text = "● Grabar"
            estado.text = CapturaService.ultimoError?.let { "⚠ $it" } ?: ""
        }
    }

    private fun refrescar() {
        val l = Biblioteca.listar(buscar.text.toString(), fPend.isChecked, fFav.isChecked)
        vacio.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
        adaptador.items = l
        adaptador.notifyDataSetChanged()
    }

    inner class Adaptador : RecyclerView.Adapter<Adaptador.VH>() {
        var items: List<Grabacion> = emptyList()
        inner class VH(val v: LinearLayout) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val v = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Ui.fondo(Ui.PANEL, 24f)
                setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(c, 8) }
            }
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(vh: VH, i: Int) {
            val g = items[i]
            val c = vh.v.context
            vh.v.removeAllViews()
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF000000.toInt())
                g.miniatura?.let { if (File(it).exists()) setImageBitmap(BitmapFactory.decodeFile(it)) } }
            vh.v.addView(img, LinearLayout.LayoutParams(Ui.dp(c, 120), Ui.dp(c, 68)))
            val info = Ui.columna(c).apply { setPadding(Ui.dp(c, 10), 0, 0, 0) }
            info.addView(Ui.texto(c, (if (g.favorito) "★ " else "") + g.titulo + (if (g.recuperada) " · recuperada" else "") + (if (g.recorteDe != null) " · recorte" else ""), 15f, Ui.TEXTO, true))
            val f = runCatching { LocalDateTime.parse(g.creadoLocal).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")) }.getOrDefault(g.creadoLocal)
            info.addView(Ui.texto(c, "$f · ${Ui.duracion(g.duracion)} · ${Ui.tamano(g.tamano)}", 12f, Ui.SUTIL))
            val chips = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Teléfono" to "dispositivo", "NAS" to "nas", "Fotos JSE" to "fotosJse", "Galería" to "galeria").forEach { (n, k) ->
                if (k == "galeria" && g.destinos[k]?.estado == "no_configurado") return@forEach
                chips.addView(Ui.chip(c, n, g.destinos[k]), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(c, 4) })
            }
            info.addView(android.widget.HorizontalScrollView(c).apply { addView(chips) })
            val nas = g.destinos["nas"]!!
            if (nas.estado == "copiando") info.addView(ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = (nas.progreso * 1000).toInt() })
            if (nas.motivo != null && nas.estado != "guardado") info.addView(Ui.texto(c, nas.motivo!!, 12f, if (nas.estado == "requiere_atencion") Ui.MAL else Ui.SUTIL))
            vh.v.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            vh.v.setOnClickListener { startActivity(Intent(c, ReproductorActivity::class.java).putExtra("id", g.id)) }
        }
    }
}
