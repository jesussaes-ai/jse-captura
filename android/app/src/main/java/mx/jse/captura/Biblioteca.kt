package mx.jse.captura

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Estado de un destino (Dispositivo, NAS, Fotos JSE, Galería). */
data class Destino(
    var estado: String = "pendiente",   // pendiente | copiando | guardado | exportado | visible | requiere_atencion | no_configurado
    var progreso: Double = 0.0,
    var motivo: String? = null,
    var codigo: String? = null,
    var intentos: Int = 0,
    var ruta: String? = null,
) {
    fun json() = JSONObject().put("estado", estado).put("progreso", progreso).put("motivo", motivo).put("codigo", codigo).put("intentos", intentos).put("ruta", ruta)

    companion object {
        fun de(j: JSONObject?) = if (j == null) Destino() else Destino(
            j.optString("estado", "pendiente"), j.optDouble("progreso", 0.0),
            j.optString("motivo").takeIf { it.isNotEmpty() && it != "null" },
            j.optString("codigo").takeIf { it.isNotEmpty() && it != "null" },
            j.optInt("intentos", 0), j.optString("ruta").takeIf { it.isNotEmpty() && it != "null" },
        )

        val TEXTO = mapOf(
            "pendiente" to "Pendiente", "copiando" to "Copiando", "guardado" to "Guardado",
            "exportado" to "Exportado a la carpeta", "visible" to "Visible en Fotos JSE",
            "requiere_atencion" to "Requiere atención", "no_configurado" to "Sin configurar", "borrado" to "Borrado",
        )
    }
}

data class Marca(val t: Double, val nota: String = "")

data class Grabacion(
    val id: String,
    var titulo: String,
    val creado: String,          // ISO UTC
    val creadoLocal: String,     // AAAA-MM-DDTHH:mm:ss
    var archivo: String?,
    var miniatura: String?,
    var duracion: Double,
    var tamano: Long,
    var ancho: Int,
    var alto: Int,
    var origen: String,
    var audio: String,
    var notas: String = "",
    var etiquetas: MutableList<String> = mutableListOf(),
    var favorito: Boolean = false,
    var marcas: MutableList<Marca> = mutableListOf(),
    var recorteDe: String? = null,
    var sha256: String? = null,
    var recuperada: Boolean = false,
    var metaPendiente: Boolean = false,
    val destinos: MutableMap<String, Destino> = mutableMapOf(
        "dispositivo" to Destino("guardado"), "nas" to Destino(), "fotosJse" to Destino(), "galeria" to Destino("no_configurado"),
    ),
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("titulo", titulo).put("creado", creado).put("creadoLocal", creadoLocal)
        .put("archivo", archivo).put("miniatura", miniatura).put("duracion", duracion).put("tamano", tamano)
        .put("ancho", ancho).put("alto", alto).put("origen", origen).put("audio", audio).put("notas", notas)
        .put("etiquetas", JSONArray(etiquetas)).put("favorito", favorito)
        .put("marcas", JSONArray(marcas.map { JSONObject().put("t", it.t).put("nota", it.nota) }))
        .put("recorteDe", recorteDe).put("sha256", sha256).put("recuperada", recuperada).put("metaPendiente", metaPendiente)
        .put("destinos", JSONObject().apply { destinos.forEach { (k, v) -> put(k, v.json()) } })

    companion object {
        fun de(j: JSONObject): Grabacion {
            val et = j.optJSONArray("etiquetas") ?: JSONArray()
            val ma = j.optJSONArray("marcas") ?: JSONArray()
            val de = j.optJSONObject("destinos") ?: JSONObject()
            fun s(k: String) = j.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            return Grabacion(
                j.getString("id"), j.optString("titulo"), j.optString("creado"), j.optString("creadoLocal"),
                s("archivo"), s("miniatura"), j.optDouble("duracion", 0.0), j.optLong("tamano"), j.optInt("ancho"), j.optInt("alto"),
                j.optString("origen"), j.optString("audio"), j.optString("notas"),
                MutableList(et.length()) { et.getString(it) }, j.optBoolean("favorito"),
                MutableList(ma.length()) { Marca(ma.getJSONObject(it).optDouble("t"), ma.getJSONObject(it).optString("nota")) },
                s("recorteDe"), s("sha256"), j.optBoolean("recuperada"), j.optBoolean("metaPendiente"),
                mutableMapOf(
                    "dispositivo" to Destino.de(de.optJSONObject("dispositivo") ?: JSONObject().put("estado", "guardado")),
                    "nas" to Destino.de(de.optJSONObject("nas")),
                    "fotosJse" to Destino.de(de.optJSONObject("fotosJse")),
                    "galeria" to Destino.de(de.optJSONObject("galeria") ?: JSONObject().put("estado", "no_configurado")),
                ),
            )
        }
    }
}

