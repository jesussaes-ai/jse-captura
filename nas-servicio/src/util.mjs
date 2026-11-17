import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';

// Escribe JSON de forma atómica: temporal + fsync + renombrado (nunca deja un archivo a medias).
export function escribirJsonAtomico(destino, datos) {
  fs.mkdirSync(path.dirname(destino), { recursive: true });
  const tmp = `${destino}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`;
  const fd = fs.openSync(tmp, 'w', 0o600);
  try {
    fs.writeSync(fd, JSON.stringify(datos, null, 2));
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(tmp, destino);
}

export async function sha256Archivo(ruta) {
  const h = crypto.createHash('sha256');
  for await (const trozo of fs.createReadStream(ruta, { highWaterMark: 1 << 20 })) h.update(trozo);
  return h.digest('hex');
}

export function espacioLibre(dir) {
  try {
    const s = fs.statfsSync(dir);
    return s.bavail * s.bsize;
  } catch {
    return Infinity;
  }
}

// Nombre seguro para Windows, macOS, Linux y SMB.
export function nombreSeguro(s, max = 80) {
  return String(s || '')
    .normalize('NFC')
    .replace(/[<>:"/\\|?*\x00-\x1f]/g, '_')
    .replace(/\s+/g, ' ')
    .replace(/^[.\s]+|[.\s]+$/g, '')
    .slice(0, max) || 'Grabación';
}

// Carpeta relativa elegida por el cliente: sin "..", sin rutas absolutas, sin carpetas ocultas.
export function carpetaSegura(c) {
  const partes = String(c || '')
    .split(/[\\/]+/)
    .map((p) => p.trim())
    .filter(Boolean);
  if (partes.some((p) => p === '..' || p === '.' || /^[.#@$]/.test(p))) return null;
  return partes.map((p) => nombreSeguro(p, 60)).join('/');
}

export const esUuid = (s) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(String(s || ''));
export const esSha = (s) => /^[0-9a-f]{64}$/i.test(String(s || ''));

// Valida el MP4 con ffprobe: pista de video H.264 (u otra) y duración > 0. Si no hay ffprobe, devuelve null.
export function sondear(ruta, ffprobe = 'ffprobe') {
  return new Promise((resolve) => {
    execFile(
      ffprobe,
      ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', ruta],
      { timeout: 60000, maxBuffer: 4 << 20 },
      (err, stdout) => {
        if (err && err.code === 'ENOENT') return resolve(null);
        if (err) return resolve({ valido: false, motivo: 'ffprobe no pudo leer el archivo' });
        try {
          const j = JSON.parse(stdout);
          const v = (j.streams || []).find((s) => s.codec_type === 'video');
          const a = (j.streams || []).find((s) => s.codec_type === 'audio');
          const duracion = Number(j.format?.duration) || Number(v?.duration) || 0;
          if (!v) return resolve({ valido: false, motivo: 'El archivo no tiene pista de video' });
          if (!(duracion > 0)) return resolve({ valido: false, motivo: 'Duración inválida' });
          resolve({
            valido: true,
            duracion,
            video: { codec: v.codec_name, ancho: v.width, alto: v.height, fps: v.avg_frame_rate },
            audio: a ? { codec: a.codec_name, canales: a.channels } : null,
            contenedor: j.format?.format_name,
          });
        } catch {
          resolve({ valido: false, motivo: 'Respuesta de ffprobe ilegible' });
        }
      },
    );
  });
}

export function miniatura(video, salida, ffmpeg = 'ffmpeg', segundo = 1) {
  return new Promise((resolve) => {
    fs.mkdirSync(path.dirname(salida), { recursive: true });
    const tmp = salida + '.tmp.jpg';
    execFile(
      ffmpeg,
      ['-y', '-v', 'error', '-ss', String(segundo), '-i', video, '-frames:v', '1', '-vf', 'scale=480:-2', tmp],
      { timeout: 60000 },
      (err) => {
        if (err || !fs.existsSync(tmp) || fs.statSync(tmp).size === 0) {
          fs.rmSync(tmp, { force: true });
          // Videos de menos de 1 s: probar con el primer cuadro.
          if (err?.code !== 'ENOENT' && segundo > 0) return resolve(miniatura(video, salida, ffmpeg, 0));
          return resolve(false);
        }
        fs.renameSync(tmp, salida);
        resolve(true);
      },
    );
  });
}
