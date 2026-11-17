// Ciclo de vida de una grabación en disco:
//   iniciar → los trozos de MediaRecorder se escriben al momento en Grabando/<id>.parcial (nada se queda en memoria)
//   detener → ffmpeg lo convierte a MP4 estándar en Videos/, se valida, se crea la miniatura y entra a la biblioteca
//   cierre inesperado → al abrir, recuperarPendientes() intenta salvar cada .parcial y avisa el resultado
const fs = require('node:fs');
const path = require('node:path');
const { escribirJsonAtomico, espacioLibre, nombreSeguro, fechaLocal } = require('./archivos');
const medios = require('./medios');

const MIN_LIBRE_INICIAR = 1.5 * 1024 ** 3; // ~25 min a 1080p
const MIN_LIBRE_SEGUIR = 400 * 1024 ** 2;

class Grabaciones {
  constructor(biblioteca, { alAvisar = () => {} } = {}) {
    this.bib = biblioteca;
    this.avisar = alAvisar;
    this.actual = null;
  }

  comprobarEspacio() {
    const libre = espacioLibre(this.bib.dir);
    return { libre, suficiente: libre >= MIN_LIBRE_INICIAR, minimo: MIN_LIBRE_INICIAR };
  }

  iniciar({ titulo, origen, extension = 'mp4', audio = {} }) {
    if (this.actual) throw new Error('Ya hay una grabación en curso.');
    const esp = this.comprobarEspacio();
    if (!esp.suficiente) {
      throw new Error(`Quedan ${(esp.libre / 1024 ** 3).toFixed(1)} GB libres; se necesitan al menos ${(esp.minimo / 1024 ** 3).toFixed(1)} GB para grabar. Libera espacio (puedes borrar del equipo las grabaciones que ya están en el NAS).`);
    }
    const id = this.bib.nuevoId();
    const ahora = new Date();
    const parcial = path.join(this.bib.temporales, `${id}.parcial.${extension}`);
    this.actual = {
      id,
      titulo: titulo || `Grabación ${fechaLocal(ahora).replace('T', ' ').slice(0, 16)}`,
      creado: ahora.toISOString(),
      creadoLocal: fechaLocal(ahora),
      origen,
      audio,
      parcial,
      bytes: 0,
      marcas: [],
      inicio: Date.now(),
    };
    escribirJsonAtomico(path.join(this.bib.temporales, `${id}.json`), this.actual);
    this.fd = fs.openSync(parcial, 'a');
    return this.actual;
  }

  // Trozo de MediaRecorder (cada segundo). Devuelve false si hay que detener por falta de espacio.
  escribir(buffer) {
    if (!this.actual) return true;
    fs.writeSync(this.fd, buffer);
    this.actual.bytes += buffer.length;
    if (this.actual.bytes % (64 * 1024 * 1024) < buffer.length) fs.fsyncSync(this.fd);
    return espacioLibre(this.bib.dir) > MIN_LIBRE_SEGUIR;
  }

  marcar(nota = '') {
    if (!this.actual) return null;
    const m = { t: (Date.now() - this.actual.inicio) / 1000, nota };
    this.actual.marcas.push(m);
    escribirJsonAtomico(path.join(this.bib.temporales, `${this.actual.id}.json`), this.actual);
    return m;
  }

  async detener({ alProgreso } = {}) {
    const a = this.actual;
    if (!a) return null;
    fs.fsyncSync(this.fd);
    fs.closeSync(this.fd);
    this.actual = null;
    return this.convertir(a, { alProgreso });
  }

