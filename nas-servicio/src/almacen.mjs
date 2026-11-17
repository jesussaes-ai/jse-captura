// Catálogo y subidas reanudables.
//
// Protocolo (parecido a tus, simplificado):
//   1. POST /api/v1/subidas            { id, tamano, sha256, titulo, carpeta, ... }  → { desplazamiento }
//   2. PATCH /api/v1/subidas/:id        cabecera Upload-Offset = desplazamiento, cuerpo = bytes  → { desplazamiento }
//      (se repite hasta llegar a `tamano`; si la conexión se corta, GET /api/v1/subidas/:id dice desde dónde seguir)
//   3. Al llegar al tamaño: SHA-256, ffprobe, fsync y renombrado atómico al catálogo. Respuesta 201 { estado: 'guardado' }.
// Repetir cualquier paso con el mismo id es seguro: si ya está guardado con el mismo SHA-256 responde 200 'guardado'.
import fs from 'node:fs';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { carpetaSegura, escribirJsonAtomico, espacioLibre, esSha, esUuid, miniatura, nombreSeguro, sha256Archivo, sondear } from './util.mjs';

export class ErrorApi extends Error {
  constructor(estado, codigo, mensaje, extra = {}) {
    super(mensaje);
    this.estado = estado;
    this.codigo = codigo;
    this.extra = extra;
  }
}

const EDITABLES = ['titulo', 'notas', 'etiquetas', 'favorito', 'marcas'];

export class Almacen {
  constructor(cfg, { alGuardar } = {}) {
    this.cfg = cfg;
    this.dirCatalogo = path.join(cfg.datos, 'catalogo');
    this.dirSubidas = path.join(cfg.datos, 'subidas');
    this.dirMinis = path.join(cfg.datos, 'miniaturas');
    for (const d of [this.dirCatalogo, this.dirSubidas, this.dirMinis, cfg.grabaciones, cfg.incompletos]) fs.mkdirSync(d, { recursive: true });
    this.catalogo = new Map();
    for (const f of fs.readdirSync(this.dirCatalogo)) {
      if (!f.endsWith('.json')) continue;
      try {
        const g = JSON.parse(fs.readFileSync(path.join(this.dirCatalogo, f), 'utf8'));
        this.catalogo.set(g.id, g);
      } catch {
        // JSON dañado: se ignora (la escritura es atómica, no debería pasar).
      }
    }
    this.ocupadas = new Set(); // ids con un PATCH en curso
    this.alGuardar = alGuardar || (() => {});
  }

  // ---------- catálogo ----------
  guardarMeta(g) {
    escribirJsonAtomico(path.join(this.dirCatalogo, `${g.id}.json`), g);
    this.catalogo.set(g.id, g);
  }

  ruta(g) {
    return path.join(this.cfg.grabaciones, ...g.rutaRelativa.split('/'));
  }

  obtener(id) {
    const g = this.catalogo.get(id);
    if (!g) throw new ErrorApi(404, 'no_encontrada', 'Esa grabación no está en el NAS.');
    return g;
  }

  listar({ q = '', favoritos = false, pagina = 1, limite = 50, desde = '' } = {}) {
    const texto = String(q).toLowerCase().trim();
    let l = [...this.catalogo.values()];
    if (favoritos) l = l.filter((g) => g.favorito);
    if (desde) l = l.filter((g) => g.actualizado > desde);
    if (texto) l = l.filter((g) => [g.titulo, g.notas, ...(g.etiquetas || [])].join(' ').toLowerCase().includes(texto));
    l.sort((a, b) => (b.creado || '').localeCompare(a.creado || ''));
    limite = Math.min(Math.max(1, Number(limite) || 50), 200);
    pagina = Math.max(1, Number(pagina) || 1);
    return { total: l.length, pagina, limite, elementos: l.slice((pagina - 1) * limite, pagina * limite) };
  }

  editar(id, cambios) {
    const g = this.obtener(id);
    for (const k of EDITABLES) {
      if (!(k in cambios)) continue;
      const v = cambios[k];
      if (k === 'titulo') g.titulo = nombreSeguro(v, 120);
      if (k === 'notas') g.notas = String(v || '').slice(0, 5000);
      if (k === 'etiquetas') g.etiquetas = (Array.isArray(v) ? v : []).map((e) => String(e).trim().slice(0, 40)).filter(Boolean).slice(0, 30);
      if (k === 'favorito') g.favorito = !!v;
      if (k === 'marcas') g.marcas = (Array.isArray(v) ? v : []).map((m) => ({ t: Math.max(0, Number(m.t) || 0), nota: String(m.nota || '').slice(0, 200) })).slice(0, 500);
    }
    g.actualizado = new Date().toISOString();
    this.guardarMeta(g);
    return g;
  }