/** Biblioteca local (JSON atómico en almacenamiento privado de la app). Funciona sin NAS desde el primer uso. */
object Biblioteca {
    private lateinit var archivo: File
    lateinit var videos: File; private set
    lateinit var temporales: File; private set
    lateinit var miniaturas: File; private set
    val items = CopyOnWriteArrayList<Grabacion>()
    private val oyentes = CopyOnWriteArrayList<() -> Unit>()

    fun iniciar(ctx: Context) {
        if (::archivo.isInitialized) return
        // Videos en la carpeta privada de la app (no requiere permisos; se puede compartir con FileProvider).
        videos = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "JSE Captura").apply { mkdirs() }
        temporales = File(ctx.getExternalFilesDir(null), "Grabando").apply { mkdirs() }
        miniaturas = File(ctx.filesDir, "miniaturas").apply { mkdirs() }
        archivo = File(ctx.filesDir, "biblioteca.json")
        if (archivo.exists()) runCatching {
            val a = JSONObject(archivo.readText()).getJSONArray("grabaciones")
            for (i in 0 until a.length()) items.add(Grabacion.de(a.getJSONObject(i)))
        }.onFailure { archivo.copyTo(File(archivo.path + ".dañada-" + System.currentTimeMillis()), true) }
        // Lo que quedó "Copiando" vuelve a "Pendiente".
        items.forEach { g -> g.destinos.values.forEach { if (it.estado == "copiando") it.estado = "pendiente" } }
    }

    @Synchronized
    fun guardar() {
        val tmp = File(archivo.path + ".tmp")
        val datos = JSONObject().put("version", 1).put("grabaciones", JSONArray(items.map { it.json() })).toString()
        tmp.outputStream().use { o -> o.write(datos.toByteArray()); o.fd.sync() }
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
        oyentes.forEach { it() }
    }

    fun notificar() = oyentes.forEach { it() }
    fun alCambiar(f: () -> Unit) = oyentes.add(f)
    fun quitarOyente(f: () -> Unit) = oyentes.remove(f)

    fun nuevoId(): String = UUID.randomUUID().toString()
    fun obtener(id: String) = items.firstOrNull { it.id == id }

    fun agregar(g: Grabacion) {
        items.removeAll { it.id == g.id }
        items.add(g)
        guardar()
    }

    fun listar(q: String = "", pendientes: Boolean = false, favoritos: Boolean = false): List<Grabacion> {
        val t = q.lowercase().trim()
        return items.filter { g ->
            (!pendientes || g.destinos.values.any { it.estado in setOf("pendiente", "copiando", "requiere_atencion") }) &&
                (!favoritos || g.favorito) &&
                (t.isEmpty() || (listOf(g.titulo, g.notas) + g.etiquetas).joinToString(" ").lowercase().contains(t))
        }.sortedByDescending { it.creado }
    }

    /**
     * Vuelve a poner en la lista los videos que están en el teléfono pero que la lista perdió
     * (lista dañada, una versión anterior que no la guardó, etc.). Nunca borra nada.
     */
    fun reconstruir(ctx: Context): Int {
        var n = 0
        // 1) Copias de una lista que no se pudo leer.
        ctx.filesDir.listFiles { f -> f.name.startsWith("biblioteca.json.dañada") }?.sortedByDescending { it.name }?.forEach { f ->
            runCatching {
                val a = JSONObject(f.readText()).getJSONArray("grabaciones")
                for (i in 0 until a.length()) runCatching {
                    val g = Grabacion.de(a.getJSONObject(i))
                    if (obtener(g.id) == null && g.archivo?.let { File(it).exists() } == true) { items.add(g); n++ }
                }
            }
        }
        // 2) MP4 en la carpeta de videos que no están en la lista.
        val conocidos = items.mapNotNull { it.archivo?.let { a -> File(a).canonicalPath } }.toSet()
        videos.listFiles { f -> f.isFile && f.name.endsWith(".mp4", true) }?.forEach { f ->
            if (f.canonicalPath in conocidos) return@forEach
            val info = Medios.validar(f)
            if (!info.valido) return@forEach
            val m = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2})\.(\d{2})\.(\d{2}) (.+)\.mp4$""", RegexOption.IGNORE_CASE).find(f.name)
            val local = m?.let { "${it.groupValues[1]}T${it.groupValues[2]}:${it.groupValues[3]}:${it.groupValues[4]}" }
                ?: LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(f.lastModified()), java.time.ZoneId.systemDefault()).withNano(0).toString()
            val creado = runCatching { LocalDateTime.parse(local).atZone(java.time.ZoneId.systemDefault()).toInstant().toString() }.getOrDefault(java.time.Instant.ofEpochMilli(f.lastModified()).toString())
            val id = nuevoId()
            val g = Grabacion(
                id = id, titulo = m?.groupValues?.get(5) ?: f.nameWithoutExtension, creado = creado, creadoLocal = local,
                archivo = f.path, miniatura = Medios.miniatura(f, File(miniaturas, "$id.jpg")),
                duracion = info.duracion, tamano = f.length(), ancho = info.ancho, alto = info.alto,
                origen = "Android · recuperada de la carpeta de videos", audio = "",
            )
            if (!Ajustes(ctx).nasConfigurado()) g.destinos["nas"] = Destino("no_configurado", motivo = "Vincula el NAS en Ajustes para copiar.")
            items.add(g); n++
        }
        if (n > 0) guardar()
        android.util.Log.i("JSECaptura", "reconstruir: $n grabaciones devueltas a la lista")
        return n
    }

    /** Nombre del MP4 en disco: fecha + título (igual que al guardar). */
    fun nombreArchivo(creadoLocal: String, titulo: String) =
        "${creadoLocal.replace('T', ' ').replace(':', '.')} ${Ui.nombreSeguro(titulo)}.mp4"

    /** Cambia el título y, si no se está copiando en ese momento, también el nombre del MP4 en el teléfono. */
    fun renombrar(id: String, titulo: String): Grabacion? {
        val g = obtener(id) ?: return null
        val t = titulo.trim().ifEmpty { return g }
        g.titulo = t
        val actual = g.archivo?.let { File(it) }
        if (actual != null && actual.exists() && g.destinos["nas"]?.estado != "copiando") {
            var nuevo = File(actual.parentFile, nombreArchivo(g.creadoLocal, t))
            if (nuevo.exists() && nuevo != actual) nuevo = File(actual.parentFile, nuevo.nameWithoutExtension + " ${g.id.take(4)}.mp4")
            if (nuevo != actual && actual.renameTo(nuevo)) g.archivo = nuevo.path
        }
        if (g.destinos["nas"]?.estado == "guardado") g.metaPendiente = true
        guardar()
        return g
    }

    /** Borra solo la copia del teléfono. La del NAS y la de la galería no se tocan. */
    fun borrarLocal(id: String): Boolean {
        val g = obtener(id) ?: return false
        g.archivo?.let { File(it).delete() }
        g.miniatura?.let { File(it).delete() }
        if (g.destinos["nas"]?.estado == "guardado") {
            g.archivo = null; g.destinos["dispositivo"] = Destino("borrado")
        } else items.remove(g)
        guardar()
        return true
    }

    fun fechaLocal(t: LocalDateTime = LocalDateTime.now()): String = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}
