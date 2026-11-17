// Operaciones con ffmpeg/ffprobe (incluidos en el instalador; no hace falta Internet).
// Resultado siempre: MP4 con H.264 (+ AAC si hay audio) y "faststart", que abre cualquier reproductor
// (VLC, mpv, MPC-HC, Películas y TV, navegadores, galerías de Android e iPhone).
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

let rutas = { ffmpeg: 'ffmpeg', ffprobe: 'ffprobe' };
function configurarBinarios(r) {
  rutas = { ...rutas, ...r };
}

function ejecutar(bin, args, { alProgreso, duracion } = {}) {
  return new Promise((resolve, reject) => {
    const p = spawn(bin, args, { windowsHide: true });
    let salida = '';
    let errores = '';
    p.stdout.on('data', (d) => {
      salida += d;
      if (alProgreso && duracion) {
        const m = /out_time_us=(\d+)/g;
        let x, ult;
        while ((x = m.exec(salida))) ult = x[1];
        if (ult) alProgreso(Math.min(1, Number(ult) / 1e6 / duracion));
        if (salida.length > 1e5) salida = salida.slice(-2000);
      }
    });
    p.stderr.on('data', (d) => (errores = (errores + d).slice(-4000)));
    p.on('error', reject);
    p.on('close', (code) => (code === 0 ? resolve(salida) : reject(new Error(`${path.basename(bin)} terminó con código ${code}: ${errores.trim().split('\n').slice(-3).join(' ')}`))));
  });
}

async function sondear(archivo) {
  try {
    const j = JSON.parse(await ejecutar(rutas.ffprobe, ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', archivo]));
    const v = (j.streams || []).find((s) => s.codec_type === 'video');
    const a = (j.streams || []).find((s) => s.codec_type === 'audio');
    const duracion = Number(j.format?.duration) || Number(v?.duration) || 0;
    if (!v) return { valido: false, motivo: 'no tiene pista de video' };
    if (!(duracion > 0)) return { valido: false, motivo: 'duración inválida' };
    return { valido: true, duracion, ancho: v.width, alto: v.height, codecVideo: v.codec_name, codecAudio: a?.codec_name || null, contenedor: j.format?.format_name };
  } catch (e) {
    return { valido: false, motivo: `no se pudo leer (${e.message})` };
  }
}

// Convierte la grabación cruda (MP4 fragmentado o WebM de MediaRecorder) a un MP4 estándar.
// Si el video ya es H.264 se copia sin recodificar (rápido y sin perder calidad).
async function finalizar(entrada, salida, { titulo, creado, alProgreso } = {}) {
  const info = await sondear(entrada).catch(() => null);
  const copiarVideo = info?.codecVideo === 'h264';
  const args = ['-y', '-v', 'error', '-fflags', '+genpts', '-i', entrada, '-map', '0:v:0', '-map', '0:a:0?'];
  if (copiarVideo) args.push('-c:v', 'copy');
  else args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-pix_fmt', 'yuv420p', '-r', '30');
  args.push('-c:a', info?.codecAudio === 'aac' ? 'copy' : 'aac', '-b:a', '160k');
  args.push('-movflags', '+faststart', '-metadata', `title=${titulo || ''}`);
  if (creado) args.push('-metadata', `creation_time=${new Date(creado).toISOString()}`);
  args.push('-progress', 'pipe:1', '-f', 'mp4');
  const tmp = `${salida}.tmp`;
  await ejecutar(rutas.ffmpeg, [...args, tmp], { alProgreso, duracion: info?.duracion });
  const v = await sondear(tmp);
  if (!v.valido) {
    fs.rmSync(tmp, { force: true });
    throw new Error(`El MP4 final no es válido: ${v.motivo}`);
  }
  fs.renameSync(tmp, salida);
  return v;
}

// Recupera lo que se pueda de una grabación interrumpida (cierre inesperado).
async function recuperar(parcial, salida, opciones = {}) {
  try {
    return await finalizar(parcial, salida, opciones);
  } catch {
    // Segundo intento: recodificar ignorando el final dañado.
    const tmp = `${salida}.tmp`;
    await ejecutar(rutas.ffmpeg, ['-y', '-v', 'error', '-err_detect', 'ignore_err', '-i', parcial, '-map', '0:v:0', '-map', '0:a:0?', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-movflags', '+faststart', '-f', 'mp4', tmp]);
    const v = await sondear(tmp);
    if (!v.valido) {
      fs.rmSync(tmp, { force: true });
      throw new Error(v.motivo);
    }
    fs.renameSync(tmp, salida);
    return v;
  }
}

async function miniatura(video, salida, segundo = 1) {
  try {
    await ejecutar(rutas.ffmpeg, ['-y', '-v', 'error', '-ss', String(segundo), '-i', video, '-frames:v', '1', '-vf', 'scale=480:-2', salida]);
    if (fs.existsSync(salida) && fs.statSync(salida).size > 0) return salida;
  } catch {}
  if (segundo > 0) return miniatura(video, salida, 0);
  return null;
}

// Recorte exacto (recodifica para cortar en cualquier cuadro). El original no se toca.
async function recortar(entrada, salida, inicio, fin, { titulo, alProgreso } = {}) {
  const dur = Math.max(0.1, fin - inicio);
  const tmp = `${salida}.tmp`;
  await ejecutar(
    rutas.ffmpeg,
    ['-y', '-v', 'error', '-ss', String(inicio), '-i', entrada, '-t', String(dur), '-map', '0:v:0', '-map', '0:a:0?', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', '-metadata', `title=${titulo || ''}`, '-metadata', `creation_time=${new Date().toISOString()}`, '-progress', 'pipe:1', '-f', 'mp4', tmp],
    { alProgreso, duracion: dur },
  );
  const v = await sondear(tmp);
  if (!v.valido) {
    fs.rmSync(tmp, { force: true });
    throw new Error(`El recorte no es válido: ${v.motivo}`);
  }
  fs.renameSync(tmp, salida);
  return v;
}

module.exports = { configurarBinarios, sondear, finalizar, recuperar, miniatura, recortar };