  eliminar(id) {
    const g = this.obtener(id);
    fs.rmSync(this.ruta(g), { force: true });
    fs.rmSync(path.join(this.dirMinis, `${id}.jpg`), { force: true });
    fs.rmSync(path.join(this.dirCatalogo, `${id}.json`), { force: true });
    this.catalogo.delete(id);
    return { eliminado: true, ubicacion: g.rutaRelativa };
  }

  miniaturaDe(id) {
    const f = path.join(this.dirMinis, `${id}.jpg`);
    return fs.existsSync(f) ? f : null;
  }

  // ---------- subidas ----------
  rutaParte(id) {
    return path.join(this.cfg.incompletos, `${id}.part`);
  }

  sesion(id) {
    const f = path.join(this.dirSubidas, `${id}.json`);
    return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null;
  }

  estadoSubida(id) {
    if (!esUuid(id)) throw new ErrorApi(400, 'id_invalido', 'El identificador debe ser un UUID.');
    const g = this.catalogo.get(id);
    if (g) return { id, estado: 'guardado', desplazamiento: g.tamano, tamano: g.tamano, sha256: g.sha256, grabacion: g };
    const s = this.sesion(id);
    if (!s) throw new ErrorApi(404, 'subida_desconocida', 'No hay una subida con ese identificador. Empieza de nuevo.');
    const hecho = fs.existsSync(this.rutaParte(id)) ? fs.statSync(this.rutaParte(id)).size : 0;
    return { id, estado: 'incompleta', desplazamiento: hecho, tamano: s.tamano };
  }

  crearSubida(d, equipo) {
    if (!esUuid(d.id)) throw new ErrorApi(400, 'id_invalido', 'El identificador debe ser un UUID.');
    if (!esSha(d.sha256)) throw new ErrorApi(400, 'sha256_invalido', 'Falta el SHA-256 del archivo (64 caracteres hexadecimales).');
    const tamano = Number(d.tamano);
    if (!Number.isSafeInteger(tamano) || tamano <= 0) throw new ErrorApi(400, 'tamano_invalido', 'El tamaño del archivo no es válido.');
    if (tamano > this.cfg.maxArchivo)
      throw new ErrorApi(413, 'archivo_demasiado_grande', `El archivo pesa ${(tamano / 1024 ** 3).toFixed(1)} GB y el máximo del NAS es ${(this.cfg.maxArchivo / 1024 ** 3).toFixed(0)} GB.`);

    const ya = this.catalogo.get(d.id);
    if (ya) {
      if (ya.sha256 === d.sha256.toLowerCase()) return { id: d.id, estado: 'guardado', desplazamiento: ya.tamano, tamano: ya.tamano, grabacion: ya };
      throw new ErrorApi(409, 'conflicto_id', 'Ya existe otra grabación con ese identificador y distinto contenido.');
    }
    const carpeta = carpetaSegura(d.carpeta ?? '');
    if (carpeta === null) throw new ErrorApi(400, 'carpeta_invalida', 'La carpeta de destino no es válida.');

    const previa = this.sesion(d.id);
    if (previa && (previa.sha256 !== d.sha256.toLowerCase() || previa.tamano !== tamano)) {
      // Mismo id, otro archivo (p. ej. el cliente lo regeneró): se reinicia.
      fs.rmSync(this.rutaParte(d.id), { force: true });
    }
    const hecho = fs.existsSync(this.rutaParte(d.id)) ? fs.statSync(this.rutaParte(d.id)).size : 0;
    const libre = espacioLibre(this.cfg.grabaciones);
    if (libre - (tamano - hecho) < this.cfg.reservaLibre)
      throw new ErrorApi(507, 'sin_espacio', 'El NAS no tiene espacio suficiente para esta grabación. Libera espacio y reintenta.', { libre, necesario: tamano - hecho });

    const s = {
      id: d.id,
      tamano,
      sha256: d.sha256.toLowerCase(),
      carpeta,
      titulo: nombreSeguro(d.titulo || 'Grabación', 120),
      creado: d.creado && !Number.isNaN(Date.parse(d.creado)) ? new Date(d.creado).toISOString() : new Date().toISOString(),
      // Fecha local del equipo (AAAA-MM-DDTHH:mm:ss): Fotos JSE agrupa por día local.
      creadoLocal: /^\d{4}-\d{2}-\d{2}/.test(String(d.creadoLocal || '')) ? String(d.creadoLocal).slice(0, 25) : null,
      duracion: Number(d.duracion) || null,
      origen: {
        plataforma: String(d.origen?.plataforma || '').slice(0, 30),
        equipo: String(d.origen?.equipo || equipo?.equipo || '').slice(0, 80),
        modo: String(d.origen?.modo || '').slice(0, 60),
      },
      notas: String(d.notas || '').slice(0, 5000),
      etiquetas: Array.isArray(d.etiquetas) ? d.etiquetas.map(String).slice(0, 30) : [],
      favorito: !!d.favorito,
      marcas: Array.isArray(d.marcas) ? d.marcas.slice(0, 500) : [],
      recorteDe: esUuid(d.recorteDe) ? d.recorteDe : null,
      equipoId: equipo?.id || null,
      iniciada: previa?.iniciada || new Date().toISOString(),
    };
    escribirJsonAtomico(path.join(this.dirSubidas, `${d.id}.json`), s);
    if (!fs.existsSync(this.rutaParte(d.id))) fs.writeFileSync(this.rutaParte(d.id), '');
    return { id: d.id, estado: 'incompleta', desplazamiento: hecho, tamano };
  }

