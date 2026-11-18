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
        const val ACCION_EMPEZAR = "empezar"
        const val ACCION_PAUSA = "pausa"
        const val EXTRA_RESULTADO = "resultado"
        const val EXTRA_DATOS = "datos"
        const val EXTRA_LINK = "link"
        /** Nombre de la app que se está grabando (YouTube, Facebook, Zoom…), para el título. */
        const val EXTRA_APP = "app"
        /** Segundos de cuenta regresiva antes de empezar (0 = de inmediato). */
        const val EXTRA_CUENTA = "cuenta"
        private const val NOTI = 7

        /** Grabando de verdad (se están escribiendo cuadros). */
        @Volatile var activo = false; private set
        /** Permiso aceptado y todo listo; corre la cuenta regresiva antes de empezar. */
        @Volatile var preparado = false; private set
        @Volatile var pausado = false; private set
        @Volatile var guardando = false; private set
        /** El permiso de captura sigue abierto: la siguiente grabación empieza sin preguntar. */
        @Volatile var enEspera = false; private set
        /** Cuenta regresiva que se muestra en el botón flotante (0 = ninguna). */
        @Volatile var cuenta = 0; private set
        @Volatile var inicioMs = 0L; private set
        @Volatile private var pausaAcumMs = 0L
        @Volatile private var pausaDesdeMs = 0L
        /** Tiempo grabado, sin contar las pausas. */
        fun transcurridoMs(): Long {
            val ahora = SystemClock.elapsedRealtime()
            return ahora - inicioMs - pausaAcumMs - (if (pausado) ahora - pausaDesdeMs else 0)
        }
        @Volatile var ultimoError: String? = null
        val oyentes = mutableListOf<() -> Unit>()
        private fun avisar() = Handler(Looper.getMainLooper()).post { oyentes.toList().forEach { it() } }

        fun detener(ctx: Context) = ctx.startService(Intent(ctx, CapturaService::class.java).setAction(ACCION_DETENER))
        fun empezar(ctx: Context) = ctx.startService(Intent(ctx, CapturaService::class.java).setAction(ACCION_EMPEZAR))
        fun marcar(ctx: Context) = ctx.startService(Intent(ctx, CapturaService::class.java).setAction(ACCION_MARCAR))
        /** Graba otra vez con el permiso que sigue abierto (sin diálogo). */
        fun grabarDeNuevo(ctx: Context, extras: Intent) = ctx.startService(Intent(ctx, CapturaService::class.java).setAction(ACCION_INICIAR).putExtras(extras))
        fun pausa(ctx: Context) = ctx.startService(Intent(ctx, CapturaService::class.java).setAction(ACCION_PAUSA))
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
    private var link: String? = null
    private var app: String? = null
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
        android.util.Log.i("JSECaptura", "acción=${intent?.action} activo=$activo preparado=$preparado")
        when (intent?.action) {
            ACCION_INICIAR -> iniciar(intent)
            ACCION_DETENER -> if (enEspera) apagar() else terminar()
            ACCION_EMPEZAR -> empezar()
            ACCION_PAUSA -> alternarPausa()
            ACCION_MARCAR -> if (activo) {
                marcas.add(Marca(transcurridoMs() / 1000.0))
                guardarSesion()
                notificar("Momento marcado en ${Ui.duracion(transcurridoMs() / 1000.0)}")
            }
        }
        return START_NOT_STICKY
    }

    private var anchoV = 0
    private var altoV = 0

    private fun tiposServicio() = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
        (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)

    private fun leerAudio() {
        val aj = Ajustes(this)
        // Audio encendido por defecto, pero solo si Android dio el permiso (sin él, el tipo "micrófono" haría fallar el servicio).
        val permisoAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        mic = aj.microfono && permisoAudio
        interno = aj.audioInterno && permisoAudio
    }

    private fun iniciar(intent: Intent) {
        if (activo || preparado) return
        if (enEspera) { nuevaSesion(intent); return }
        leerAudio()
        // Primero el aviso persistente (obligatorio antes de obtener la proyección en Android 10+).
        startForeground(NOTI, notificacion("Preparando…"), tiposServicio())

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val datos: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATOS, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATOS)
        val p = runCatching { mpm.getMediaProjection(intent.getIntExtra(EXTRA_RESULTADO, Activity.RESULT_CANCELED), datos!!) }.getOrNull()
        if (p == null) {
            ultimoError = "No se obtuvo el permiso de captura. Vuelve a intentarlo y acepta «Iniciar ahora»."
            pararServicio(); return
        }
        proyeccion = p
        p.registerCallback(object : MediaProjection.Callback() {
            // El sistema terminó la captura (el usuario tocó "Dejar de compartir", bloqueo de pantalla…).
            override fun onStop() {
                android.util.Log.i("JSECaptura", "la proyección terminó activo=$activo espera=$enEspera")
                proyeccion = null
                if (activo && !deteniendo) {
                    ultimoError = "El sistema terminó la captura. Se guardó lo grabado; toca ● REC para seguir."
                    terminar()
                } else if (enEspera) apagar()
            }
            // Android 14+: la app compartida cambió de tamaño o giró. El video conserva su tamaño (con bordes).
            override fun onCapturedContentResize(width: Int, height: Int) {}
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {}
        }, manejador)

        val m = resources.displayMetrics
        val real = (getSystemService(DisplayManager::class.java)).getDisplay(0)?.let { d ->
            val r = android.graphics.Point(); @Suppress("DEPRECATION") d.getRealSize(r); r
        } ?: android.graphics.Point(m.widthPixels, m.heightPixels)
        val (largo, corto) = if (Ajustes(this).calidad == "720") 1280 to 720 else 1920 to 1080
        val (w, h) = Codificador.tamano(real.x, real.y, largo, corto)
        anchoV = w; altoV = h
        nuevaSesion(intent)
    }

    /**
     * Una grabación nueva sobre el permiso ya aceptado. Después de la primera, el permiso se queda abierto
     * (en espera, sin grabar nada) y las siguientes empiezan con un toque, sin volver a preguntar.
     */
    private fun nuevaSesion(intent: Intent) {
        val p = proyeccion ?: run { pararServicio(); return }
        val aj = Ajustes(this)
        if (enEspera) {
            leerAudio()
            runCatching { startForeground(NOTI, notificacion("Preparando…"), tiposServicio()) }
                .onFailure { mic = false; runCatching { startForeground(NOTI, notificacion("Preparando…"), tiposServicio()) } }
            enEspera = false
        }
        deteniendo = false
        marcas.clear()
        id = Biblioteca.nuevoId()
        creado = Instant.now().toString()
        creadoLocal = Biblioteca.fechaLocal()
        sesion = File(Biblioteca.temporales, id).apply { mkdirs() }
        if (StatFs(sesion!!.path).availableBytes < 1024L * 1024 * 1024) {
            ultimoError = "Queda menos de 1 GB libre en el teléfono. Libera espacio (puedes borrar del teléfono lo que ya está en el NAS)."
            sesion?.deleteRecursively(); apagar(); return
        }
        try {
            cod = Codificador(sesion!!, anchoV, altoV, if (aj.calidad == "720") 4_000_000 else 8_000_000, p, mic, interno).also { it.iniciar() }
            val d = pantalla
            if (d == null) pantalla = p.createVirtualDisplay("JSE Captura", anchoV, altoV, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, cod!!.superficie, null, manejador)
            else d.surface = cod!!.superficie
        } catch (e: Exception) {
            ultimoError = "No se pudo iniciar el codificador de video: ${e.message}"
            cod?.runCatching { detener() }
            sesion?.deleteRecursively()
            apagar(); return
        }
        ultimoError = cod!!.avisoAudio
        link = intent.getStringExtra(EXTRA_LINK)
        app = intent.getStringExtra(EXTRA_APP)
        preparado = true
        android.util.Log.i("JSECaptura", "preparado ${anchoV}x$altoV mic=$mic interno=$interno app=$app")
        // El botón flotante sigue a la vista aunque se haya empezado desde el mosaico o la app.
        BurbujaService.mostrar(this)
        val segundos = if (link != null) 3 else intent.getIntExtra(EXTRA_CUENTA, 0)
        if (segundos > 0) {
            // Cuenta regresiva en el botón flotante (link: la app del link se abre mientras tanto).
            getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(
                if (link != null) "Abriendo el link… empieza a grabar en $segundos s" else "Empieza a grabar en $segundos s"))
            actualizarMosaico()
            for (n in segundos downTo 1) manejador.postDelayed({ if (preparado) { cuenta = n; avisar() } }, (segundos - n) * 1000L)
            manejador.postDelayed({ cuenta = 0; if (preparado) empezar() }, segundos * 1000L)
        } else empezar()
    }

    /** Queda listo para la siguiente grabación sin volver a pedir permiso (no se graba nada mientras tanto). */
    private fun quedarEnEspera() {
        if (proyeccion == null) { pararServicio(); return }
        activo = false; preparado = false; pausado = false; guardando = false; cuenta = 0
        deteniendo = false
        cod = null; sesion = null
        enEspera = true
        manejador.removeCallbacks(vigilarEspacio)
        getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(null))
        actualizarMosaico()
        avisar()
        ultimoError?.let { Ui.avisoSistema(this, "JSE Captura", it) }
    }

    /** Suelta el permiso de captura y cierra el servicio. */
    private fun apagar() {
        enEspera = false
        runCatching { pantalla?.release() }; pantalla = null
        runCatching { proyeccion?.stop() }; proyeccion = null
        pararServicio()
    }

    /** Empieza a escribir el video desde este instante. */
    private fun empezar() {
        android.util.Log.i("JSECaptura", "empezar preparado=$preparado activo=$activo")
        if (!preparado || activo) return
        cod?.empezar()
        preparado = false
        activo = true
        inicioMs = SystemClock.elapsedRealtime()
        pausaAcumMs = 0; pausado = false
        guardarSesion()
        getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(null))
        manejador.postDelayed(vigilarEspacio, 5000)
        actualizarMosaico()
        avisar()
    }

    /** ❚❚ / ▶: la pausa no queda en el video; al seguir, la grabación continúa en el mismo MP4. */
    private fun alternarPausa() {
        if (!activo || deteniendo) return
        val c = cod ?: return
        if (!pausado) {
            c.pausar(); pausaDesdeMs = SystemClock.elapsedRealtime(); pausado = true
        } else {
            c.reanudar(); pausaAcumMs += SystemClock.elapsedRealtime() - pausaDesdeMs; pausado = false
        }
        android.util.Log.i("JSECaptura", "pausado=$pausado")
        getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(null))
        avisar()
    }

    /** Se aceptó el permiso pero nunca se empezó: se libera todo y no queda ninguna grabación. */
    private fun cancelar() {
        preparado = false
        deteniendo = true
        val s = sesion
        val c = cod
        Thread {
            runCatching { pantalla?.surface = null }
            runCatching { c?.detener() }
            s?.deleteRecursively()
            manejador.post { quedarEnEspera() }
        }.start()
    }

    /** Datos de la sesión en disco: si el proceso muere, Recuperacion los usa al volver a abrir. */
    private fun guardarSesion() {
        val j = JSONObject().put("id", id).put("creado", creado).put("creadoLocal", creadoLocal)
            .put("titulo", (link?.let { Link.sitio(it) + " " } ?: app?.let { "$it " } ?: "Grabación ") + creadoLocal.replace('T', ' ').take(16))
            .put("origen", "Android · ${Build.MANUFACTURER} ${Build.MODEL} · pantalla completa" + (app?.let { " · $it" } ?: "") + (link?.let { " · $it" } ?: ""))
            .put("audio", listOfNotNull(if (mic) "micrófono" else null, if (interno) "audio interno" else null).joinToString(" + ").ifEmpty { "sin audio" })
            .put("marcas", JSONArray(marcas.map { JSONObject().put("t", it.t).put("nota", it.nota) }))
        File(sesion, "sesion.json").writeText(j.toString())
    }

    private fun terminar() {
        android.util.Log.i("JSECaptura", "terminar activo=$activo preparado=$preparado deteniendo=$deteniendo")
        if (preparado && !activo && !deteniendo) { cancelar(); return }
        if (!activo || deteniendo) { if (!activo && !deteniendo) pararServicio(); return }
        deteniendo = true
        guardando = true
        avisar()
        getSystemService(NotificationManager::class.java).notify(NOTI, notificacion("Guardando el MP4…"))
        val s = sesion!!
        val c = cod!!
        Thread {
            // El permiso no se suelta: la pantalla virtual se queda sin destino (no graba) hasta el siguiente ● REC.
            runCatching { pantalla?.surface = null }
            c.detener()
            activo = false
            pausado = false
            val g = Recuperacion.finalizarSesion(this, s, recuperada = false)
            android.util.Log.i("JSECaptura", "finalizada=${g?.id} segmentos=${c.segmentos.map { it.name + ":" + it.length() }}")
            if (g != null) {
                val nombrar = NombrarActivity.intent(this, g.id)
                Ui.avisoSistema(this, "Grabación guardada", "${g.titulo} · ${Ui.duracion(g.duracion)} · toca para ponerle nombre",
                    PendingIntent.getActivity(this, g.id.hashCode(), nombrar, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                // Se pide el nombre antes de copiar al NAS. El botón flotante sigue visible, lo que permite a
                // Android 15 abrir la ventana desde segundo plano.
                val pidio = Ajustes(this).pedirNombre && runCatching { startActivity(nombrar) }.onFailure { android.util.Log.w("JSECaptura", "nombrar", it) }.isSuccess
                if (!pidio) Sincronizar.ahora(this)
            } else ultimoError = ultimoError ?: "No se pudo guardar la grabación. Los segmentos quedaron en la carpeta Grabando."
            manejador.postDelayed({ quedarEnEspera() }, 600)
        }.start()
    }

    private fun pararServicio() {
        enEspera = false
        runCatching { pantalla?.release() }; pantalla = null
        runCatching { proyeccion?.stop() }; proyeccion = null
        activo = false
        preparado = false
        pausado = false
        guardando = false
        cuenta = 0
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
            .setContentTitle(if (enEspera) "● REC listo" else if (activo && pausado) "❚❚ Grabación en pausa" else if (activo) "● Grabando la pantalla" else if (preparado) "Por empezar a grabar" else "JSE Captura")
            .setContentText(texto ?: if (enEspera) "Toca ● REC en cualquier app: graba sin volver a pedir permiso" else if (activo && pausado) "Pausa en ${Ui.duracion(transcurridoMs() / 1000.0)} · toca ▶ Seguir para continuar en el mismo video"
                else if (preparado) "Empieza en unos segundos" else "❚❚ pausa · ■ termina (aquí, en el botón flotante o en el mosaico)")
            .setOngoing(true)
            .setContentIntent(abrir)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (activo) {
            if (!pausado) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - transcurridoMs())
            b.addAction(Notification.Action.Builder(null, if (pausado) "▶ Seguir" else "❚❚ Pausa", pi(ACCION_PAUSA, 4)).build())
            b.addAction(Notification.Action.Builder(null, "■ Detener", pi(ACCION_DETENER, 1)).build())
            if (!pausado) b.addAction(Notification.Action.Builder(null, "⚑ Marcar", pi(ACCION_MARCAR, 2)).build())
        } else if (enEspera) {
            b.addAction(Notification.Action.Builder(null, "Apagar", pi(ACCION_DETENER, 1)).build())
        } else if (preparado) {
            b.addAction(Notification.Action.Builder(null, "● Empezar", pi(ACCION_EMPEZAR, 3)).build())
            b.addAction(Notification.Action.Builder(null, "✕ Cancelar", pi(ACCION_DETENER, 1)).build())
        }
        return b.build()
    }

    private fun notificar(texto: String) = getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(texto))

    // La rotación no reinicia nada: VirtualDisplay con AUTO_MIRROR ajusta el contenido al tamaño del video.
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
}
