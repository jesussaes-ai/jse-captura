package mx.jse.captura

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.content.ComponentName
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Servicio en primer plano (tipo mediaProjection) que graba la pantalla.
 * Se detiene con: la notificación persistente, el mosaico de Ajustes rápidos, la app, o cuando el sistema
 * termina la captura (botón del sistema "Dejar de compartir", pantalla bloqueada en algunas versiones…).
 */
class CapturaService : Service() {
    companion object {
        const val ACCION_INICIAR = "iniciar"
        const val ACCION_DETENER = "detener"
        const val ACCION_MARCAR = "marcar"
        const val EXTRA_RESULTADO = "resultado"
        const val EXTRA_DATOS = "datos"
        private const val NOTI = 7

        @Volatile var activo = false; private set
        @Volatile var inicioMs = 0L; private set
        @Volatile var ultimoError: String? = null
        val oyentes = mutableListOf<() -> Unit>()
        private fun avisar() = Handler(Looper.getMainLooper()).post { oyentes.toList().forEach { it() } }

        fun detener(ctx: Context) = ctx.startService(Intent(ctx, CapturaService::class.java).setAction(ACCION_DETENER))
        fun marcar(ctx: Context) = ctx.startService(Intent(ctx, CapturaService::class.java).setAction(ACCION_MARCAR))
    }

    private var proyeccion: MediaProjection? = null
    private var pantalla: VirtualDisplay? = null
    private var cod: Codificador? = null
    private var sesion: File? = null
    private var id = ""
    private var creado = ""
    private var creadoLocal = ""
    private val marcas = mutableListOf<Marca>()
    private var deteniendo = false
    private var mic = false
    private var interno = false
    private val manejador = Handler(Looper.getMainLooper())
    private val vigilarEspacio = object : Runnable {
        override fun run() {
            if (!activo) return
            if (StatFs(Biblioteca.temporales.path).availableBytes < 300L * 1024 * 1024) {
                ultimoError = "El teléfono se quedó casi sin espacio: se detuvo la grabación para no perder nada."
                terminar()
            } else manejador.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_INICIAR -> iniciar(intent)
            ACCION_DETENER -> terminar()
            ACCION_MARCAR -> if (activo) {
                marcas.add(Marca((SystemClock.elapsedRealtime() - inicioMs) / 1000.0))
                guardarSesion()
                notificar("Momento marcado en ${Ui.duracion((SystemClock.elapsedRealtime() - inicioMs) / 1000.0)}")
            }
        }
        return START_NOT_STICKY
    }

    private fun iniciar(intent: Intent) {
        if (activo) return
        val aj = Ajustes(this)
        // Audio encendido por defecto, pero solo si Android dio el permiso (sin él, el tipo "micrófono" haría fallar el servicio).
        val permisoAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        mic = aj.microfono && permisoAudio
        interno = aj.audioInterno && permisoAudio
        // Primero el aviso persistente (obligatorio antes de obtener la proyección en Android 10+).
        val tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(NOTI, notificacion("Preparando…"), tipos)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val datos: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATOS, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATOS)
        val p = runCatching { mpm.getMediaProjection(intent.getIntExtra(EXTRA_RESULTADO, Activity.RESULT_CANCELED), datos!!) }.getOrNull()
        if (p == null) {
            ultimoError = "No se obtuvo el permiso de captura. Vuelve a intentarlo y acepta «Iniciar ahora»."
            pararServicio(); return
        }
        proyeccion = p
        p.registerCallback(object : MediaProjection.Callback() {
            // El sistema terminó la captura (el usuario tocó "Dejar de compartir", otra app tomó la pantalla…).
            override fun onStop() {
                if (activo && !deteniendo) {
                    ultimoError = "El sistema terminó la captura. Se guardó lo grabado; inicia otra sesión si quieres seguir."
                    terminar()
                }
            }
            // Android 14+: la app compartida cambió de tamaño o giró. El video conserva su tamaño (con bordes).
            override fun onCapturedContentResize(width: Int, height: Int) {}
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {}
        }, manejador)

        val m = resources.displayMetrics
        val real = (getSystemService(DisplayManager::class.java)).getDisplay(0)?.let { d ->
            val r = android.graphics.Point(); @Suppress("DEPRECATION") d.getRealSize(r); r
        } ?: android.graphics.Point(m.widthPixels, m.heightPixels)
        val (largo, corto) = if (aj.calidad == "720") 1280 to 720 else 1920 to 1080
        val (w, h) = Codificador.tamano(real.x, real.y, largo, corto)

