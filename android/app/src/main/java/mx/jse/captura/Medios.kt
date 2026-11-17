package mx.jse.captura

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

data class InfoVideo(val valido: Boolean, val duracion: Double = 0.0, val ancho: Int = 0, val alto: Int = 0, val motivo: String? = null)

object Medios {
    /** Comprueba que el MP4 tenga pista de video y duración válida antes de agregarlo a la biblioteca. */
    fun validar(f: File): InfoVideo {
        if (!f.exists() || f.length() == 0L) return InfoVideo(false, motivo = "archivo vacío")
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val tieneVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            when {
                !tieneVideo -> InfoVideo(false, motivo = "no tiene pista de video")
                ms <= 0 -> InfoVideo(false, motivo = "duración inválida")
                else -> InfoVideo(true, ms / 1000.0, w, h)
            }
        } catch (e: Exception) {
            InfoVideo(false, motivo = "no se pudo leer (${e.message})")
        } finally {
            r.release()
        }
    }

    fun miniatura(video: File, destino: File): String? = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(video.path)
        val bmp = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0)
        r.release()
        val esc = Bitmap.createScaledBitmap(bmp!!, 480, (480.0 * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
        destino.outputStream().use { esc.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        destino.path
    }.getOrNull()

    fun sha256(f: File, alAvanzar: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        f.inputStream().use { i ->
            while (true) {
                val n = i.read(buf); if (n < 0) break
                md.update(buf, 0, n); total += n; alAvanzar?.invoke(total)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Une segmentos MP4 (mismo codificador) en uno solo sin recodificar.
     * Cada segmento empieza en un cuadro clave; las marcas de tiempo se hacen continuas.
     */
    fun unir(segmentos: List<File>, destino: File) {
        val validos = segmentos.filter { validar(it).valido }
        require(validos.isNotEmpty()) { "No hay segmentos válidos" }
        if (validos.size == 1) {
            validos[0].copyTo(destino, overwrite = true); return
        }
        val tmp = File(destino.path + ".tmp")
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val pistas = mutableMapOf<String, Int>()
        // Formatos del primer segmento.
        MediaExtractor().apply { setDataSource(validos[0].path) }.let { ex ->
            for (t in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME)!!
                val tipo = if (mime.startsWith("video/")) "v" else "a"
                if (tipo !in pistas) pistas[tipo] = muxer.addTrack(f)
            }
            ex.release()
        }
        muxer.start()
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var desplazamiento = 0L
        for (seg in validos) {
            val ex = MediaExtractor().apply { setDataSource(seg.path) }
            val mapa = mutableMapOf<Int, Int>()
            for (t in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME)!!
                pistas[if (mime.startsWith("video/")) "v" else "a"]?.let { mapa[t] = it; ex.selectTrack(t) }
            }
            var base = -1L
            var maxPts = 0L
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val pista = mapa[ex.sampleTrackIndex]
                if (pista == null) { ex.advance(); continue }
                if (base < 0) base = ex.sampleTime
                val pts = (ex.sampleTime - base).coerceAtLeast(0) + desplazamiento
                maxPts = maxOf(maxPts, pts)
                info.set(0, n, pts, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(pista, buf, info)
                ex.advance()
            }
            ex.release()
            desplazamiento = maxPts + 1_000_000L / Codificador.FPS
        }
        muxer.stop(); muxer.release()
        if (!tmp.renameTo(destino)) { destino.delete(); tmp.renameTo(destino) }
    }

    /**
     * Recorte sin recodificar: empieza en el cuadro clave anterior a `inicio` (como mucho 1 s antes,
     * porque se graba con un cuadro clave por segundo) y termina en `fin`. Crea un MP4 nuevo.
     */
    fun recortar(origen: File, destino: File, inicio: Double, fin: Double) {
        val ex = MediaExtractor().apply { setDataSource(origen.path) }
        val tmp = File(destino.path + ".tmp")
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val mapa = mutableMapOf<Int, Int>()
        for (t in 0 until ex.trackCount) { mapa[t] = muxer.addTrack(ex.getTrackFormat(t)); ex.selectTrack(t) }
        muxer.start()
        ex.seekTo((inicio * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var base = -1L
        val finUs = (fin * 1e6).toLong()
        while (true) {
            val n = ex.readSampleData(buf, 0)
            if (n < 0 || ex.sampleTime > finUs) break
            if (base < 0) base = ex.sampleTime
            info.set(0, n, (ex.sampleTime - base).coerceAtLeast(0), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(mapa[ex.sampleTrackIndex]!!, buf, info)
            ex.advance()
        }
        ex.release(); muxer.stop(); muxer.release()
        if (!tmp.renameTo(destino)) { destino.delete(); tmp.renameTo(destino) }
    }
}
