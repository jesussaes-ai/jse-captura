package mx.jse.captura

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/** Pide el consentimiento de captura del sistema (uno por sesión, como exige Android 14+) e inicia el servicio. */
class PermisoCapturaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: el diálogo del sistema deja elegir pantalla completa o una sola app.
        val i = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()) else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(i, 1)
    }

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val link = intent.getStringExtra(CapturaService.EXTRA_LINK)
            startForegroundService(Intent(this, CapturaService::class.java).setAction(CapturaService.ACCION_INICIAR)
                .putExtra(CapturaService.EXTRA_RESULTADO, resultCode).putExtra(CapturaService.EXTRA_DATOS, data)
                .putExtra(CapturaService.EXTRA_LINK, link))
            // Grabar desde un link: se abre en su app (YouTube, navegador…) mientras esta ventana sigue al frente.
            if (link != null) runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                CapturaService.ultimoError = "Ninguna app del teléfono puede abrir ese link; no se grabó nada."
                CapturaService.detener(this)
            }
        } else {
            CapturaService.ultimoError = "Cancelaste el permiso de captura; no se empezó a grabar."
        }
        finish()
    }
}
