// Utilidades de archivos compartidas por el cliente de escritorio.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

function escribirJsonAtomico(destino, datos) {
  fs.mkdirSync(path.dirname(destino), { recursive: true });
  const tmp = `${destino}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`;
  const fd = fs.openSync(tmp, 'w');
  try {
    fs.writeSync(fd, JSON.stringify(datos, null, 2));
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(tmp, destino);
}

async function sha256Archivo(ruta, alAvanzar) {
  const h = crypto.createHash('sha256');
  const total = fs.statSync(ruta).size;
  let hecho = 0;
  for await (const t of fs.createReadStream(ruta, { highWaterMark: 1 << 20 })) {
    h.update(t);
    hecho += t.length;
    if (alAvanzar) alAvanzar(hecho, total);
  }
  return h.digest('hex');
}

function espacioLibre(dir) {
  try {
    const s = fs.statfsSync(dir);
    return s.bavail * s.bsize;
  } catch {
    return Infinity;
  }
}

function nombreSeguro(s, max = 80) {
  return String(s || '').replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/\s+/g, ' ').replace(/^[.\s]+|[.\s]+$/g, '').slice(0, max) || 'Grabación';
}

// Fecha local como "2026-11-16T14:05:09" (Fotos JSE agrupa por día local).
function fechaLocal(d = new Date()) {
  const z = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${z(d.getMonth() + 1)}-${z(d.getDate())}T${z(d.getHours())}:${z(d.getMinutes())}:${z(d.getSeconds())}`;
}

module.exports = { escribirJsonAtomico, sha256Archivo, espacioLibre, nombreSeguro, fechaLocal };