  async convertir(a, { alProgreso, recuperacion = false } = {}) {
    const z = (n) => String(n).padStart(2, '0');
    const f = new Date(a.creado);
    const nombre = `${f.getFullYear()}-${z(f.getMonth() + 1)}-${z(f.getDate())} ${z(f.getHours())}.${z(f.getMinutes())}.${z(f.getSeconds())} ${nombreSeguro(a.titulo, 60)}.mp4`;
    const destino = path.join(this.bib.videos, nombre);
    const fn = recuperacion ? medios.recuperar : medios.finalizar;
    const info = await fn(a.parcial, destino, { titulo: a.titulo, creado: a.creado, alProgreso });
    const mini = await medios.miniatura(destino, path.join(this.bib.miniaturas, `${a.id}.jpg`));
    const g = this.bib.agregar({
      id: a.id,
      titulo: a.titulo,
      creado: a.creado,
      creadoLocal: a.creadoLocal,
      archivo: destino,
      miniatura: mini,
      duracion: info.duracion,
      ancho: info.ancho,
      alto: info.alto,
      tamano: fs.statSync(destino).size,
      origen: a.origen,
      audio: a.audio,
      marcas: a.marcas || [],
      recuperada: recuperacion || undefined,
    });
    fs.rmSync(a.parcial, { force: true });
    fs.rmSync(path.join(this.bib.temporales, `${a.id}.json`), { force: true });
    return g;
  }

  // Al abrir la app: grabaciones que quedaron a medias.
  async recuperarPendientes() {
    const resultados = [];
    for (const f of fs.readdirSync(this.bib.temporales)) {
      if (!f.endsWith('.json')) continue;
      let a;
      try {
        a = JSON.parse(fs.readFileSync(path.join(this.bib.temporales, f), 'utf8'));
      } catch {
        continue;
      }
      if (this.actual?.id === a.id || this.bib.obtener(a.id)) continue;
      if (!fs.existsSync(a.parcial) || fs.statSync(a.parcial).size === 0) {
        fs.rmSync(path.join(this.bib.temporales, f), { force: true });
        resultados.push({ id: a.id, titulo: a.titulo, ok: false, motivo: 'No se alcanzó a guardar nada.' });
        continue;
      }
      try {
        const g = await this.convertir(a, { recuperacion: true });
        resultados.push({ id: a.id, titulo: a.titulo, ok: true, duracion: g.duracion });
      } catch (e) {
        // No se borra: queda en Grabando/ para revisarlo a mano.
        resultados.push({ id: a.id, titulo: a.titulo, ok: false, motivo: e.message, archivo: a.parcial });
      }
    }
    return resultados;
  }

  // Recorte: crea una grabación NUEVA; la original conserva su id, archivo y duración.
  async recortar(id, inicio, fin, { alProgreso } = {}) {
    const o = this.bib.obtener(id);
    if (!o?.archivo || !fs.existsSync(o.archivo)) throw new Error('El original no está en este equipo.');
    if (!(fin > inicio) || inicio < 0 || fin > o.duracion + 0.05) throw new Error('Rango de recorte inválido.');
    const nuevoId = this.bib.nuevoId();
    const titulo = `${o.titulo} (recorte)`;
    const destino = path.join(this.bib.videos, `${path.basename(o.archivo, '.mp4')} recorte ${nuevoId.slice(0, 4)}.mp4`);
    const info = await medios.recortar(o.archivo, destino, inicio, fin, { titulo, alProgreso });
    const ahora = new Date();
    return this.bib.agregar({
      id: nuevoId,
      titulo,
      creado: ahora.toISOString(),
      creadoLocal: fechaLocal(ahora),
      archivo: destino,
      miniatura: await medios.miniatura(destino, path.join(this.bib.miniaturas, `${nuevoId}.jpg`)),
      duracion: info.duracion,
      ancho: info.ancho,
      alto: info.alto,
      tamano: fs.statSync(destino).size,
      origen: { ...o.origen, modo: `Recorte de ${o.titulo}` },
      etiquetas: [...(o.etiquetas || [])],
      marcas: (o.marcas || []).filter((m) => m.t >= inicio && m.t <= fin).map((m) => ({ ...m, t: m.t - inicio })),
      recorteDe: o.id,
    });
  }
}

module.exports = { Grabaciones };