        id = Biblioteca.nuevoId()
        creado = Instant.now().toString()
        creadoLocal = Biblioteca.fechaLocal()
        sesion = File(Biblioteca.temporales, id).apply { mkdirs() }
        if (StatFs(sesion!!.path).availableBytes < 1024L * 1024 * 1024) {
            ultimoError = "Queda menos de 1 GB libre en el teléfono. Libera espacio (puedes borrar del teléfono lo que ya está en el NAS)."
            pararServicio(); return
        }
        try {
            cod = Codificador(sesion!!, w, h, if (aj.calidad == "720") 4_000_000 else 8_000_000, p, mic, interno).also { it.iniciar() }
            pantalla = p.createVirtualDisplay("JSE Captura", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, cod!!.superficie, null, manejador)
        } catch (e: Exception) {
            ultimoError = "No se pudo iniciar el codificador de video: ${e.message}"
            cod?.runCatching { detener() }
            pararServicio(); return
        }
        activo = true
        inicioMs = SystemClock.elapsedRealtime()
        ultimoError = cod!!.avisoAudio
        guardarSesion()
        getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(null))
        manejador.postDelayed(vigilarEspacio, 5000)
        actualizarMosaico()
        avisar()
    }

    /** Datos de la sesión en disco: si el proceso muere, Recuperacion los usa al volver a abrir. */
    private fun guardarSesion() {
        val aj = Ajustes(this)
        val j = JSONObject().put("id", id).put("creado", creado).put("creadoLocal", creadoLocal)
            .put("titulo", "Grabación ${creadoLocal.replace('T', ' ').take(16)}")
            .put("origen", "Android · ${Build.MANUFACTURER} ${Build.MODEL} · pantalla completa")
            .put("audio", listOfNotNull(if (mic) "micrófono" else null, if (interno) "audio interno" else null).joinToString(" + ").ifEmpty { "sin audio" })
            .put("marcas", JSONArray(marcas.map { JSONObject().put("t", it.t).put("nota", it.nota) }))
        File(sesion, "sesion.json").writeText(j.toString())
    }

    private fun terminar() {
        if (!activo || deteniendo) { if (!activo) pararServicio(); return }
        deteniendo = true
        getSystemService(NotificationManager::class.java).notify(NOTI, notificacion("Guardando el MP4…"))
        val s = sesion!!
        val c = cod!!
        Thread {
            runCatching { pantalla?.release() }
            c.detener()
            runCatching { proyeccion?.stop() }
            activo = false
            val g = Recuperacion.finalizarSesion(this, s, recuperada = false)
            if (g != null) {
                Ui.avisoSistema(this, "Grabación guardada", "${g.titulo} · ${Ui.duracion(g.duracion)}")
                Sincronizar.ahora(this)
            } else ultimoError = ultimoError ?: "No se pudo guardar la grabación. Los segmentos quedaron en la carpeta Grabando."
            manejador.post { pararServicio() }
        }.start()
    }

    private fun pararServicio() {
        activo = false
        manejador.removeCallbacks(vigilarEspacio)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        actualizarMosaico()
        avisar()
        ultimoError?.let { Ui.avisoSistema(this, "JSE Captura", it) }
    }

    private fun actualizarMosaico() = runCatching { TileService.requestListeningState(this, ComponentName(this, MosaicoService::class.java)) }

    private fun pi(accion: String, cod: Int) = PendingIntent.getService(this, cod, Intent(this, CapturaService::class.java).setAction(accion), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notificacion(texto: String?): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, App.CANAL_GRABANDO)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(if (activo) "● Grabando la pantalla" else "JSE Captura")
            .setContentText(texto ?: "Toca Detener aquí o en el mosaico de Ajustes rápidos")
            .setOngoing(true)
            .setContentIntent(abrir)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (activo) {
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - inicioMs))
            b.addAction(Notification.Action.Builder(null, "■ Detener", pi(ACCION_DETENER, 1)).build())
            b.addAction(Notification.Action.Builder(null, "⚑ Marcar", pi(ACCION_MARCAR, 2)).build())
        }
        return b.build()
    }

    private fun notificar(texto: String) = getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(texto))

    // La rotación no reinicia nada: VirtualDisplay con AUTO_MIRROR ajusta el contenido al tamaño del video.
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
}
