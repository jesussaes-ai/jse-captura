package mx.jse.captura

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Aparece al detener la grabación: pide el nombre del archivo y lo guarda (título + nombre del MP4).
 * Después empieza la copia al NAS, ya con el nombre correcto.
 */
class NombrarActivity : Activity() {
    companion object {
        fun intent(ctx: Context, id: String): Intent = Intent(ctx, NombrarActivity::class.java).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private var listo = false

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Ui.letraLimitada(base))

    // El botón flotante se esconde mientras escribes el nombre para no tapar «Guardar».
    override fun onStart() { super.onStart(); BurbujaService.escondida = true }
    override fun onStop() { BurbujaService.escondida = false; super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: return finish()
        val g = Biblioteca.obtener(id) ?: return finish()
        val c = this
        val campo = EditText(c).apply {
            // Vacío, con el nombre automático como sugerencia: el teclado de Samsung reemplazaba solo una palabra
            // del texto seleccionado y lo escrito quedaba pegado a la fecha («jesus2026-11-17 22_39»).
            setText(""); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE; hint = g.titulo
        }
        val caja = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(c, 20), Ui.dp(c, 4), Ui.dp(c, 20), 0)
            addView(Ui.marca(c, 11f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
            addView(TextView(c).apply { text = "${Ui.duracion(g.duracion)} · ${Ui.tamano(g.tamano)} · ${g.audio}"; textSize = 13f })
            addView(campo)
        }
        val d = AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("¿Cómo se llama esta grabación?")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ -> guardar(id, campo.text.toString(), false) }
            .setNeutralButton("Guardar y ver") { _, _ -> guardar(id, campo.text.toString(), true) }
            .setNegativeButton("Dejar así") { _, _ -> guardar(id, "", false) }
            .setOnCancelListener { guardar(id, "", false) }
            .create()
        campo.setOnEditorActionListener { _, a, ev ->
            val enter = a == EditorInfo.IME_ACTION_DONE || (ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN)
            if (enter) { guardar(id, campo.text.toString(), false); d.dismiss(); true } else false }
        d.setOnDismissListener { if (!listo) guardar(id, "", false) }
        // Un toque fuera de la ventana (fácil con el teclado abierto) ya no la cierra sin guardar el nombre.
        d.setCanceledOnTouchOutside(false)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
        campo.requestFocus()
        // Algunos teléfonos (Samsung) no abren el teclado solos en una ventana que viene de segundo plano.
        campo.postDelayed({
            campo.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(campo, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 350)
    }

    private fun guardar(id: String, titulo: String, ver: Boolean) {
        if (listo) return
        listo = true
        if (titulo.isNotBlank()) Biblioteca.renombrar(id, titulo)
        Sincronizar.ahora(this)
        if (ver) startActivity(Intent(this, PantallaCompletaActivity::class.java).putExtra("id", id))
        finish()
    }
}
