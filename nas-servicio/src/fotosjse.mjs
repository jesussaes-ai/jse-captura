// Integración con Fotos JSE (repositorio jesussaes-ai/fotos-jse).
//
// Cómo recibe videos Fotos JSE: indexa todo lo que haya en su biblioteca del NAS (`Fotos-JSE/biblioteca`, montada
// como /photos) cada 10 minutos y al pedir POST /api/scan. Las carpetas que empiezan con "." se ignoran.
// Por eso el modo recomendado es "misma-carpeta": JSE_GRABACIONES apunta a `Fotos-JSE/biblioteca/JSE Captura`
// y Fotos JSE muestra la MISMA copia (no se duplica nada en el NAS).
//
// Estados que ve el usuario:
//   pendiente           → aún no se exporta / comprueba
//   exportado           → el MP4 está en la carpeta que indexa Fotos JSE (Exportado a la carpeta)
//   visible             → Fotos JSE lo devolvió en su API y sirvió sus primeros bytes (Visible en Fotos JSE)
//   requiere_atencion   → falló la copia o la comprobación; `motivo` lo explica
import fs from 'node:fs';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { sha256Archivo } from './util.mjs';

const espera = (ms) => new Promise((r) => setTimeout(r, ms));

export class FotosJse {
  constructor(cfg, almacen, { fetchImpl = globalThis.fetch, intervalo = 15000 } = {}) {
    this.cfg = cfg;
    this.almacen = almacen;
    this.fetch = fetchImpl;
    this.intervalo = intervalo;
  }

  estado(g, estado, motivo = null, extra = {}) {
    g.fotosJse = { estado, motivo, actualizado: new Date().toISOString(), ...extra };
    this.almacen.guardarMeta(g);
    return g.fotosJse;
  }

  // Copia con verificación al modo "copiar" (otra carpeta de importación).
  async copiar(g) {
    const origen = this.almacen.ruta(g);
    const destino = path.join(this.cfg.fotosJse.carpeta, ...g.rutaRelativa.split('/'));
    if (fs.existsSync(destino) && fs.statSync(destino).size === g.tamano && (await sha256Archivo(destino)) === g.sha256) return destino;
    fs.mkdirSync(path.dirname(destino), { recursive: true });
    const tmp = path.join(path.dirname(destino), `.${path.basename(destino)}.part`);
    try {
      fs.linkSync(origen, tmp); // mismo volumen: enlace duro, sin ocupar espacio
    } catch {
      await pipeline(fs.createReadStream(origen), fs.createWriteStream(tmp));
    }
    if ((await sha256Archivo(tmp)) !== g.sha256) {
      fs.rmSync(tmp, { force: true });
      throw new Error('La copia para Fotos JSE no coincide con el original (SHA-256).');
    }
    fs.renameSync(tmp, destino);
    return destino;
  }

  async exportar(g) {
    const { modo } = this.cfg.fotosJse;
    if (modo === 'no') return this.estado(g, 'no_configurado');
    try {
      if (modo === 'copiar') {
        if (!this.cfg.fotosJse.carpeta) throw new Error('Falta JSE_FOTOS_CARPETA para el modo "copiar".');
        await this.copiar(g);
      } else if (!fs.existsSync(this.almacen.ruta(g))) {
        throw new Error('El MP4 ya no está en la carpeta del NAS.');
      }
    } catch (err) {
      return this.estado(g, 'requiere_atencion', `No se pudo exportar a la carpeta de Fotos JSE: ${err.message}`);
    }
    this.estado(g, 'exportado', this.cfg.fotosJse.url ? null : 'Sin JSE_FOTOS_URL no se puede comprobar si ya aparece en la galería.');
    if (this.cfg.fotosJse.url) return this.comprobar(g);
    return g.fotosJse;
  }

  async pedir(ruta, opciones = {}) {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), opciones.timeout || 15000);
    try {
      return await this.fetch(this.cfg.fotosJse.url + ruta, { ...opciones, signal: ctl.signal });
    } finally {
      clearTimeout(t);
    }
  }

  async buscar(g) {
    const dia = (g.creadoLocal || g.creado || '').slice(0, 10);
    for (const carpeta of ['recientes', dia].filter(Boolean)) {
      const r = await this.pedir(`/api/explorar?carpeta=${encodeURIComponent(carpeta)}`);
      if (!r.ok) continue;
      const j = await r.json();
      const it = (j.items || []).find((x) => x.nombre === g.nombreArchivo && (!x.tam || x.tam === g.tamano));
      if (it) return it;
    }
    return null;
  }

  // Pide a Fotos JSE que escanee y espera a que el video aparezca y se pueda leer.
  async comprobar(g) {
    try {
      // El escaneo completo puede tardar minutos: no se espera la respuesta.
      this.pedir('/api/scan', { method: 'POST', timeout: 600000 }).catch(() => {});
      const fin = Date.now() + this.cfg.fotosJse.espera * 1000;
      let it = null;
      while (!(it = await this.buscar(g)) && Date.now() < fin) await espera(this.intervalo);
      if (!it) return this.estado(g, 'exportado', 'Fotos JSE todavía no lo indexa (lo hace cada 10 minutos). Usa «Comprobar en Fotos JSE» más tarde.');
      const r = await this.pedir(`/m/${it.id}/file`, { headers: { Range: 'bytes=0-1023' } });
      if (r.status !== 206 && r.status !== 200) return this.estado(g, 'requiere_atencion', `Fotos JSE lo lista pero no lo sirve (HTTP ${r.status}).`, { fotosJseId: it.id });
      await r.body?.cancel?.();
      return this.estado(g, 'visible', null, { fotosJseId: it.id, url: `${this.cfg.fotosJse.url}/?m=${it.id}` });
    } catch (err) {
      return this.estado(g, 'exportado', `No se pudo contactar a Fotos JSE en ${this.cfg.fotosJse.url} (${err.cause?.code || err.message}). El video ya está en su carpeta y aparecerá en el siguiente escaneo.`);
    }
  }
}
