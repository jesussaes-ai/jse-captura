package mx.jse.captura

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Codifica la pantalla (H.264) y el audio (AAC) con MediaCodec y los escribe en segmentos MP4 de ~60 s.
 * Si la app o el teléfono se cierran de golpe, los segmentos ya cerrados se pueden recuperar.
 * Al detener, los segmentos se unen sin recodificar en un único MP4.
 */
class Codificador(
    private val dir: File,
    val ancho: Int,
    val alto: Int,
    private val bitrate: Int,
    private val proyeccion: MediaProjection?,
    private val microfono: Boolean,
    private val audioInterno: Boolean,
) {
    companion object {
        const val FPS = 30
        const val SEGMENTO_US = 60_000_000L
        private const val TASA = 48000
        private const val TAG = "JSECodificador"

        /** Tamaño de salida: hasta 1080p (o 720p) conservando la relación de aspecto; múltiplos de 16. */
        fun tamano(anchoPantalla: Int, altoPantalla: Int, maxLargo: Int, maxCorto: Int): Pair<Int, Int> {
            val largo = max(anchoPantalla, altoPantalla).toDouble()
            val corto = min(anchoPantalla, altoPantalla).toDouble()
            val escala = min(1.0, min(maxLargo / largo, maxCorto / corto))
            fun al16(v: Double) = max(16, (v * escala / 16).toInt() * 16)
            return if (anchoPantalla >= altoPantalla) al16(largo) to al16(corto) else al16(corto) to al16(largo)
        }
    }

    private lateinit var video: MediaCodec
    private var audio: MediaCodec? = null
    lateinit var superficie: Surface; private set
    private var formatoVideo: MediaFormat? = null
    private var formatoAudio: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var pistaV = -1
    private var pistaA = -1
    private var inicioSegmentoUs = -1L
    private var numSegmento = 0
    private var pidioClave = false
    private var ultimoAudioUs = -1L
    private val bloqueo = Object()
    @Volatile private var corriendo = false
    private val grabadores = mutableListOf<AudioRecord>()
    private var hiloVideo: Thread? = null
    private var hiloAudio: Thread? = null
    val segmentos = mutableListOf<File>()
    var avisoAudio: String? = null; private set
    var primerPtsUs = -1L; private set
    var ultimoPtsUs = 0L; private set
    val conAudio get() = audio != null

    /**
     * Mientras es falso, la captura ya está lista (permiso, codificadores, audio) pero no se escribe nada:
     * así la grabación empieza justo cuando tocas el botón flotante y no desde que aceptas el permiso.
     */
    @Volatile var escribiendo = false; private set

    fun empezar() {
        if (escribiendo) return
        escribiendo = true
        // El primer cuadro escrito debe ser clave: se pide uno ya para no esperar hasta 1 s.
        runCatching { video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    // ---- pausa ----
    // Al pausar no se escribe nada. Al seguir, se espera un cuadro clave y todo lo que viene después se recorre
    // hacia atrás el tiempo que duró la pausa: el MP4 queda continuo, sin hueco ni imagen congelada.
    @Volatile var pausado = false; private set
    private var reanudando = false
    private var desfaseUs = 0L
    private var ultimoCrudoUs = -1L
    private var reanudaCrudoUs = Long.MAX_VALUE

    fun pausar() = synchronized(bloqueo) {
        if (!escribiendo || pausado) return@synchronized
        pausado = true
    }

    fun reanudar() = synchronized(bloqueo) {
        if (!pausado) return@synchronized
        pausado = false
        reanudando = true
        runCatching { video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    @SuppressLint("MissingPermission")
    fun iniciar() {
        val fv = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ancho, alto).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Repetir el último cuadro si la pantalla no cambia (pantallas estáticas siguen teniendo video continuo).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / FPS)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            video.configure(fv, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Algunos codificadores no aceptan High: se usa el perfil por defecto (Baseline/Main).
            fv.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            fv.removeKey(MediaFormat.KEY_LEVEL)
            video.reset()
            video.configure(fv, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        superficie = video.createInputSurface()

        // ---- audio ----
        // Eco: si se graban a la vez el audio interno y el micrófono, el micrófono también oye la bocina y el
        // sonido sale dos veces con retraso. Con ambos se activan la cancelación de eco y de ruido del teléfono.
        if (microfono) {
            // Fuente normal del micrófono en todos los teléfonos: la de «voz» (llamada) en algunos Samsung cambia
            // el modo de audio y baja o corta el sonido que se está grabando. La cancelación de eco va encima.
            val fuente = MediaRecorder.AudioSource.MIC
            crearGrabador(fuente, null)?.let { r ->
                grabadores.add(r)
                if (audioInterno) limpiarVoz(r.audioSessionId)
            } ?: run { avisoAudio = "No se pudo abrir el micrófono; la grabación va sin él." }
        }
        if (audioInterno && proyeccion != null) {
            val conf = AudioPlaybackCaptureConfiguration.Builder(proyeccion)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            crearGrabador(null, conf)?.let { grabadores.add(it) }
                ?: run { avisoAudio = (avisoAudio?.plus(" ") ?: "") + "El sistema no permitió capturar el audio interno." }
        }
        if (grabadores.isNotEmpty()) {
            val fa = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, TASA, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(fa, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }

        corriendo = true
        video.start()
        audio?.start()
        grabadores.forEach { it.startRecording() }
        hiloVideo = Thread({ bucleVideo() }, "jse-video").apply { start() }
        if (audio != null) hiloAudio = Thread({ bucleAudio() }, "jse-audio").apply { start() }
    }

    @SuppressLint("MissingPermission")
    private fun crearGrabador(fuente: Int?, conf: AudioPlaybackCaptureConfiguration?): AudioRecord? = try {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(TASA).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val buf = max(AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4, 8192)
        val b = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(buf)
        if (conf != null) b.setAudioPlaybackCaptureConfig(conf) else b.setAudioSource(fuente!!)
        b.build().takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (e: Exception) {
        Log.w(TAG, "AudioRecord", e); null
    }

    private val efectos = mutableListOf<android.media.audiofx.AudioEffect>()

    /** Cancelación de eco y supresión de ruido del propio teléfono, si las tiene. */
    private fun limpiarVoz(sesion: Int) {
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sesion)?.let { it.enabled = true; efectos.add(it) } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sesion)?.let { it.enabled = true; efectos.add(it) } }
    }

    /** Marca para finalizar: el siguiente bloque de datos sale con fin de flujo. */
    fun detener() {
        corriendo = false
        runCatching { video.signalEndOfInputStream() }
        hiloAudio?.join(3000)
        hiloVideo?.join(5000)
        synchronized(bloqueo) {
            if (muxer != null) { if (inicioSegmentoUs >= 0) cerrarSegmento() else {
                // Segmento abierto sin ningún cuadro (se canceló antes de empezar): se descarta.
                runCatching { muxer?.release() }; muxer = null
                segmentos.removeLastOrNull()?.delete()
            } }
        }
        runCatching { video.stop(); video.release() }
        runCatching { audio?.stop(); audio?.release() }
        grabadores.forEach { runCatching { it.stop(); it.release() } }
        efectos.forEach { runCatching { it.release() } }
        runCatching { superficie.release() }
    }

    private fun bucleVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = video.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(bloqueo) { formatoVideo = video.outputFormat; abrirSiListo() }
                i >= 0 -> {
                    val buf = video.getOutputBuffer(i)!!
                    val clave = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        synchronized(bloqueo) {
                            val crudo = info.presentationTimeUs
                            var escribir = escribiendo && !pausado
                            if (escribir && reanudando) {
                                if (!clave) escribir = false
                                else {
                                    // Primer cuadro después de la pausa: se quita el tiempo pausado.
                                    if (ultimoCrudoUs >= 0) desfaseUs += crudo - ultimoCrudoUs - 1_000_000L / FPS
                                    reanudando = false
                                    reanudaCrudoUs = crudo
                                }
                            }
                            info.presentationTimeUs = crudo - desfaseUs
                            // Rotar de segmento en un cuadro clave para que cada segmento sea reproducible solo.
                            if (escribir && muxer != null && inicioSegmentoUs >= 0 && info.presentationTimeUs - inicioSegmentoUs >= SEGMENTO_US) {
                                if (clave) { cerrarSegmento(); abrirSiListo() }
                                else if (!pidioClave) { video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }); pidioClave = true }
                            }
                            if (escribir && muxer != null && (inicioSegmentoUs >= 0 || clave)) {
                                ultimoCrudoUs = crudo
                                if (inicioSegmentoUs < 0) inicioSegmentoUs = info.presentationTimeUs
                                if (primerPtsUs < 0) primerPtsUs = info.presentationTimeUs
                                ultimoPtsUs = max(ultimoPtsUs, info.presentationTimeUs)
                                muxer!!.writeSampleData(pistaV, buf, info)
                            }
                        }
                    }
                    video.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun bucleAudio() {
        val a = audio!!
        val info = MediaCodec.BufferInfo()
        val n = 2048
        val bufs = grabadores.map { ShortArray(n) }
        val mezcla = ShortArray(n)
        val inicioNs = System.nanoTime()
        var muestras = 0L
        var fin = false
        while (true) {
            if (!fin) {
                val ii = a.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val leidos = grabadores.mapIndexed { k, r -> r.read(bufs[k], 0, n) }
                    val len = leidos.filter { it > 0 }.maxOrNull() ?: 0
                    for (s in 0 until len) {
                        var v = 0
                        for (k in bufs.indices) if (s < leidos[k]) v += bufs[k][s]
                        mezcla[s] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    val entrada = a.getInputBuffer(ii)!!
                    entrada.clear()
                    entrada.asShortBuffer().put(mezcla, 0, len)
                    // Marca de tiempo por muestras contadas, alineada con el reloj del video (System.nanoTime).
                    val pts = inicioNs / 1000 + muestras * 1_000_000L / TASA
                    muestras += len
                    fin = !corriendo
                    a.queueInputBuffer(ii, 0, len * 2, pts, if (fin) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                }
            }
            val o = a.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) synchronized(bloqueo) { formatoAudio = a.outputFormat; abrirSiListo() }
            else if (o >= 0) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) synchronized(bloqueo) {
                    val crudo = info.presentationTimeUs
                    // En pausa solo pasa el audio de antes de pausar; al seguir, solo el de después del cuadro clave.
                    val vale = if (pausado || reanudando) crudo <= ultimoCrudoUs else crudo >= reanudaCrudoUs || reanudaCrudoUs == Long.MAX_VALUE
                    info.presentationTimeUs = crudo - desfaseUs
                    if (vale && muxer != null && inicioSegmentoUs >= 0 && info.presentationTimeUs >= inicioSegmentoUs && info.presentationTimeUs > ultimoAudioUs) {
                        muxer!!.writeSampleData(pistaA, a.getOutputBuffer(o)!!, info)
                        ultimoAudioUs = info.presentationTimeUs
                    }
                }
                a.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    // Debe llamarse con `bloqueo`.
    private fun abrirSiListo() {
        if (muxer != null || formatoVideo == null || (audio != null && formatoAudio == null)) return
        val f = File(dir, "segmento-%03d.mp4".format(numSegmento++))
        muxer = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
            pistaV = m.addTrack(formatoVideo!!)
            pistaA = formatoAudio?.let { m.addTrack(it) } ?: -1
            m.start()
        }
        segmentos.add(f)
        inicioSegmentoUs = -1
        pidioClave = false
    }

    private fun cerrarSegmento() {
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        // Aviso en disco: este segmento quedó completo (sirve para recuperar tras un cierre inesperado).
        File(dir, "listos.txt").appendText(segmentos.last().name + "\n")
    }
}
