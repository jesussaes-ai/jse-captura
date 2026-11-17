// Cliente del servicio NAS de JSE Captura (protocolo en docs/protocolo.md).
const fs = require('node:fs');
const http = require('node:http');
const https = require('node:https');

class ErrorNas extends Error {
  constructor(estado, codigo, mensaje, datos = {}) {
    super(mensaje || codigo);
    this.estado = estado;
    this.codigo = codigo;
    this.datos = datos;
    // Errores que no se arreglan reintentando solos: el usuario debe actuar.
    this.requiereUsuario = ['credenciales_invalidas', 'archivo_demasiado_grande', 'sin_espacio', 'carpeta_invalida', 'video_invalido', 'conflicto_id'].includes(codigo);
  }
}

const MENSAJES_RED = {
  ECONNREFUSED: 'El NAS no responde en esa dirección (¿apagado o el servicio detenido?).',
  EHOSTUNREACH: 'No se encuentra el NAS en la red. ¿Estás en casa o con Tailscale encendido?',
  ENETUNREACH: 'Sin red.',
  ENOTFOUND: 'No se reconoce el nombre del NAS.',
  ETIMEDOUT: 'El NAS tardó demasiado en responder.',
  ECONNRESET: 'Se cortó la conexión con el NAS.',
  DEPTH_ZERO_SELF_SIGNED_CERT: 'El certificado HTTPS del NAS no es de confianza en este equipo (ver guía, sección HTTPS).',
  SELF_SIGNED_CERT_IN_CHAIN: 'El certificado HTTPS del NAS no es de confianza en este equipo (ver guía, sección HTTPS).',
};

class ClienteNas {
  constructor({ direccion, token = null, ca = null, timeout = 30000 }) {
    this.base = new URL(direccion.includes('://') ? direccion : `http://${direccion}`);
    if (!this.base.port && this.base.protocol === 'http:' && !direccion.match(/:\d+/)) this.base.port = '8790';
    this.token = token;
    this.ca = ca;
    this.timeout = timeout;
  }

  peticion(metodo, ruta, { json, cuerpo, cabeceras = {}, alEnviar, timeout } = {}) {
    return new Promise((resolve, reject) => {
      const u = new URL(ruta, this.base);
      const mod = u.protocol === 'https:' ? https : http;
      const h = { Accept: 'application/json', ...cabeceras };
      if (this.token) h.Authorization = `Bearer ${this.token}`;
      let datos = null;
      if (json !== undefined) {
        datos = Buffer.from(JSON.stringify(json));
        h['Content-Type'] = 'application/json';
        h['Content-Length'] = datos.length;
      }
      const req = mod.request(u, { method: metodo, headers: h, ca: this.ca || undefined, timeout: timeout || this.timeout }, (res) => {
        const trozos = [];
        res.on('data', (t) => trozos.push(t));
        res.on('end', () => {
          const texto = Buffer.concat(trozos).toString('utf8');
          let j = null;
          try {
            j = texto ? JSON.parse(texto) : {};
          } catch {
            j = { error: 'respuesta_ilegible', mensaje: `Respuesta inesperada (HTTP ${res.statusCode}). ¿Es la dirección del servicio JSE Captura?` };
          }
          if (res.statusCode >= 400) return reject(new ErrorNas(res.statusCode, j.error || `http_${res.statusCode}`, j.mensaje, j));
          resolve(j);
        });
      });
      req.on('timeout', () => req.destroy(Object.assign(new Error('timeout'), { code: 'ETIMEDOUT' })));
      req.on('error', (e) => reject(new ErrorNas(0, e.code || 'red', MENSAJES_RED[e.code] || `Error de red: ${e.message}`)));
      if (cuerpo) {
        let enviados = 0;
        cuerpo.on('data', (t) => {
          enviados += t.length;
          if (alEnviar) alEnviar(enviados);
        });
        cuerpo.on('error', (e) => req.destroy(e));
        cuerpo.pipe(req);
      } else {
        req.end(datos || undefined);
      }
    });
  }

  salud() {
    return this.peticion('GET', '/api/v1/salud');
  }

  async iniciarSesion(usuario, contrasena, equipo) {
    const r = await this.peticion('POST', '/api/v1/sesion', { json: { usuario, contrasena, equipo } });
    this.token = r.token;
    return r;
  }

  cerrarSesion() {
    return this.peticion('DELETE', '/api/v1/sesion');
  }

  prueba() {
    return this.peticion('GET', '/api/v1/prueba');
  }

  carpetas() {
    return this.peticion('GET', '/api/v1/carpetas');
  }

  listar(params = {}) {
    const q = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')).toString();
    return this.peticion('GET', `/api/v1/grabaciones${q ? `?${q}` : ''}`);
  }

  editar(id, cambios) {
    return this.peticion('PATCH', `/api/v1/grabaciones/${id}`, { json: cambios });
  }

  eliminar(id) {
    return this.peticion('DELETE', `/api/v1/grabaciones/${id}`);
  }

  async enlace(id) {
    const r = await this.peticion('GET', `/api/v1/grabaciones/${id}/enlace`);
    return new URL(r.ruta, this.base).toString();
  }

  comprobarFotosJse(id) {
    // El NAS espera a que Fotos JSE indexe (hasta ~2 min).
    return this.peticion('POST', `/api/v1/grabaciones/${id}/fotos-jse`, { timeout: 200000 });
  }

  // Sube un archivo de forma reanudable. `meta` lleva id, sha256, titulo, carpeta, etc.
  // alProgreso(bytes, total). Devuelve la respuesta final del NAS (estado 'guardado').
  async subir(ruta, meta, { alProgreso, trozo = 16 * 1024 * 1024, cancelado = () => false } = {}) {
    const tamano = fs.statSync(ruta).size;
    let r = await this.peticion('POST', '/api/v1/subidas', { json: { ...meta, tamano } });
    while (r.estado !== 'guardado') {
      if (cancelado()) throw new ErrorNas(0, 'cancelado', 'Copia en pausa.');
      const desde = r.desplazamiento;
      const hasta = Math.min(tamano, desde + trozo) - 1;
      if (alProgreso) alProgreso(desde, tamano);
      try {
        r = await this.peticion('PATCH', `/api/v1/subidas/${meta.id}`, {
          cuerpo: fs.createReadStream(ruta, { start: desde, end: hasta }),
          cabeceras: { 'Upload-Offset': String(desde), 'Content-Type': 'application/offset+octet-stream', 'Content-Length': hasta - desde + 1 },
          alEnviar: (n) => alProgreso && alProgreso(desde + n, tamano),
        });
      } catch (e) {
        // El NAS dice desde dónde seguir: se continúa sin volver a empezar.
        if (e.codigo === 'desplazamiento_incorrecto' && Number.isFinite(e.datos.desplazamiento)) {
          r = { estado: 'incompleta', desplazamiento: e.datos.desplazamiento };
          continue;
        }
        throw e;
      }
    }
    if (alProgreso) alProgreso(tamano, tamano);
    return r;
  }
}

module.exports = { ClienteNas, ErrorNas };
