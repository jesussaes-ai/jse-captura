package mx.jse.captura

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Convierte una sesión de segmentos en una grabación de la biblioteca (al detener o tras un cierre inesperado). */
object Recuperacion {
    fun finalizarSesion(ctx: Context, dir: File, recuperada: Boolean): Grabacion? {
        val s = runCatching { JSONObject(File(dir, "sesion.json").readText()) }.getOrNull() ?: return null
        val listos = File(dir, "listos.txt").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        // Tras un cierre inesperado solo sirven los segmentos que se cerraron bien.
        val segs = dir.listFiles { f -> f.name.startsWith("segmento-") && f.name.endsWith(".mp4") }!!
            .sortedBy { it.name }.filter { !recuperada || it.name in listos }
        if (segs.isEmpty()) return null
        val titulo = s.optString("titulo", "Grabación")
        val nombre = "${s.optString("creadoLocal").replace('T', ' ').replace(':', '.')} ${Ui.nombreSeguro(titulo)}.mp4"
        val destino = File(Biblioteca.videos, nombre)
        return try {
            Medios.unir(segs, destino)
            val info = Medios.validar(destino)
            if (!info.valido) { destino.delete(); return null }
            val ma = s.optJSONArray("marcas")
            val g = Grabacion(
                id = s.getString("id"), titulo = titulo, creado = s.getString("creado"), creadoLocal = s.getString("creadoLocal"),
                archivo = destino.path, miniatura = Medios.miniatura(destino, File(Biblioteca.miniaturas, s.getString("id") + ".jpg")),
                duracion = info.duracion, tamano = destino.length(), ancho = info.ancho, alto = info.alto,
                origen = s.optString("origen"), audio = s.optString("audio"),
                marcas = MutableList(ma?.length() ?: 0) { Marca(ma!!.getJSONObject(it).optDouble("t"), ma.getJSONObject(it).optString("nota")) },
                recuperada = recuperada,
            )
            val aj = Ajustes(ctx)
            if (!aj.nasConfigurado()) g.destinos["nas"] = Destino("no_configurado", motivo = "Vincula el NAS en Ajustes para copiar.")
            if (aj.galeria) g.destinos["galeria"] = Destino("pendiente")
            Biblioteca.agregar(g)
            dir.deleteRecursively()
            g
        } catch (e: Exception) {
            destino.delete(); null
        }
    }

    fun recuperar(ctx: Context) {
        if (CapturaService.activo) return
        val dirs = Biblioteca.temporales.listFiles { f -> f.isDirectory } ?: return
        for (d in dirs) {
            val id = d.name
            if (Biblioteca.obtener(id) != null) { d.deleteRecursively(); continue }
            val g = finalizarSesion(ctx, d, recuperada = true)
            if (g != null) Ui.avisoSistema(ctx, "Grabación recuperada", "Se recuperaron ${Ui.duracion(g.duracion)} de «${g.titulo}», que se interrumpió.")
            else if (File(d, "sesion.json").exists()) Ui.avisoSistema(ctx, "No se pudo recuperar una grabación", "La grabación se interrumpió antes de completar el primer minuto; no hay partes válidas. Los archivos quedaron en «Grabando».")
        }
    }
}
