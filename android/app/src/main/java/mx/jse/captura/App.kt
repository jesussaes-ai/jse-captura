package mx.jse.captura

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_GRABANDO, "Grabando", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Aviso persistente mientras se graba la pantalla"
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_AVISOS, "Avisos", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Grabación guardada, copias al NAS y errores"
        })
        Biblioteca.iniciar(this)
        // Grabaciones que quedaron a medias por un cierre inesperado.
        Thread { Recuperacion.recuperar(this) }.start()
        Sincronizar.programar(this)
    }

    companion object {
        const val CANAL_GRABANDO = "grabando"
        const val CANAL_AVISOS = "avisos"
    }
}
