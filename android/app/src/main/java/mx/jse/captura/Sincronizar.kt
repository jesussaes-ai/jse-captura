package mx.jse.captura

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cola de sincronización persistente: el estado vive en la biblioteca (disco) y WorkManager reintenta
 * cuando hay red (y, si se pide, Wi-Fi), también después de reiniciar el teléfono.
 */
object Sincronizar {
    fun programar(ctx: Context) {
        val r = PeriodicWorkRequestBuilder<Trabajador>(15, TimeUnit.MINUTES).setConstraints(restricciones(ctx)).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("jse-sync-periodico", ExistingPeriodicWorkPolicy.UPDATE, r)
    }

    fun ahora(ctx: Context) {
        val r = OneTimeWorkRequestBuilder<Trabajador>().setConstraints(restricciones(ctx)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("jse-sync-ahora", ExistingWorkPolicy.REPLACE, r)
    }

    /** "Sincronizar ahora": también reintenta lo que requería atención. */
    fun forzar(ctx: Context) {
        Biblioteca.items.forEach { g ->
            g.destinos.forEach { (k, d) -> if (k != "dispositivo" && d.estado == "requiere_atencion") d.estado = "pendiente" }
            if (g.destinos["nas"]?.estado == "no_configurado" && Ajustes(ctx).nasConfigurado()) g.destinos["nas"] = Destino()
        }
        Biblioteca.guardar()
        ahora(ctx)
    }

    private fun restricciones(ctx: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (Ajustes(ctx).soloWifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    class Trabajador(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
        override suspend fun doWork(): Result {
            Biblioteca.iniciar(applicationContext)
            val aj = Ajustes(applicationContext)
            var reintentar = false
            for (g in Biblioteca.items.sortedBy { it.creado }) {
                if (isStopped) break
                if (aj.galeria && g.destinos["galeria"]?.estado in setOf("pendiente", "no_configurado") && g.archivo != null) galeria(g)
                if (g.archivo != null && g.destinos["nas"]?.estado == "pendiente" && aj.nasConfigurado()) if (!nas(g, aj)) reintentar = true
                if (g.destinos["nas"]?.estado == "guardado" && g.metaPendiente && aj.nasConfigurado()) meta(g, aj)
                if (g.destinos["nas"]?.estado == "guardado" && g.destinos["fotosJse"]?.estado == "exportado" && aj.nasConfigurado()) fotos(g, aj)
            }
            return if (reintentar) Result.retry() else Result.success()
        }

        private fun nas(g: Grabacion, aj: Ajustes): Boolean {
            val d = g.destinos["nas"]!!
            return try {
                d.estado = "copiando"; d.motivo = null; d.progreso = 0.0; Biblioteca.guardar()
                val f = File(g.archivo!!)
                if (!f.exists()) throw ErrorNas(0, "sin_archivo_local", "El MP4 ya no está en el teléfono.")
                if (g.sha256 == null) { g.sha256 = Medios.sha256(f); Biblioteca.guardar() }
                var ultimo = 0L
                val meta = JSONObject().put("id", g.id).put("sha256", g.sha256).put("titulo", g.titulo).put("carpeta", aj.carpeta)
                    .put("creado", g.creado).put("creadoLocal", g.creadoLocal).put("duracion", g.duracion)
                    .put("origen", JSONObject().put("plataforma", "android").put("equipo", "${Build.MANUFACTURER} ${Build.MODEL}").put("modo", g.origen))
                    .put("notas", g.notas).put("etiquetas", JSONArray(g.etiquetas)).put("favorito", g.favorito)
                    .put("marcas", JSONArray(g.marcas.map { JSONObject().put("t", it.t).put("nota", it.nota) })).put("recorteDe", g.recorteDe)
                val r = ClienteNas(aj.direccion, aj.token).subir(f, meta, { hecho, total ->
                    d.progreso = if (total > 0) hecho.toDouble() / total else 0.0
                    if (System.currentTimeMillis() - ultimo > 1000) { ultimo = System.currentTimeMillis(); Biblioteca.notificar() }
                }, { isStopped })
                val rem = r.optJSONObject("grabacion") ?: JSONObject()
                g.destinos["nas"] = Destino("guardado", 1.0, ruta = rem.optString("rutaRelativa"))
                g.destinos["fotosJse"] = Destino.de(rem.optJSONObject("fotosJse") ?: JSONObject().put("estado", "no_configurado"))
                Biblioteca.guardar()
                true
            } catch (e: ErrorNas) {
                d.intentos++
                d.estado = if (e.codigo == "cancelado") "pendiente" else if (e.requiereUsuario || e.codigo == "sin_archivo_local") "requiere_atencion" else "pendiente"
                d.motivo = e.message; d.codigo = e.codigo
                Biblioteca.guardar()
                d.estado == "requiere_atencion"
            }
        }

        private fun meta(g: Grabacion, aj: Ajustes) = runCatching {
            ClienteNas(aj.direccion, aj.token).editar(g.id, JSONObject().put("titulo", g.titulo).put("notas", g.notas)
                .put("etiquetas", JSONArray(g.etiquetas)).put("favorito", g.favorito)
                .put("marcas", JSONArray(g.marcas.map { JSONObject().put("t", it.t).put("nota", it.nota) })))
            g.metaPendiente = false; Biblioteca.guardar()
        }

        private fun fotos(g: Grabacion, aj: Ajustes) = runCatching {
            g.destinos["fotosJse"] = Destino.de(ClienteNas(aj.direccion, aj.token).comprobarFotosJse(g.id))
            Biblioteca.guardar()
        }

        /** Galería del teléfono: copia a la colección de videos de MediaStore y la publica al terminar de escribirla. */
        private fun galeria(g: Grabacion) {
            val d = g.destinos["galeria"]!!
            try {
                val cr = applicationContext.contentResolver
                val v = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, File(g.archivo!!).name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JSE Captura")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
                    ?: throw IllegalStateException("La galería no aceptó el video")
                cr.openOutputStream(uri)!!.use { o -> File(g.archivo!!).inputStream().use { it.copyTo(o, 1 shl 20) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                g.destinos["galeria"] = Destino("guardado", 1.0, ruta = "Movies/JSE Captura")
            } catch (e: Exception) {
                d.estado = "requiere_atencion"; d.motivo = "No se pudo guardar en la galería: ${e.message}"
            }
            Biblioteca.guardar()
        }
    }
}
