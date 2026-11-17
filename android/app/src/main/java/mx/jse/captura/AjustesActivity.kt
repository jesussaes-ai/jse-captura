package mx.jse.captura

import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity

/** NAS (dirección, vincular con la cuenta de servicio, carpeta, probar conexión) y destinos. */
class AjustesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val aj = Ajustes(c)
        val col = Ui.columna(c).apply { setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16)) }
        fun campo(hint: String, valor: String, clave: Boolean = false) = EditText(c).apply {
            this.hint = hint; setText(valor); setTextColor(Ui.TEXTO); setHintTextColor(Ui.SUTIL)
            if (clave) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(Ui.texto(c, "NAS UGREEN", 18f, Ui.TEXTO, true))
        val dir = campo("Dirección local: 192.168.1.50:8790 o https://nas.tu-tailnet.ts.net:8790", aj.direccion)
        val usu = campo("Usuario de servicio", aj.usuario)
        val con = campo("Contraseña (solo para vincular; no se guarda)", "", true)
        val estado = Ui.texto(c, if (aj.token != null) "✓ Teléfono vinculado (token cifrado en el Keystore)" else "Sin vincular", 13f, Ui.SUTIL)
        val carpeta = Spinner(c)
        val carpetas = mutableListOf(aj.carpeta)
        fun pintarCarpetas() {
            carpeta.adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, carpetas.distinct().map { it.ifEmpty { "(carpeta principal)" } })
            carpeta.setSelection(carpetas.distinct().indexOf(aj.carpeta).coerceAtLeast(0))
        }
        pintarCarpetas()
        col.addView(dir); col.addView(usu); col.addView(con)
        col.addView(Ui.fila(c,
            Ui.boton(c, "Vincular", true) {
                estado.text = "Vinculando…"
                val d = dir.text.toString().trim(); val u = usu.text.toString().trim(); val p = con.text.toString()
                Thread {
                    val r = runCatching {
                        val cli = ClienteNas(d); cli.salud()
                        cli.iniciarSesion(u, p, "${Build.MANUFACTURER} ${Build.MODEL} (Android)")
                    }
                    runOnUiThread {
                        r.onSuccess { j ->
                            aj.direccion = d; aj.usuario = u; aj.token = j.getString("token"); con.setText("")
                            estado.text = "✓ Vinculado como «${j.optString("equipo")}». Las grabaciones pendientes se empiezan a copiar."
                            Sincronizar.forzar(c)
                        }.onFailure { estado.text = "✗ ${it.message}" }
                    }
                }.start()
            },
            Ui.boton(c, "Desvincular") {
                Thread { runCatching { ClienteNas(aj.direccion, aj.token).json("DELETE", "/api/v1/sesion") } }.start()
                aj.token = null; estado.text = "Sin vincular"
            }))
        col.addView(estado)
        col.addView(Ui.texto(c, "Carpeta de destino en el NAS", 13f, Ui.SUTIL))
        col.addView(carpeta)
        val prueba = Ui.texto(c, "", 13f, Ui.SUTIL)
        col.addView(Ui.fila(c,
            Ui.boton(c, "Cargar carpetas") {
                Thread { val r = runCatching { ClienteNas(aj.direccion, aj.token).carpetas().getJSONArray("carpetas") }
                    runOnUiThread { r.onSuccess { a -> for (i in 0 until a.length()) carpetas.add(a.getString(i)); pintarCarpetas() }.onFailure { prueba.text = "✗ ${it.message}" } } }.start()
            },
            Ui.boton(c, "Probar conexión") {
                prueba.text = "Probando…"
                Thread {
                    val r = runCatching { ClienteNas(aj.direccion, aj.token).prueba() }
                    runOnUiThread {
                        r.onSuccess { j ->
                            val f = j.optJSONObject("fotosJse")
                            prueba.text = if (j.optBoolean("ok")) "✓ Conexión correcta · ${Ui.tamano(j.optLong("espacioLibre"))} libres en el NAS" +
                                (f?.let { " · Fotos JSE: " + when { it.isNull("alcanzable") -> "sin comprobar"; it.optBoolean("alcanzable") -> "responde"; else -> "no responde" } } ?: "")
                            else "✗ ${j.optString("motivo")}"
                        }.onFailure { prueba.text = "✗ ${it.message}" }
                    }
                }.start()
            }))
        val nueva = campo("Nueva carpeta (opcional)", "")
        col.addView(nueva)
        col.addView(prueba)

        col.addView(Ui.texto(c, "\nDestinos", 18f, Ui.TEXTO, true))
        val galeria = CheckBox(c).apply { text = "Guardar también en la galería del teléfono (Movies/JSE Captura)"; setTextColor(Ui.TEXTO); isChecked = aj.galeria }
        val wifi = CheckBox(c).apply { text = "Copiar al NAS solo con Wi-Fi"; setTextColor(Ui.TEXTO); isChecked = aj.soloWifi }
        col.addView(galeria); col.addView(wifi)
        col.addView(Ui.boton(c, "Guardar", true) {
            aj.carpeta = nueva.text.toString().trim().ifEmpty { carpetas.distinct().getOrNull(carpeta.selectedItemPosition) ?: "" }
            aj.galeria = galeria.isChecked
            aj.soloWifi = wifi.isChecked
            if (aj.galeria) { Biblioteca.items.forEach { if (it.destinos["galeria"]?.estado == "no_configurado") it.destinos["galeria"] = Destino("pendiente") }; Biblioteca.guardar() }
            Sincronizar.programar(c); Sincronizar.ahora(c)
            finish()
        })
        col.addView(Ui.texto(c, "\nJSE Captura ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}", 12f, Ui.SUTIL))
        setContentView(Ui.conVersion(c, ScrollView(c).apply { addView(col) }))
    }
}
