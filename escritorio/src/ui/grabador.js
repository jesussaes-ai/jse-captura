// Grabación en el proceso de interfaz: getDisplayMedia + MediaRecorder. Los trozos se mandan al proceso principal
// cada segundo y se escriben a disco al momento (nada se acumula en memoria; un cierre inesperado deja lo grabado).
/* global jse */
const TIPOS = [
  // MP4 con H.264 (Chromium 126+; codificador por hardware si la tarjeta lo tiene) → no hace falta recodificar.
  'video/mp4;codecs=avc1.640028,mp4a.40.2',
  'video/mp4;codecs=avc1.640028,opus',
  'video/mp4;codecs=avc1',
  'video/mp4',
  // Respaldo: WebM; al detener se convierte a H.264.
  'video/webm;codecs=h264,opus',
  'video/webm;codecs=vp9,opus',
  'video/webm',
];

class Grabador {
  constructor() {
    this.recorder = null;
    this.flujos = [];
    this.audioCtx = null;
    this.alTerminar = null; // la captura terminó sola (ventana cerrada, permiso retirado…)
  }

  async iniciar({ fuente, calidad = '1080', microfono = false, audioSistema = false, titulo }) {
    await jse.preparar({ fuenteId: fuente.id });
    const [maxAncho, maxAlto, bits] = calidad === '720' ? [1280, 720, 4_000_000] : [1920, 1080, 8_000_000];
    const pantalla = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: { ideal: 30, max: 30 }, width: { max: maxAncho }, height: { max: maxAlto } },
      audio: audioSistema && fuente.tipo === 'pantalla',
    });
    this.flujos.push(pantalla);
    const video = pantalla.getVideoTracks()[0];
    // Ajustar a 1080p/720p conservando la relación de aspecto (ventanas verticales, monitores ultrapanorámicos…).
    try {
      await video.applyConstraints({ width: { max: maxAncho }, height: { max: maxAlto }, frameRate: { max: 30 } });
    } catch {}
    try {
      video.contentHint = 'detail';
    } catch {}

    const pistasAudio = [...pantalla.getAudioTracks()];
    let avisoAudio = null;
    if (audioSistema && fuente.tipo !== 'pantalla') avisoAudio = 'El audio del equipo solo se puede grabar con una pantalla completa; esta grabación va sin él.';
    if (audioSistema && fuente.tipo === 'pantalla' && !pistasAudio.length) avisoAudio = 'Windows no entregó el audio del equipo; la grabación sigue sin él.';
    if (microfono) {
      try {
        const mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
        this.flujos.push(mic);
        pistasAudio.push(...mic.getAudioTracks());
      } catch (e) {
        avisoAudio = `No se pudo usar el micrófono (${e.name === 'NotAllowedError' ? 'permiso denegado en Windows → Privacidad → Micrófono' : e.message}). La grabación sigue sin él.`;
      }
    }
    let pistaAudio = null;
    if (pistasAudio.length === 1) pistaAudio = pistasAudio[0];
    else if (pistasAudio.length > 1) {
      // Mezcla micrófono + equipo en una sola pista.
      this.audioCtx = new AudioContext();
      const destino = this.audioCtx.createMediaStreamDestination();
      for (const p of pistasAudio) this.audioCtx.createMediaStreamSource(new MediaStream([p])).connect(destino);
      pistaAudio = destino.stream.getAudioTracks()[0];
    }
    const flujo = new MediaStream([video, ...(pistaAudio ? [pistaAudio] : [])]);
    const tipo = TIPOS.find((t) => MediaRecorder.isTypeSupported(t)) || '';
    this.recorder = new MediaRecorder(flujo, { mimeType: tipo, videoBitsPerSecond: bits, audioBitsPerSecond: 160_000 });
    const ajustesVideo = video.getSettings();
    const sesion = await jse.iniciar({
      titulo,
      extension: tipo.includes('webm') ? 'webm' : 'mp4',
      displayId: fuente.displayId,
      audio: { microfono: microfono && pistasAudio.some((p) => p.label && !/loopback|system/i.test(p.label)), equipo: pantalla.getAudioTracks().length > 0 },
      origen: {
        plataforma: 'windows',
        equipo: '',
        modo: fuente.nombre,
        tipo: fuente.tipo,
        resolucion: `${ajustesVideo.width}×${ajustesVideo.height}`,
        codificador: tipo,
      },
    });
    // Los trozos se envían en orden aunque convertirlos sea asíncrono.
    this.cadena = Promise.resolve();
    this.recorder.ondataavailable = (e) => {
      if (!e.data || !e.data.size) return;
      this.cadena = this.cadena.then(async () => jse.trozo(new Uint8Array(await e.data.arrayBuffer())));
    };
    this.detenido = new Promise((r) => (this.recorder.onstop = r));
    // Si el sistema termina la captura (se cerró la ventana, se bloqueó la sesión, contenido protegido…).
    video.addEventListener('ended', () => this.alTerminar && this.alTerminar('La fuente dejó de estar disponible (se cerró la ventana o Windows detuvo la captura). Se guardó lo grabado.'));
    this.recorder.start(1000);
    return { sesion, avisoAudio, tipo, resolucion: `${ajustesVideo.width}×${ajustesVideo.height}` };
  }

  async detener() {
    if (!this.recorder) return null;
    if (this.recorder.state !== 'inactive') {
      this.recorder.requestData();
      this.recorder.stop();
      await this.detenido;
    }
    // Esperar a que el último trozo se envíe.
    await this.cadena;
    for (const f of this.flujos) f.getTracks().forEach((t) => t.stop());
    this.flujos = [];
    if (this.audioCtx) this.audioCtx.close();
    this.audioCtx = null;
    this.recorder = null;
    return jse.detener();
  }
}

window.Grabador = Grabador;
