package mx.jse.captura

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ErrorNas(val estado: Int, val codigo: String, mensaje: String, val datos: JSONObject = JSONObject()) : Exception(mensaje) {
    /** Errores que no se arreglan solos reintentando: el usuario debe actuar. */
    val requiereUsuario get() = codigo in setOf("credenciales_invalidas", "archivo_demasiado_grande", "sin_espacio", "carpeta_invalida", "video_invalido", "conflicto_id", "certificado")
}

/** Cliente del servicio NAS de JSE Captura (protocolo en docs/protocolo.md). Solo red local o Tailscale. */
class ClienteNas(direccion: String, var token: String? = null) {
    private val base: String = run {
        var d = direccion.trim().trimEnd('/')
        if (!d.contains("://")) d = "http://$d"
        if (Regex("^http://[^/:]+$").matches(d)) d += ":8790"
        d
    }

    private fun abrir(metodo: String, ruta: String, timeoutMs: Int = 30000): HttpURLConnection =
        (URL(base + ruta).openConnection() as HttpURLConnection).apply {
            requestMethod = if (metodo == "PATCH") "POST" else metodo
            if (metodo == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connectTimeout = 8000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun leer(c: HttpURLConnection): JSONObject {
        val codigo = c.responseCode
        val texto = (if (codigo >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val j = runCatching { if (texto.isBlank()) JSONObject() else JSONObject(texto) }
            .getOrElse { JSONObject().put("error", "respuesta_ilegible").put("mensaje", "Respuesta inesperada (HTTP $codigo). ¿Es la dirección del servicio JSE Captura?") }
        if (codigo >= 400) throw ErrorNas(codigo, j.optString("error", "http_$codigo"), j.optString("mensaje", "Error HTTP $codigo"), j)
        return j
    }

    private fun <T> red(f: () -> T): T = try { f() } catch (e: ErrorNas) { throw e } catch (e: Exception) { throw traducir(e) }

    private fun traducir(e: Exception) = when (e) {
        is ConnectException -> ErrorNas(0, "ECONNREFUSED", "El NAS no responde en esa dirección (¿apagado o fuera de casa sin Tailscale?).")
        is NoRouteToHostException -> ErrorNas(0, "EHOSTUNREACH", "No se encuentra el NAS en la red. ¿Estás en el Wi-Fi de casa o con Tailscale encendido?")
        is UnknownHostException -> ErrorNas(0, "ENOTFOUND", "No se reconoce el nombre del NAS.")
        is SocketTimeoutException -> ErrorNas(0, "ETIMEDOUT", "El NAS tardó demasiado en responder.")
        is SSLException -> ErrorNas(0, "certificado", "El certificado HTTPS del NAS no es de confianza en este teléfono (ver guía, sección HTTPS).")
        is IOException -> ErrorNas(0, "red", "Se cortó la conexión con el NAS: ${e.message}")
        else -> ErrorNas(0, "red", e.message ?: "Error de red")
    }

    fun json(metodo: String, ruta: String, cuerpo: JSONObject? = null, timeoutMs: Int = 30000): JSONObject = red {
        val c = abrir(metodo, ruta, timeoutMs)
        if (cuerpo != null) {
            val b = cuerpo.toString().toByteArray()
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
        }
        try { leer(c) } finally { c.disconnect() }
    }

    fun salud() = json("GET", "/api/v1/salud")
    fun iniciarSesion(usuario: String, contrasena: String, equipo: String): JSONObject =
        json("POST", "/api/v1/sesion", JSONObject().put("usuario", usuario).put("contrasena", contrasena).put("equipo", equipo)).also { token = it.getString("token") }
    fun prueba() = json("GET", "/api/v1/prueba")
    fun carpetas() = json("GET", "/api/v1/carpetas")
    fun listar(q: String = "", pagina: Int = 1) = json("GET", "/api/v1/grabaciones?pagina=$pagina&limite=30&q=" + java.net.URLEncoder.encode(q, "UTF-8"))
    fun editar(id: String, cambios: JSONObject) = json("PATCH", "/api/v1/grabaciones/$id", cambios)
    fun eliminar(id: String) = json("DELETE", "/api/v1/grabaciones/$id")
    fun enlace(id: String): String = base + json("GET", "/api/v1/grabaciones/$id/enlace").getString("ruta")
    fun comprobarFotosJse(id: String) = json("POST", "/api/v1/grabaciones/$id/fotos-jse", timeoutMs = 200000)

    /** Subida reanudable en trozos de 8 MB. Si se corta, el NAS dice desde dónde seguir. */
    fun subir(f: File, meta: JSONObject, alProgreso: (Long, Long) -> Unit, cancelado: () -> Boolean = { false }): JSONObject {
        val total = f.length()
        var r = json("POST", "/api/v1/subidas", meta.put("tamano", total))
        val trozo = 8L * 1024 * 1024
        val buf = ByteArray(256 * 1024)
        RandomAccessFile(f, "r").use { raf ->
            while (r.optString("estado") != "guardado") {
                if (cancelado()) throw ErrorNas(0, "cancelado", "En pausa")
                val desde = r.getLong("desplazamiento")
                val largo = minOf(trozo, total - desde)
                alProgreso(desde, total)
                r = try {
                    red {
                        val c = abrir("PATCH", "/api/v1/subidas/${meta.getString("id")}", 120000)
                        c.doOutput = true
                        c.setRequestProperty("Upload-Offset", desde.toString())
                        c.setRequestProperty("Content-Type", "application/offset+octet-stream")
                        c.setFixedLengthStreamingMode(largo)
                        raf.seek(desde)
                        c.outputStream.use { o ->
                            var resta = largo
                            while (resta > 0) {
                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), resta).toInt())
                                if (n < 0) break
                                o.write(buf, 0, n); resta -= n
                                alProgreso(desde + largo - resta, total)
                            }
                        }
                        try { leer(c) } finally { c.disconnect() }
                    }
                } catch (e: ErrorNas) {
                    if (e.codigo == "desplazamiento_incorrecto" && e.datos.has("desplazamiento")) JSONObject().put("estado", "incompleta").put("desplazamiento", e.datos.getLong("desplazamiento"))
                    else throw e
                }
            }
        }
        alProgreso(total, total)
        return r
    }
}
