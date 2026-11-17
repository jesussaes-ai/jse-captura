// Cola de sincronización persistente. El estado vive en la biblioteca (disco), así que sobrevive a reinicios.
// Destinos:
//   - "servicio": servicio NAS de JSE Captura por HTTP (recomendado; Android/iPhone usan el mismo).
//   - "smb": carpeta de red ya montada en Windows (\\NAS\carpeta o una unidad Z:), con la misma verificación SHA-256.
const fs = require('node:fs');
const path = require('node:path');
const { pipeline } = require('node:stream/promises');
const { ClienteNas, ErrorNas } = require('./nas');
const { sha256Archivo, nombreSeguro } = require('./archivos');

const ESPERAS_FOTOS = [20e3, 120e3, 600e3, 1800e3];

class Cola {
  constructor(biblioteca, obtenerAjustes, { registrar = console.log } = {}) {
    this.bib = biblioteca;
    this.ajustes = obtenerAjustes; // () => ({ modo, direccion, token, carpeta, smbCarpeta, ca })
    this.registrar = registrar;
    this.ocupado = false;
    this.otraVez = false;
    this.temporizador = null;
    this.pausada = false;
  }

  cliente() {
    const a = this.ajustes();
    if (a.modo !== 'servicio' || !a.direccion || !a.token) return null;
    return new ClienteNas({ direccion: a.direccion, token: a.token, ca: a.ca });
  }

  configurado() {
    const a = this.ajustes();
    return (a.modo === 'servicio' && a.direccion && a.token) || (a.modo === 'smb' && a.smbCarpeta);
  }

  programar(ms) {
    clearTimeout(this.temporizador);
    this.temporizador = setTimeout(() => this.procesar(), ms);
    this.temporizador.unref?.();
  }

  // "Sincronizar ahora": reintenta todo, incluso lo que requería atención.
  sincronizarAhora() {
    for (const g of this.bib.items.values()) {
      const n = g.destinos.nas;
      if (n.estado === 'requiere_atencion') this.bib.destino(g.id, 'nas', { estado: 'pendiente', siguiente: 0 }, { persistir: false });
      else if (n.estado === 'pendiente') n.siguiente = 0;
    }
    return this.procesar();
  }

  async procesar() {
    if (this.ocupado) {
      this.otraVez = true;
      return;
    }
    this.ocupado = true;
    try {
      do {
        this.otraVez = false;
        await this.vuelta();
      } while (this.otraVez);
    } finally {
      this.ocupado = false;
    }
  }

  async vuelta() {
    if (!this.configurado() || this.pausada) return;
    const ahora = Date.now();
    let proxima = Infinity;
    const lista = [...this.bib.items.values()].sort((a, b) => a.creado.localeCompare(b.creado));
    for (const g of lista) {
      const n = g.destinos.nas;
      if (n.estado === 'pendiente' && g.archivo) {
        if ((n.siguiente || 0) > ahora) {
          proxima = Math.min(proxima, n.siguiente);
          continue;
        }
        await this.copiar(g);
        if (g.destinos.nas.estado === 'pendiente') proxima = Math.min(proxima, g.destinos.nas.siguiente);
      } else if (n.estado === 'guardado' && n.metaPendiente) {
        await this.enviarMeta(g);
      }
      const f = g.destinos.fotosJse;
      if (n.estado === 'guardado' && f.estado === 'exportado' && this.ajustes().modo === 'servicio' && (f.siguienteRevision || 0) <= Date.now()) {
        await this.revisarFotosJse(g);
      }
      if (f.siguienteRevision > Date.now() && f.estado === 'exportado') proxima = Math.min(proxima, f.siguienteRevision);
    }
    if (proxima < Infinity) this.programar(Math.max(1000, proxima - Date.now()));
  }

