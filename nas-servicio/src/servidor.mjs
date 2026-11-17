// API HTTP del servicio NAS de JSE Captura. Solo red local / Tailscale: nunca publicar este puerto en Internet.
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { Almacen, ErrorApi } from './almacen.mjs';
import { Autenticacion } from './auth.mjs';
import { FotosJse } from './fotosjse.mjs';
import { espacioLibre } from './util.mjs';

const MAX_JSON = 256 * 1024;

function json(res, estado, datos) {
  const cuerpo = JSON.stringify(datos);
  res.writeHead(estado, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(cuerpo), 'Cache-Control': 'no-store' });
  res.end(cuerpo);
}

function error(res, e) {
  if (e instanceof ErrorApi) return json(res, e.estado, { error: e.codigo, mensaje: e.message, ...e.extra });
  console.error(e);
  return json(res, 500, { error: 'error_interno', mensaje: 'Error inesperado en el servicio del NAS.' });
}

async function leerJson(req) {
  let n = 0;
  const trozos = [];
  for await (const t of req) {
    n += t.length;
    if (n > MAX_JSON) throw new ErrorApi(413, 'cuerpo_grande', 'La petición es demasiado grande.');
    trozos.push(t);
  }
  if (!n) return {};
  try {
    return JSON.parse(Buffer.concat(trozos).toString('utf8'));
  } catch {
    throw new ErrorApi(400, 'json_invalido', 'El cuerpo no es JSON válido.');
  }
}

// Envía un archivo con soporte de Range (adelantar en el reproductor sin descargar todo).
function enviarArchivo(req, res, ruta, tipo, extra = {}) {
  let st;
  try {
    st = fs.statSync(ruta);
  } catch {
    throw new ErrorApi(404, 'archivo_no_disponible', 'El archivo no está en el NAS (¿se borró desde fuera?).');
  }
  const base = { 'Content-Type': tipo, 'Accept-Ranges': 'bytes', 'Cache-Control': 'private, max-age=0', ...extra };
  const rango = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
  if (rango && (rango[1] || rango[2])) {
    let ini = rango[1] ? Number(rango[1]) : st.size - Number(rango[2]);
    let fin = rango[1] && rango[2] ? Math.min(Number(rango[2]), st.size - 1) : st.size - 1;
    ini = Math.max(0, ini);
    if (ini >= st.size || ini > fin) {
      res.writeHead(416, { 'Content-Range': `bytes */${st.size}` });
      return res.end();
    }
    res.writeHead(206, { ...base, 'Content-Range': `bytes ${ini}-${fin}/${st.size}`, 'Content-Length': fin - ini + 1 });
    if (req.method === 'HEAD') return res.end();
    return fs.createReadStream(ruta, { start: ini, end: fin }).pipe(res);
  }
  res.writeHead(200, { ...base, 'Content-Length': st.size });
  if (req.method === 'HEAD') return res.end();
  fs.createReadStream(ruta).pipe(res);
}

