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
        nm.createNotificationChannel(NotificationChannel(CANAL_BOTON, "Botón ● REC", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Aviso discreto mientras el botón flotante está a la vista"
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_AVISOS, "Avisos", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Grabación guardada, copias al NAS y errores"
        })
        Ajustes(this).migrar()
        Biblioteca.iniciar(this)
        // Grabaciones que quedaron a medias por un cierre inesperado.
        Thread {
            // Videos que están en el teléfono pero no en la lista: se vuelven a mostrar.
            runCatching { Biblioteca.reconstruir(this) }
            Recuperacion.recuperar(this)
        }.start()
        Sincronizar.programar(this)
    }

    companion object {
        const val CANAL_GRABANDO = "grabando"
        const val CANAL_AVISOS = "avisos"
        const val CANAL_BOTON = "boton"
    }
}