  async copiar(g) {
    const a = this.ajustes();
    let ultimoGuardado = 0;
    const progreso = (hecho, total) => {
      const p = total ? hecho / total : 0;
      // Se guarda en disco como mucho cada 2 s; la interfaz recibe todos los avances.
      const persistir = Date.now() - ultimoGuardado > 2000;
      if (persistir) ultimoGuardado = Date.now();
      this.bib.destino(g.id, 'nas', { estado: 'copiando', progreso: p, motivo: null }, { persistir });
    };
    try {
      if (!fs.existsSync(g.archivo)) throw new ErrorNas(0, 'sin_archivo_local', 'El MP4 ya no está en este equipo; no se puede copiar.');
      this.bib.destino(g.id, 'nas', { estado: 'copiando', progreso: 0, motivo: null });
      if (!g.sha256) {
        g.sha256 = await sha256Archivo(g.archivo);
        this.bib.guardar();
      }
      let r;
      if (a.modo === 'smb') r = await this.copiarSmb(g, a.smbCarpeta, progreso);
      else
        r = await this.cliente().subir(
          g.archivo,
          {
            id: g.id,
            sha256: g.sha256,
            titulo: g.titulo,
            carpeta: a.carpeta || '',
            creado: g.creado,
            creadoLocal: g.creadoLocal,
            duracion: g.duracion,
            origen: g.origen,
            notas: g.notas,
            etiquetas: g.etiquetas,
            favorito: g.favorito,
            marcas: g.marcas,
            recorteDe: g.recorteDe,
          },
          { alProgreso: progreso, cancelado: () => this.pausada },
        );
      const remoto = r.grabacion || {};
      this.bib.destino(g.id, 'nas', { estado: 'guardado', progreso: 1, motivo: null, intentos: 0, ruta: remoto.rutaRelativa || r.ruta, copiado: new Date().toISOString() }, { persistir: false });
      const f = remoto.fotosJse || r.fotosJse || { estado: 'no_configurado' };
      this.bib.destino(g.id, 'fotosJse', { ...f, siguienteRevision: Date.now() + ESPERAS_FOTOS[0], revisiones: 0 });
      this.registrar(`NAS: ${g.titulo} guardado`);
    } catch (e) {
      const intentos = (g.destinos.nas.intentos || 0) + 1;
      if (e.codigo === 'cancelado') return this.bib.destino(g.id, 'nas', { estado: 'pendiente', motivo: 'En pausa' });
      const requiere = e.requiereUsuario || e.codigo === 'sin_archivo_local';
      const espera = Math.min(30 * 60e3, 30e3 * 2 ** Math.min(intentos - 1, 6));
      this.bib.destino(g.id, 'nas', {
        estado: requiere ? 'requiere_atencion' : 'pendiente',
        motivo: e.message,
        codigo: e.codigo,
        intentos,
        siguiente: Date.now() + espera,
      });
      this.registrar(`NAS: ${g.titulo} → ${e.codigo}: ${e.message}`);
    }
  }

  // Copia a una carpeta SMB montada: temporal oculto + SHA-256 + renombrado.
  async copiarSmb(g, carpeta, progreso) {
    if (!fs.existsSync(carpeta)) throw new ErrorNas(0, 'smb_no_disponible', `No se encuentra la carpeta ${carpeta}. ¿Está conectado el NAS?`);
    const f = new Date(g.creado);
    const z = (n) => String(n).padStart(2, '0');
    const nombre = `${path.basename(g.archivo, '.mp4')} [${g.id.slice(0, 8)}].mp4`;
    const destino = path.join(carpeta, String(f.getFullYear()), z(f.getMonth() + 1), nombreSeguro(nombre, 150));
    if (fs.existsSync(destino) && fs.statSync(destino).size === g.tamano && (await sha256Archivo(destino)) === g.sha256) return { ruta: destino };
    fs.mkdirSync(path.dirname(destino), { recursive: true });
    const tmp = path.join(path.dirname(destino), `.${path.basename(destino)}.part`);
    let hecho = 0;
    const lector = fs.createReadStream(g.archivo);
    lector.on('data', (t) => progreso((hecho += t.length), g.tamano));
    try {
      await pipeline(lector, fs.createWriteStream(tmp));
    } catch (e) {
      fs.rmSync(tmp, { force: true });
      if (e.code === 'ENOSPC') throw new ErrorNas(0, 'sin_espacio', 'La carpeta del NAS no tiene espacio.');
      if (e.code === 'EACCES' || e.code === 'EPERM') throw new ErrorNas(0, 'credenciales_invalidas', 'Windows no tiene permiso para escribir en esa carpeta del NAS.');
      throw new ErrorNas(0, e.code || 'smb', `Falló la copia a la carpeta de red: ${e.message}`);
    }
    if ((await sha256Archivo(tmp)) !== g.sha256) {
      fs.rmSync(tmp, { force: true });
      throw new ErrorNas(0, 'hash_no_coincide', 'La copia en el NAS no coincide con el original; se reintentará.');
    }
    fs.renameSync(tmp, destino);
    return { ruta: destino, fotosJse: { estado: 'exportado', motivo: 'Copiado a la carpeta de red. Fotos JSE lo mostrará si esa carpeta está en su biblioteca.' } };
  }

  async enviarMeta(g) {
    const c = this.cliente();
    if (!c) return;
    try {
      await c.editar(g.id, { titulo: g.titulo, notas: g.notas, etiquetas: g.etiquetas, favorito: g.favorito, marcas: g.marcas });
      this.bib.destino(g.id, 'nas', { metaPendiente: false });
    } catch (e) {
      this.registrar(`Metadatos de ${g.titulo}: ${e.message}`);
    }
  }

  async revisarFotosJse(g) {
    const c = this.cliente();
    if (!c) return;
    const f = g.destinos.fotosJse;
    try {
      const r = await c.comprobarFotosJse(g.id);
      const n = (f.revisiones || 0) + 1;
      this.bib.destino(g.id, 'fotosJse', { ...r, revisiones: n, siguienteRevision: r.estado === 'exportado' && n < ESPERAS_FOTOS.length ? Date.now() + ESPERAS_FOTOS[n] : null });
    } catch (e) {
      this.bib.destino(g.id, 'fotosJse', { siguienteRevision: Date.now() + ESPERAS_FOTOS.at(-1) });
    }
  }
}

module.exports = { Cola };
