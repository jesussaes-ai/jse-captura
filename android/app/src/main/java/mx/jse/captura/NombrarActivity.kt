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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: return finish()
        val g = Biblioteca.obtener(id) ?: return finish()
        val c = this
        val campo = EditText(c).apply {
            setText(g.titulo); setSelectAllOnFocus(true); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE; hint = "Nombre del archivo"
        }
        val caja = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(c, 20), Ui.dp(c, 4), Ui.dp(c, 20), 0)
            addView(TextView(c).apply { text = "v${BuildConfig.VERSION_NAME}"; textSize = 11f; setTextColor(Ui.SUTIL); gravity = Gravity.END; tag = "version" },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        campo.requestFocus()
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
