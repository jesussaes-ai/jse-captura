package mx.jse.captura

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
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
    private lateinit var campoLink: EditText
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
        override fun run() { pintarEstado(); if (CapturaService.activo || CapturaService.preparado) h.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aj = Ajustes(this)
        val c = this
        val raiz = Ui.columna(c).apply { setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), 0) }
        // Todo lo de arriba va como encabezado de la lista: así la pantalla completa se desplaza junta
        // aunque el teléfono tenga la letra grande (antes la lista quedaba atorada abajo).
        val cab = Ui.columna(c)

        cab.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(Ui.texto(c, "● JSE Captura", 20f, Ui.ROJO, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.boton(c, "NAS") { startActivity(Intent(c, NasActivity::class.java)) }.apply { maxLines = 1 })
            addView(Ui.boton(c, "Ajustes") { startActivity(Intent(c, AjustesActivity::class.java)) }.apply { maxLines = 1 },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 8) })
        })

        // ---- grabar ----
        val calidad = RadioGroup(c).apply {
            orientation = RadioGroup.HORIZONTAL
            val r1080 = RadioButton(c).apply { id = View.generateViewId(); text = "Hasta 1080p"; setTextColor(Ui.TEXTO) }
            val r720 = RadioButton(c).apply { id = View.generateViewId(); text = "720p (ahorra espacio)"; setTextColor(Ui.TEXTO) }
            addView(r1080); addView(r720)
            check(if (aj.calidad == "720") r720.id else r1080.id)
            setOnCheckedChangeListener { _, id -> aj.calidad = if (id == r720.id) "720" else "1080"; pintarResumen() }
        }
        val mic = CheckBox(c).apply { text = "Micrófono (tu voz)"; setTextColor(Ui.TEXTO); isChecked = aj.microfono
            setOnCheckedChangeListener { _, v -> aj.microfono = v; if (v) pedirAudio(); pintarResumen() } }
        val interno = CheckBox(c).apply { text = "Audio del teléfono"; setTextColor(Ui.TEXTO); isChecked = aj.audioInterno
            setOnCheckedChangeListener { _, v -> aj.audioInterno = v; if (v) pedirAudio(); pintarResumen() } }
        val flotante = CheckBox(c).apply { text = "Botón flotante"; setTextColor(Ui.TEXTO); isChecked = aj.botonFlotante
            setOnCheckedChangeListener { _, v -> aj.botonFlotante = v; if (v && !Settings.canDrawOverlays(c)) pedirSobreApps(); pintarEstado(); pintarResumen() } }
        val nombre = CheckBox(c).apply { text = "Pedir nombre al terminar"; setTextColor(Ui.TEXTO); isChecked = aj.pedirNombre
            setOnCheckedChangeListener { _, v -> aj.pedirNombre = v } }
        resumen = Ui.texto(c, "", 13f, Ui.SUTIL)
        btnGrabar = Ui.boton(c, "● Grabar", true) { alternar() }.apply { textSize = 18f }
        estado = Ui.texto(c, "", 14f, Ui.TEXTO)
        cab.addView(calidad)
        cab.addView(interno); cab.addView(mic); cab.addView(flotante); cab.addView(nombre)
        cab.addView(resumen)
        cab.addView(btnGrabar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 8) })
        cab.addView(estado)

        // ---- grabar desde un link ----
        cab.addView(Ui.texto(c, "🔗 Grabar desde un link", 16f, Ui.TEXTO, true).apply { setPadding(0, Ui.dp(c, 14), 0, 0) })
        cab.addView(Ui.texto(c, "Pega o escribe el link (YouTube, un curso…). Se abre solo y la grabación empieza a los 3 segundos.", 13f, Ui.SUTIL))
        campoLink = EditText(c).apply {
            hint = "https://…"; setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUTIL); isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            contentDescription = "Link para grabar"
            setOnEditorActionListener { _, a, _ -> if (a == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { grabarLink(); true } else false }
        }
        cab.addView(campoLink)
        cab.addView(Ui.fila(c,
            Ui.boton(c, "📋 Pegar") { pegarLink() },
            Ui.boton(c, "▶ Abrir y grabar", true) { grabarLink() }))
        recibirLink(intent)

        // ---- biblioteca ----
        buscar = EditText(c).apply { hint = "Buscar por título, notas o etiquetas"; setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUTIL)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = refrescar()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            }) }
        fPend = CheckBox(c).apply { text = "Pendientes"; setTextColor(Ui.TEXTO); setOnCheckedChangeListener { _, _ -> refrescar() } }
        fFav = CheckBox(c).apply { text = "★ Favoritos"; setTextColor(Ui.TEXTO); setOnCheckedChangeListener { _, _ -> refrescar() } }
        cab.addView(buscar)
        cab.addView(Ui.fila(c, fPend, fFav, Ui.boton(c, "⟳ Sincronizar") { Sincronizar.forzar(c); estado.text = "Sincronizando…" }))
        vacio = Ui.texto(c, "Aún no hay grabaciones. Toca Grabar, o usa el mosaico «JSE Captura» de Ajustes rápidos para grabar desde cualquier app.", 14f, Ui.SUTIL)
        cab.addView(vacio)
        lista = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c)
            adapter = androidx.recyclerview.widget.ConcatAdapter(Cabecera(cab), adaptador) }
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(Ui.conVersion(c, raiz))

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        pintarResumen()
    }

    override fun onResume() {
        super.onResume()
        Biblioteca.alCambiar(oyenteBib)
        CapturaService.oyentes.add(oyenteCaptura)
        refrescar(); pintarEstado()
        if (CapturaService.activo || CapturaService.preparado) h.post(reloj)
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
            (if (aj.audioInterno) "\nAlgunas apps (Netflix, WhatsApp, llamadas) no permiten capturar su audio." else "") +
            (if (aj.microfono && aj.audioInterno) "\nCon micrófono y audio del teléfono a la vez: mejor con audífonos." else "") +
            (if (aj.botonFlotante) "\nPreparar → abre lo que quieras grabar → toca ● REC. Otra vez para detener." else "")
    }

    /** Un link compartido desde YouTube, el navegador u otra app («Compartir → JSE Captura») llena el campo. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recibirLink(intent)
    }

    private fun recibirLink(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Link.extraer(t)?.let { campoLink.setText(it); estado.text = "Link recibido. Toca «▶ Abrir y grabar»." }
    }

    private fun pegarLink() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val l = Link.extraer(t)
        if (l != null) campoLink.setText(l) else estado.text = "Lo copiado no tiene un link. Cópialo de nuevo (Compartir → Copiar enlace)."
    }

    private fun grabarLink() {
        if (CapturaService.activo || CapturaService.preparado) { estado.text = "Ya hay una grabación en curso."; return }
        val l = Link.extraer(campoLink.text.toString())
        if (l == null) { estado.text = "Escribe o pega un link (debe empezar con http:// o https://)."; return }
        CapturaService.ultimoError = null
        if ((aj.microfono || aj.audioInterno) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { pedirAudio(); return }
        irAlFondoAlPreparar = false
        startActivity(Intent(this, PermisoCapturaActivity::class.java).putExtra(CapturaService.EXTRA_LINK, l))
        h.postDelayed(reloj, 800)
    }

    private fun alternar() {
        if (CapturaService.activo || CapturaService.preparado) CapturaService.detener(this)
        else {
            CapturaService.ultimoError = null
            if ((aj.microfono || aj.audioInterno) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { pedirAudio(); return }
            if (aj.botonFlotante && !Settings.canDrawOverlays(this)) { pedirSobreApps(); return }
            irAlFondoAlPreparar = aj.botonFlotante
            startActivity(Intent(this, PermisoCapturaActivity::class.java))
            h.postDelayed(reloj, 800)
        }
    }

    /** El botón flotante necesita «Mostrar sobre otras apps»; Android solo lo deja activar desde sus Ajustes. */
    private fun pedirSobreApps() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Permitir el botón flotante")
            .setMessage("Para que el botón ● REC flote sobre YouTube y las demás apps, activa «Mostrar sobre otras apps» para JSE Captura en la pantalla que se abre y regresa.\n\nSin ese permiso puedes empezar desde la notificación («● Empezar»).")
            .setPositiveButton("Abrir permiso") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            }
            .setNegativeButton("Sin botón flotante") { _, _ ->
                aj.botonFlotante = false; recreate()
            }.show()
    }

    private var irAlFondoAlPreparar = false

    private fun pintarEstado() {
        if (CapturaService.preparado) {
            btnGrabar.text = "✕ Cancelar (aún no graba)"
            estado.text = "Listo. Abre lo que quieras grabar (YouTube, el curso…) y toca el botón flotante ● REC para empezar. " +
                "Tócalo otra vez para detener." + (CapturaService.ultimoError?.let { "\n⚠ $it" } ?: "")
            // Te lleva fuera de la app para que elijas qué grabar; aquí todavía no se graba nada.
            if (irAlFondoAlPreparar && hasWindowFocus()) { irAlFondoAlPreparar = false; moveTaskToBack(true) }
        } else if (CapturaService.activo) {
            btnGrabar.text = "■ Detener  " + Ui.duracion((SystemClock.elapsedRealtime() - CapturaService.inicioMs) / 1000.0)
            estado.text = "Grabando. Puedes cambiar de app: detén desde la notificación o el mosaico de Ajustes rápidos." + (CapturaService.ultimoError?.let { "\n⚠ $it" } ?: "")
        } else {
            btnGrabar.text = if (aj.botonFlotante) "● Preparar grabación" else "● Grabar"
            estado.text = CapturaService.ultimoError?.let { "⚠ $it" } ?: ""
        }
    }

    private fun refrescar() {
        val l = Biblioteca.listar(buscar.text.toString(), fPend.isChecked, fFav.isChecked)
        vacio.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
        adaptador.items = l
        adaptador.notifyDataSetChanged()
    }

    /** Encabezado fijo (controles de grabación y filtros) que se desplaza junto con la lista. */
    class Cabecera(private val v: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun getItemCount() = 1
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Ui.letraLimitada(base))

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
            // Tocar la miniatura abre el video a pantalla completa (tamaño original con «1:1» o pellizcando).
            if (g.archivo != null) img.setOnClickListener { startActivity(Intent(c, PantallaCompletaActivity::class.java).putExtra("id", g.id)) }
            img.contentDescription = "Ver «${g.titulo}» a pantalla completa"
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
