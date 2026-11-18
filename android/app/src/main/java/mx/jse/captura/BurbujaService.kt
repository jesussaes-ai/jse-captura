package mx.jse.captura

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings

/**
 * Mantiene el botón flotante ● REC siempre a la vista, en cualquier app, desde que abres JSE Captura.
 * Un toque graba lo que haya en pantalla (YouTube, Facebook, Zoom, un reproductor…); mientras graba, el mismo
 * botón da pausa / seguir y terminar. El archivo toma el nombre de la app que estaba al frente.
 */
class BurbujaService : Service() {
    companion object {
        private const val NOTI = 8
        private const val QUITAR = "quitar"

        fun mostrar(ctx: Context) {
            if (!Ajustes(ctx).botonFlotante || !Settings.canDrawOverlays(ctx)) return
            runCatching { ctx.startForegroundService(Intent(ctx, BurbujaService::class.java)) }
                .onFailure { android.util.Log.w("JSECaptura", "burbuja", it) }
        }

        fun quitar(ctx: Context) = ctx.stopService(Intent(ctx, BurbujaService::class.java))

        fun puedeVerApps(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            val m = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            return m == AppOpsManager.MODE_ALLOWED
        }

        /** Nombre de la app que el usuario estaba viendo (YouTube, Facebook, Zoom…), o null si no se sabe. */
        fun appAlFrente(ctx: Context): String? = runCatching {
            if (!puedeVerApps(ctx)) return null
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val ahora = System.currentTimeMillis()
            val ev = usm.queryEvents(ahora - 10 * 60_000L, ahora)
            val pm = ctx.packageManager
            val inicio = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
            val ignorar = setOf(ctx.packageName, "com.android.systemui", inicio)
            var ultimo: String? = null
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) ultimo = e.packageName
            }
            val p = ultimo?.takeIf { it !in ignorar } ?: return null
            pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString().trim().ifEmpty { null }
        }.getOrNull()
    }

    private var burbuja: Burbuja? = null
    private val oyente: () -> Unit = { pintar() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == QUITAR) {
            Ajustes(this).botonFlotante = false
            stopSelf(); return START_NOT_STICKY
        }
        val n = notificacion()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTI, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTI, n)
        if (burbuja == null) {
            burbuja = Burbuja(this, ::tocar)
            if (!burbuja!!.mostrar()) { burbuja = null; stopSelf(); return START_NOT_STICKY }
            CapturaService.oyentes.add(oyente)
        }
        pintar()
        return START_STICKY
    }

    private fun tocar(boton: Int) {
        when {
            CapturaService.guardando -> {}
            CapturaService.activo && boton == Burbuja.DETENER -> CapturaService.detener(this)
            CapturaService.activo -> CapturaService.pausa(this)
            CapturaService.preparado -> CapturaService.empezar(this)
            else -> {
                // Se mira qué app está al frente ANTES de que salga el diálogo del sistema.
                val app = appAlFrente(this)
                android.util.Log.i("JSECaptura", "grabar desde la burbuja; app al frente=$app")
                runCatching {
                    startActivity(Intent(this, PermisoCapturaActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(CapturaService.EXTRA_APP, app))
                }.onFailure { android.util.Log.w("JSECaptura", "permiso", it) }
            }
        }
    }

    private fun pintar() {
        val b = burbuja ?: return
        when {
            CapturaService.guardando -> b.modoGuardando()
            CapturaService.activo -> b.modoGrabando(CapturaService.pausado)
            CapturaService.preparado && CapturaService.cuenta > 0 -> b.cuenta(CapturaService.cuenta)
            else -> b.modoListo()
        }
    }

    private fun notificacion(): Notification {
        val abrir = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val quitar = PendingIntent.getService(this, 11, Intent(this, BurbujaService::class.java).setAction(QUITAR), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, App.CANAL_BOTON)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle("Botón ● REC listo")
            .setContentText("Ve a cualquier app y toca ● REC para grabar")
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Quitar botón", quitar).build())
            .build()
    }

    override fun onDestroy() {
        CapturaService.oyentes.remove(oyente)
        burbuja?.quitar(); burbuja = null
        super.onDestroy()
    }
}
