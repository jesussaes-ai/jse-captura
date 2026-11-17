// Biblioteca local: un JSON (escrito de forma atómica) con todas las grabaciones y el estado de cada destino.
// Funciona desde el primer uso, sin NAS. El id (UUID) de cada grabación no cambia nunca.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { escribirJsonAtomico } = require('./archivos');

// Estados de destino que ve el usuario.
const ESTADOS = {
  pendiente: 'Pendiente',
  copiando: 'Copiando',
  guardado: 'Guardado',
  exportado: 'Exportado a la carpeta',
  visible: 'Visible en Fotos JSE',
  requiere_atencion: 'Requiere atención',
  no_configurado: 'Sin configurar',
};

class Biblioteca extends EventEmitter {
  constructor(dir) {
    super();
    this.dir = dir;
    this.videos = path.join(dir, 'Videos');
    this.temporales = path.join(dir, 'Grabando');
    this.miniaturas = path.join(dir, 'Miniaturas');
    this.archivo = path.join(dir, 'biblioteca.json');
    for (const d of [this.videos, this.temporales, this.miniaturas]) fs.mkdirSync(d, { recursive: true });
    this.items = new Map();
    if (fs.existsSync(this.archivo)) {
      try {
        for (const g of JSON.parse(fs.readFileSync(this.archivo, 'utf8')).grabaciones || []) this.items.set(g.id, g);
      } catch (e) {
        // Biblioteca dañada: se conserva una copia y se empieza de nuevo (los MP4 siguen en Videos).
        fs.copyFileSync(this.archivo, `${this.archivo}.dañada-${Date.now()}`);
      }
    }
    // Lo que quedó "Copiando" al cerrarse la app vuelve a "Pendiente".
    for (const g of this.items.values()) if (g.destinos?.nas?.estado === 'copiando') g.destinos.nas.estado = 'pendiente';
  }

  guardar() {
    escribirJsonAtomico(this.archivo, { version: 1, grabaciones: [...this.items.values()] });
    this.emit('cambio');
  }

  nuevoId() {
    return crypto.randomUUID();
  }

  agregar(g) {
    const ahora = new Date().toISOString();
    const item = {
      titulo: 'Grabación',
      notas: '',
      etiquetas: [],
      favorito: false,
      marcas: [],
      recorteDe: null,
      creado: ahora,
      ...g,
      actualizado: ahora,
      destinos: {
        dispositivo: { estado: 'guardado' },
        nas: { estado: 'pendiente', progreso: 0, intentos: 0 },
        fotosJse: { estado: 'pendiente' },
        ...(g.destinos || {}),
      },
    };
    this.items.set(item.id, item);
    this.guardar();
    return item;
  }

  obtener(id) {
    return this.items.get(id) || null;
  }

  editar(id, cambios) {
    const g = this.items.get(id);
    if (!g) return null;
    for (const k of ['titulo', 'notas', 'etiquetas', 'favorito', 'marcas']) if (k in cambios) g[k] = cambios[k];
    g.actualizado = new Date().toISOString();
    // Los metadatos también viajan al NAS (si ya está guardado allí se actualizan con PATCH).
    if (g.destinos.nas.estado === 'guardado') g.destinos.nas.metaPendiente = true;
    this.guardar();
    return g;
  }

  destino(id, nombre, cambios, { persistir = true } = {}) {
    const g = this.items.get(id);
    if (!g) return null;
    g.destinos[nombre] = { ...(g.destinos[nombre] || {}), ...cambios };
    if (persistir) this.guardar();
    else this.emit('cambio');
    return g;
  }

  // Borrar del equipo. NO toca la copia del NAS (eso es otra operación).
  borrarLocal(id) {
    const g = this.items.get(id);
    if (!g) return null;
    for (const f of [g.archivo, g.miniatura]) if (f) fs.rmSync(f, { force: true });
    if (g.destinos.nas.estado === 'guardado') {
      // Se conserva la ficha para seguir viéndola desde el NAS.
      g.archivo = null;
      g.miniatura = null;
      g.destinos.dispositivo = { estado: 'borrado' };
    } else {
      this.items.delete(id);
    }
    this.guardar();
    return { ubicacion: 'Este equipo', quedaEnNas: g.destinos.nas.estado === 'guardado' };
  }

  listar({ q = '', pendientes = false, favoritos = false } = {}) {
    const t = q.toLowerCase().trim();
    return [...this.items.values()]
      .filter((g) => !pendientes || ['pendiente', 'copiando', 'requiere_atencion'].includes(g.destinos.nas.estado))
      .filter((g) => !favoritos || g.favorito)
      .filter((g) => !t || [g.titulo, g.notas, ...(g.etiquetas || [])].join(' ').toLowerCase().includes(t))
      .sort((a, b) => b.creado.localeCompare(a.creado));
  }
}

module.exports = { Biblioteca, ESTADOS };
