package mx.jse.captura

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant

/** Reproductor con marcas, edición de título/notas/etiquetas, compartir, recortar y borrado por ubicación. */
class ReproductorActivity : AppCompatActivity() {
    private lateinit var g: Grabacion
    private lateinit var video: VideoView
    private lateinit var marcas: LinearLayout
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        g = Biblioteca.obtener(intent.getStringExtra("id") ?: "") ?: return finish()
        val c = this
        val col = Ui.columna(c).apply { setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12)) }
        val titulo = EditText(c).apply { setText(g.titulo); textSize = 18f; setTextColor(Ui.TEXTO)
            setOnFocusChangeListener { _, f -> if (!f) guardar { g.titulo = text.toString().ifBlank { g.titulo } } } }
        col.addView(titulo)
        video = VideoView(c)
        if (g.archivo != null && File(g.archivo!!).exists()) {
            video.setVideoPath(g.archivo)
            video.setMediaController(MediaController(c).also { it.setAnchorView(video) })
            video.setOnPreparedListener { it.start() }
        }
        col.addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 260)))
        marcas = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(android.widget.HorizontalScrollView(c).apply { addView(marcas) })
        pintarMarcas()

        col.addView(Ui.fila(c,
            Ui.boton(c, "⚑ Marcar aquí") { guardar { g.marcas.add(Marca(video.currentPosition / 1000.0)); g.marcas.sortBy { it.t } }; pintarMarcas() },
            Ui.boton(c, if (g.favorito) "★" else "☆") { b -> guardar { g.favorito = !g.favorito }; (b as android.widget.Button).text = if (g.favorito) "★" else "☆" },
            Ui.boton(c, "Compartir") { compartir() },
            Ui.boton(c, "Abrir con…") { abrirCon() }))

        val notas = EditText(c).apply { hint = "Notas"; setText(g.notas); setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUTIL)
            setOnFocusChangeListener { _, f -> if (!f) guardar { g.notas = text.toString() } } }
        val etiquetas = EditText(c).apply { hint = "Etiquetas, separadas por coma"; setText(g.etiquetas.joinToString(", ")); setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUTIL)
            setOnFocusChangeListener { _, f -> if (!f) guardar { g.etiquetas = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList() } } }
        col.addView(notas); col.addView(etiquetas)

        // Recorte (segunda entrega): crea un MP4 nuevo; el original conserva su id y duración.
        val ini = EditText(c).apply { hint = "Inicio (s)"; setText("0"); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setTextColor(Ui.TEXTO) }
        val fin = EditText(c).apply { hint = "Fin (s)"; setText("%.1f".format(g.duracion)); inputType = ini.inputType; setTextColor(Ui.TEXTO) }
        col.addView(Ui.texto(c, "✂ Recortar (crea un MP4 nuevo)", 14f, Ui.SUTIL))
        col.addView(Ui.fila(c, ini, Ui.boton(c, "← aquí") { ini.setText("%.1f".format(video.currentPosition / 1000.0)) },
            fin, Ui.boton(c, "← aquí") { fin.setText("%.1f".format(video.currentPosition / 1000.0)) }))
        col.addView(Ui.fila(c,
            Ui.boton(c, "▶ Vista previa") { vistaPrevia(ini.text.toString().toDoubleOrNull() ?: 0.0, fin.text.toString().toDoubleOrNull() ?: g.duracion) },
            Ui.boton(c, "Guardar recorte") { recortar(ini.text.toString().toDoubleOrNull() ?: 0.0, fin.text.toString().toDoubleOrNull() ?: g.duracion) }))

        info = Ui.texto(c, "", 13f, Ui.SUTIL)
        col.addView(info)
        pintarInfo()
        col.addView(Ui.fila(c,
            Ui.boton(c, "Borrar del teléfono…") { borrarLocal() },
            Ui.boton(c, "Borrar del NAS…") { borrarNas() }))
        setContentView(Ui.conVersion(c, ScrollView(c).apply { addView(col) }))
    }

    private fun guardar(cambio: () -> Unit) {
        cambio()
        if (g.destinos["nas"]?.estado == "guardado") { g.metaPendiente = true; Sincronizar.ahora(this) }
        Biblioteca.guardar()
    }

    private fun pintarMarcas() {
        marcas.removeAllViews()
        g.marcas.forEach { m ->
            marcas.addView(Ui.boton(this, "⚑ ${Ui.duracion(m.t)}") { video.seekTo((m.t * 1000).toInt()); video.start() }.apply {
                setOnLongClickListener { guardar { g.marcas.remove(m) }; pintarMarcas(); true }
            })
        }
    }

    private fun pintarInfo() {
        val d = g.destinos
        info.text = buildString {
            append("${Ui.duracion(g.duracion)} · ${Ui.tamano(g.tamano)} · ${g.ancho}×${g.alto} · ${g.audio}\n${g.origen}\n")
            listOf("Teléfono" to "dispositivo", "NAS" to "nas", "Fotos JSE" to "fotosJse", "Galería" to "galeria").forEach { (n, k) ->
                val x = d[k] ?: return@forEach
                append("$n: ${Destino.TEXTO[x.estado] ?: x.estado}${x.ruta?.let { " · $it" } ?: ""}${x.motivo?.let { " — $it" } ?: ""}\n")
            }
        }
    }

    private fun uri() = FileProvider.getUriForFile(this, "mx.jse.captura.archivos", File(g.archivo!!))

    private fun compartir() {
        if (g.archivo == null) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir grabación"))
    }

    /** Abre el MP4 con otro reproductor (VLC, mpv-android, el de la galería…). */
    private fun abrirCon() {
        if (g.archivo == null) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri(), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Abrir con"))
    }

    private fun vistaPrevia(a: Double, b: Double) {
        video.seekTo((a * 1000).toInt()); video.start()
        video.postDelayed({ if (video.currentPosition >= b * 1000 - 300) video.pause() }, ((b - a) * 1000).toLong())
    }

    private fun recortar(a: Double, b: Double) {
        if (g.archivo == null || b <= a || a < 0 || b > g.duracion + 0.1) { info.text = "Rango de recorte inválido."; return }
        info.text = "Creando el recorte…"
        Thread {
            try {
                val id = Biblioteca.nuevoId()
                val destino = File(Biblioteca.videos, File(g.archivo!!).nameWithoutExtension + " recorte ${id.take(4)}.mp4")
                Medios.recortar(File(g.archivo!!), destino, a, b)
                val v = Medios.validar(destino)
                if (!v.valido) throw IllegalStateException(v.motivo)
                val n = Grabacion(id, "${g.titulo} (recorte)", Instant.now().toString(), Biblioteca.fechaLocal(), destino.path,
                    Medios.miniatura(destino, File(Biblioteca.miniaturas, "$id.jpg")), v.duracion, destino.length(), v.ancho, v.alto,
                    "Recorte de ${g.titulo}", g.audio, etiquetas = g.etiquetas.toMutableList(),
                    marcas = g.marcas.filter { it.t in a..b }.map { Marca(it.t - a, it.nota) }.toMutableList(), recorteDe = g.id)
                if (!Ajustes(this).nasConfigurado()) n.destinos["nas"] = Destino("no_configurado")
                Biblioteca.agregar(n)
                Sincronizar.ahora(this)
                runOnUiThread { info.text = "Listo: «${n.titulo}» (${Ui.duracion(n.duracion)}). El original no cambió. El corte empieza en el cuadro clave más cercano (≤1 s antes)." }
            } catch (e: Exception) {
                runOnUiThread { info.text = "No se pudo recortar: ${e.message}" }
            }
        }.start()
    }

    private fun borrarLocal() {
        val enNas = g.destinos["nas"]?.estado == "guardado"
        AlertDialog.Builder(this).setTitle("Borrar del teléfono")
            .setMessage(if (enNas) "Se borra solo la copia de este teléfono (${g.archivo}). La copia del NAS (${g.destinos["nas"]?.ruta}) y la de la galería no se tocan."
                else "¡Ojo! Todavía NO está en el NAS: esta es la única copia de JSE Captura. ¿Borrarla?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar del teléfono") { _, _ -> Biblioteca.borrarLocal(g.id); finish() }.show()
    }

    private fun borrarNas() {
        val aj = Ajustes(this)
        if (g.destinos["nas"]?.estado != "guardado" || !aj.nasConfigurado()) { info.text = "Esta grabación no está guardada en el NAS."; return }
        AlertDialog.Builder(this).setTitle("Borrar del NAS")
            .setMessage("Se borra ${g.destinos["nas"]?.ruta} del NAS. Si esa carpeta es la de Fotos JSE, también desaparece de la galería de Fotos JSE. La copia del teléfono no se toca.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar del NAS") { _, _ ->
                Thread {
                    val r = runCatching { ClienteNas(aj.direccion, aj.token).eliminar(g.id) }
                    runOnUiThread {
                        if (r.isSuccess) {
                            g.destinos["nas"] = Destino("no_configurado", motivo = "Borrado del NAS por ti. «Sincronizar ahora» lo vuelve a copiar.")
                            g.destinos["fotosJse"] = Destino("no_configurado"); Biblioteca.guardar(); pintarInfo()
                        } else info.text = "No se pudo borrar del NAS: ${r.exceptionOrNull()?.message}"
                    }
                }.start()
            }.show()
    }
}
