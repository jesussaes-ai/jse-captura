package mx.jse.captura

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** Grabaciones que ya están en el NAS (de este y otros equipos). Se reproducen sin descargarlas completas. */
class NasActivity : AppCompatActivity() {
    private lateinit var lista: LinearLayout
    private var pagina = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val col = Ui.columna(c).apply { setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16)) }
        val q = EditText(c).apply { hint = "Buscar en el NAS"; setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUTIL) }
        col.addView(Ui.texto(c, "En el NAS", 18f, Ui.TEXTO, true))
        col.addView(q)
        col.addView(Ui.fila(c, Ui.boton(c, "Buscar") { pagina = 1; cargar(q.text.toString()) },
            Ui.boton(c, "‹") { if (pagina > 1) { pagina--; cargar(q.text.toString()) } },
            Ui.boton(c, "›") { pagina++; cargar(q.text.toString()) }))
        lista = Ui.columna(c)
        col.addView(lista)
        setContentView(ScrollView(c).apply { addView(col) })
        cargar("")
    }

    private fun cargar(q: String) {
        val aj = Ajustes(this)
        lista.removeAllViews()
        if (!aj.nasConfigurado()) { lista.addView(Ui.texto(this, "Vincula el NAS en Ajustes para ver sus grabaciones.", 14f, Ui.SUTIL)); return }
        Thread {
            val r = runCatching { ClienteNas(aj.direccion, aj.token).listar(q, pagina) }
            runOnUiThread {
                r.onFailure { lista.addView(Ui.texto(this, "No se pudo consultar el NAS: ${it.message}", 14f, Ui.MAL)) }
                r.onSuccess { j ->
                    lista.addView(Ui.texto(this, "Página ${j.optInt("pagina")} · ${j.optInt("total")} en el NAS", 12f, Ui.SUTIL))
                    val a = j.getJSONArray("elementos")
                    for (i in 0 until a.length()) fila(a.getJSONObject(i), aj)
                }
            }
        }.start()
    }

    private fun fila(g: JSONObject, aj: Ajustes) {
        val local = Biblioteca.obtener(g.getString("id")) != null
        val o = g.optJSONObject("origen")
        val c = Ui.columna(this).apply { background = Ui.fondo(Ui.PANEL); setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8)) }
        c.addView(Ui.texto(this, (if (g.optBoolean("favorito")) "★ " else "") + g.optString("titulo"), 15f, Ui.TEXTO, true))
        c.addView(Ui.texto(this, "${g.optString("creadoLocal", g.optString("creado")).replace('T', ' ').take(16)} · ${Ui.duracion(g.optDouble("duracion"))} · ${Ui.tamano(g.optLong("tamano"))} · ${o?.optString("plataforma") ?: ""} ${o?.optString("equipo") ?: ""}", 12f, Ui.SUTIL))
        c.addView(Ui.fila(this, Ui.chip(this, "NAS", Destino("guardado")), Ui.chip(this, "Fotos JSE", Destino.de(g.optJSONObject("fotosJse"))),
            Ui.texto(this, if (local) "También en el teléfono" else "Solo en el NAS", 12f, Ui.SUTIL)))
        c.addView(Ui.boton(this, "▶ Reproducir (sin descargar)") {
            Thread {
                val u = runCatching { ClienteNas(aj.direccion, aj.token).enlace(g.getString("id")) }
                runOnUiThread {
                    u.onSuccess { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it), "video/mp4"), "Reproducir con")) }
                }
            }.start()
        })
        lista.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(this@NasActivity, 8) })
    }
}