  async recibirTrozo(id, desplazamiento, req) {
    if (!esUuid(id)) throw new ErrorApi(400, 'id_invalido', 'El identificador debe ser un UUID.');
    if (this.catalogo.has(id)) {
      req.resume();
      return this.estadoSubida(id);
    }
    const s = this.sesion(id);
    if (!s) {
      req.resume();
      throw new ErrorApi(404, 'subida_desconocida', 'No hay una subida con ese identificador. Empieza de nuevo.');
    }
    if (this.ocupadas.has(id)) {
      req.resume();
      throw new ErrorApi(409, 'subida_en_curso', 'Esta grabación ya se está recibiendo desde otra conexión.');
    }
    const parte = this.rutaParte(id);
    const actual = fs.existsSync(parte) ? fs.statSync(parte).size : 0;
    if (Number(desplazamiento) !== actual) {
      req.resume();
      throw new ErrorApi(409, 'desplazamiento_incorrecto', 'El NAS tiene otra cantidad de bytes; continúa desde el desplazamiento indicado.', { desplazamiento: actual });
    }
    this.ocupadas.add(id);
    let recibidos = actual;
    try {
      const limite = new Transform({
        transform(trozo, _enc, cb) {
          recibidos += trozo.length;
          if (recibidos > s.tamano) return cb(new ErrorApi(413, 'excede_tamano', 'Se enviaron más bytes de los declarados.'));
          cb(null, trozo);
        },
      });
      try {
        await pipeline(req, limite, fs.createWriteStream(parte, { flags: 'a' }));
      } catch (err) {
        if (err instanceof ErrorApi) throw err; // el trozo que se pasaba no se escribió
        if (err.code === 'ENOSPC') throw new ErrorApi(507, 'sin_espacio', 'El NAS se quedó sin espacio durante la copia. Lo recibido se conserva para continuar.');
        // Conexión cortada: lo recibido queda en el temporal y se puede continuar.
        throw new ErrorApi(400, 'transferencia_incompleta', 'La conexión se cortó antes de terminar. Continúa desde el desplazamiento indicado.', {
          desplazamiento: fs.statSync(parte).size,
        });
      }
      const hecho = fs.statSync(parte).size;
      if (hecho < s.tamano) return { id, estado: 'incompleta', desplazamiento: hecho, tamano: s.tamano };
      return await this.publicar(s);
    } finally {
      this.ocupadas.delete(id);
    }
  }

  async publicar(s) {
    const parte = this.rutaParte(s.id);
    const hash = await sha256Archivo(parte);
    if (hash !== s.sha256) {
      fs.rmSync(parte, { force: true });
      fs.rmSync(path.join(this.dirSubidas, `${s.id}.json`), { force: true });
      throw new ErrorApi(422, 'hash_no_coincide', 'El archivo llegó dañado (el SHA-256 no coincide). Se descartó; vuelve a enviarlo.', { recibido: hash, esperado: s.sha256 });
    }
    const sonda = await sondear(parte, this.cfg.ffprobe);
    if (sonda && !sonda.valido) {
      fs.rmSync(parte, { force: true });
      fs.rmSync(path.join(this.dirSubidas, `${s.id}.json`), { force: true });
      throw new ErrorApi(422, 'video_invalido', `El archivo no es un MP4 reproducible: ${sonda.motivo}.`);
    }
    // fsync del contenido antes de publicarlo.
    const fd = fs.openSync(parte, 'r+');
    fs.fsyncSync(fd);
    fs.closeSync(fd);

    const f = new Date(s.creado);
    const dos = (n) => String(n).padStart(2, '0');
    const sello = `${f.getFullYear()}-${dos(f.getMonth() + 1)}-${dos(f.getDate())} ${dos(f.getHours())}.${dos(f.getMinutes())}.${dos(f.getSeconds())}`;
    const nombre = `${sello} ${nombreSeguro(s.titulo, 60)} [${s.id.slice(0, 8)}].mp4`;
    const rel = [s.carpeta, String(f.getFullYear()), dos(f.getMonth() + 1), nombre].filter(Boolean).join('/');
    const destino = path.join(this.cfg.grabaciones, ...rel.split('/'));
    fs.mkdirSync(path.dirname(destino), { recursive: true });
    // Se anota el destino antes de mover: si el servicio se cae justo aquí, al arrancar se completa el catálogo.
    escribirJsonAtomico(path.join(this.dirSubidas, `${s.id}.json`), { ...s, publicando: { rel, nombre } });
    fs.renameSync(parte, destino); // atómico: mismo volumen
    try {
      const dfd = fs.openSync(path.dirname(destino), 'r');
      fs.fsyncSync(dfd);
      fs.closeSync(dfd);
    } catch {
      // Algunos sistemas (SMB) no permiten fsync de carpetas.
    }
    return this.registrar(s, rel, nombre, sonda);
  }