export function crearServicio(cfg, opciones = {}) {
  const auth = new Autenticacion(cfg);
  let fotos;
  const almacen = new Almacen(cfg, { alGuardar: (g) => fotos.exportar(g) });
  fotos = new FotosJse(cfg, almacen, opciones.fotosJse);
  const t0 = Date.now();

  const servidor = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://local');
    const p = url.pathname;
    const m = req.method;
    const ip = req.socket.remoteAddress || '';
    try {
      // ---------- sin autenticación ----------
      if (p === '/api/v1/salud') return json(res, 200, { servicio: 'jse-captura-nas', version: cfg.version, ok: true });
      if (p === '/api/v1/sesion' && m === 'POST') {
        const r = auth.iniciarSesion(await leerJson(req), ip);
        if (r.error === 'demasiados_intentos') return json(res, 429, { error: r.error, mensaje: 'Demasiados intentos fallidos. Espera 10 minutos.' });
        if (r.error === 'sin_contrasena_configurada') return json(res, 503, { error: r.error, mensaje: 'El servicio no tiene JSE_CONTRASENA configurada en el NAS.' });
        if (r.error) return json(res, 401, { error: r.error, mensaje: 'Usuario o contraseña incorrectos.' });
        return json(res, 200, r);
      }
      // Video con enlace firmado (VLC, mpv, navegador, galería): /v/<id>.mp4?exp=..&firma=..
      const enlace = /^\/v\/([0-9a-f-]{36})\.mp4$/.exec(p);
      if (enlace && (m === 'GET' || m === 'HEAD')) {
        if (!auth.firmaValida(`video:${enlace[1]}`, url.searchParams.get('exp'), url.searchParams.get('firma')))
          return json(res, 403, { error: 'enlace_vencido', mensaje: 'El enlace venció o no es válido. Pide uno nuevo desde la app.' });
        const g = almacen.obtener(enlace[1]);
        return enviarArchivo(req, res, almacen.ruta(g), 'video/mp4', { 'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(g.nombreArchivo)}` });
      }

      // ---------- con token de equipo ----------
      const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
      const equipo = auth.verificar(token);
      if (!equipo) {
        req.resume();
        return json(res, 401, { error: 'credenciales_invalidas', mensaje: 'Este equipo no está vinculado o su acceso fue revocado. Vuelve a iniciar sesión.' });
      }

      if (p === '/api/v1/sesion' && m === 'DELETE') return json(res, 200, { revocados: auth.revocar(equipo.id) });

      // Probar conexión: permisos de escritura, espacio y estado de Fotos JSE.
      if (p === '/api/v1/prueba') {
        const prueba = path.join(cfg.incompletos, `.prueba-${Date.now()}`);
        let escritura = true;
        try {
          fs.writeFileSync(prueba, 'ok');
          fs.rmSync(prueba);
        } catch {
          escritura = false;
        }
        let fotosJse = { modo: cfg.fotosJse.modo, url: cfg.fotosJse.url || null, alcanzable: null };
        if (cfg.fotosJse.url) {
          try {
            const r = await fotos.pedir('/api/explorar', { timeout: 5000 });
            fotosJse.alcanzable = r.ok;
          } catch (e) {
            fotosJse.alcanzable = false;
            fotosJse.motivo = e.cause?.code || e.message;
          }
        }
        return json(res, 200, {
          ok: escritura,
          equipo: equipo.equipo,
          escritura,
          motivo: escritura ? null : 'El servicio no puede escribir en la carpeta de grabaciones (revisa permisos del volumen).',
          espacioLibre: espacioLibre(cfg.grabaciones),
          maxArchivo: cfg.maxArchivo,
          grabaciones: almacen.catalogo.size,
          fotosJse,
          activoDesde: new Date(t0).toISOString(),
        });
      }

      if (p === '/api/v1/carpetas') {
        const l = [''];
        for (const e of fs.readdirSync(cfg.grabaciones, { withFileTypes: true }))
          if (e.isDirectory() && !/^[.#@$]/.test(e.name) && !/^\d{4}$/.test(e.name)) l.push(e.name);
        return json(res, 200, { carpetas: l });
      }

      // ---------- subidas ----------
      if (p === '/api/v1/subidas' && m === 'POST') {
        const r = almacen.crearSubida(await leerJson(req), equipo);
        return json(res, r.estado === 'guardado' ? 200 : 201, r);
      }
      const sub = /^\/api\/v1\/subidas\/([^/]+)$/.exec(p);
      if (sub && (m === 'GET' || m === 'HEAD')) return json(res, 200, almacen.estadoSubida(sub[1]));
      if (sub && m === 'PATCH') {
        const off = req.headers['upload-offset'];
        if (off === undefined || !/^\d+$/.test(off)) {
          req.resume();
          throw new ErrorApi(400, 'falta_desplazamiento', 'Falta la cabecera Upload-Offset.');
        }
        const r = await almacen.recibirTrozo(sub[1], Number(off), req);
        return json(res, r.nuevo ? 201 : 200, r);
      }

      // ---------- grabaciones ----------
      if (p === '/api/v1/grabaciones' && m === 'GET') {
        const s = url.searchParams;
        return json(res, 200, almacen.listar({ q: s.get('q') || '', favoritos: s.get('favoritos') === '1', pagina: s.get('pagina'), limite: s.get('limite'), desde: s.get('desde') || '' }));
      }
      const gm = /^\/api\/v1\/grabaciones\/([0-9a-f-]{36})(?:\/(video|miniatura|enlace|fotos-jse))?$/.exec(p);
      if (gm) {
        const [, id, accion] = gm;
        if (!accion && m === 'GET') return json(res, 200, almacen.obtener(id));
        if (!accion && m === 'PATCH') return json(res, 200, almacen.editar(id, await leerJson(req)));
        if (!accion && m === 'DELETE') {
          const g = almacen.obtener(id);
          const r = almacen.eliminar(id);
          if (cfg.fotosJse.modo === 'copiar' && url.searchParams.get('tambienFotosJse') === '1')
            fs.rmSync(path.join(cfg.fotosJse.carpeta, ...g.rutaRelativa.split('/')), { force: true });
          return json(res, 200, { ...r, afectaFotosJse: cfg.fotosJse.modo === 'misma-carpeta' });
        }
        if (accion === 'video' && (m === 'GET' || m === 'HEAD')) {
          const g = almacen.obtener(id);
          return enviarArchivo(req, res, almacen.ruta(g), 'video/mp4');
        }
        if (accion === 'miniatura') {
          const f = almacen.miniaturaDe(id);
          if (!f) throw new ErrorApi(404, 'sin_miniatura', 'Aún no hay miniatura.');
          return enviarArchivo(req, res, f, 'image/jpeg');
        }
        if (accion === 'enlace') {
          almacen.obtener(id);
          const { exp, firma } = auth.firmar(`video:${id}`);
          return json(res, 200, { ruta: `/v/${id}.mp4?exp=${exp}&firma=${firma}`, vence: new Date(exp * 1000).toISOString() });
        }
        if (accion === 'fotos-jse' && m === 'POST') {
          const g = almacen.obtener(id);
          return json(res, 200, await fotos.exportar(g));
        }
      }
      req.resume();
      return json(res, 404, { error: 'ruta_desconocida', mensaje: `No existe ${m} ${p}` });
    } catch (e) {
      return error(res, e);
    }
  });
  // Subidas de varios GB por Wi-Fi: sin límite de tiempo por petición, pero sí por inactividad.
  servidor.requestTimeout = 0;
  servidor.timeout = 120000;
  return { servidor, almacen, auth, fotos };
}