  registrar(s, rel, nombre, sonda) {
    const destino = path.join(this.cfg.grabaciones, ...rel.split('/'));
    const ahora = new Date().toISOString();
    const g = {
      id: s.id,
      titulo: s.titulo,
      nombreArchivo: nombre,
      rutaRelativa: rel,
      carpeta: s.carpeta,
      tamano: s.tamano,
      sha256: s.sha256,
      creado: s.creado,
      creadoLocal: s.creadoLocal || null,
      recibido: ahora,
      actualizado: ahora,
      duracion: sonda?.duracion ?? s.duracion,
      video: sonda?.video || null,
      audio: sonda?.audio || null,
      origen: s.origen,
      notas: s.notas,
      etiquetas: s.etiquetas,
      favorito: s.favorito,
      marcas: s.marcas,
      recorteDe: s.recorteDe,
      equipoId: s.equipoId,
      fotosJse: { estado: this.cfg.fotosJse.modo === 'no' ? 'no_configurado' : 'pendiente' },
    };
    this.guardarMeta(g);
    fs.rmSync(path.join(this.dirSubidas, `${s.id}.json`), { force: true });
    // En segundo plano: miniatura y Fotos JSE.
    miniatura(destino, path.join(this.dirMinis, `${s.id}.jpg`), this.cfg.ffmpeg).catch(() => {});
    Promise.resolve(this.alGuardar(g)).catch(() => {});
    return { id: s.id, estado: 'guardado', desplazamiento: s.tamano, tamano: s.tamano, sha256: s.sha256, grabacion: g, nuevo: true };
  }

  // Al arrancar: subidas que se movieron al catálogo pero el servicio se cayó antes de anotarlas.
  async recuperar() {
    const recuperadas = [];
    for (const f of fs.readdirSync(this.dirSubidas)) {
      if (!f.endsWith('.json')) continue;
      const s = JSON.parse(fs.readFileSync(path.join(this.dirSubidas, f), 'utf8'));
      if (!s.publicando || this.catalogo.has(s.id)) continue;
      const destino = path.join(this.cfg.grabaciones, ...s.publicando.rel.split('/'));
      if (fs.existsSync(destino) && (await sha256Archivo(destino)) === s.sha256) {
        this.registrar(s, s.publicando.rel, s.publicando.nombre, await sondear(destino, this.cfg.ffprobe));
        recuperadas.push(s.id);
      }
    }
    return recuperadas;
  }

  // Borra temporales viejos (subidas abandonadas) y sesiones huérfanas.
  limpiarIncompletos() {
    const limite = Date.now() - this.cfg.horasIncompletos * 3600e3;
    let borrados = 0;
    for (const f of fs.readdirSync(this.cfg.incompletos)) {
      const p = path.join(this.cfg.incompletos, f);
      const id = f.replace(/\.part$/, '');
      if (this.ocupadas.has(id)) continue;
      if (fs.statSync(p).mtimeMs < limite) {
        fs.rmSync(p, { force: true });
        fs.rmSync(path.join(this.dirSubidas, `${id}.json`), { force: true });
        borrados++;
      }
    }
    for (const f of fs.readdirSync(this.dirSubidas)) {
      const id = f.replace(/\.json$/, '');
      if (!fs.existsSync(this.rutaParte(id)) && !this.catalogo.has(id) && !this.sesion(id)?.publicando) fs.rmSync(path.join(this.dirSubidas, f), { force: true });
    }
    return borrados;
  }
}
