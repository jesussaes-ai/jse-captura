// Pruebas del servicio NAS: node --test test/
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { leerConfig } from '../src/config.mjs';
import { crearServicio } from '../src/servidor.mjs';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'jse-nas-'));
const MP4 = path.join(tmp, 'prueba.mp4');
let base, svc, token, fotosFalso, fotosVistos;

// MP4 real H.264/AAC de 3 s (lo que entregan los clientes).
execFileSync('ffmpeg', ['-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=30', '-f', 'lavfi', '-i', 'sine=frequency=440',
  '-t', '3', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-movflags', '+faststart', MP4]);
const datos = fs.readFileSync(MP4);
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');

async function api(ruta, { method = 'GET', body, headers = {}, auth = true } = {}) {
  const h = { ...headers };
  if (auth && token) h.Authorization = `Bearer ${token}`;
  let cuerpo = body;
  if (body && !(body instanceof Buffer)) {
    cuerpo = JSON.stringify(body);
    h['Content-Type'] = 'application/json';
  }
  const r = await fetch(base + ruta, { method, body: cuerpo, headers: h });
  const tipo = r.headers.get('content-type') || '';
  return { status: r.status, headers: r.headers, json: tipo.includes('json') ? await r.json() : null, bytes: tipo.includes('json') ? null : Buffer.from(await r.arrayBuffer()) };
}

const subir = (id, trozo, desde) => api(`/api/v1/subidas/${id}`, { method: 'PATCH', body: trozo, headers: { 'Upload-Offset': String(desde), 'Content-Type': 'application/offset+octet-stream' } });

function nuevaSubida(extra = {}) {
  return { id: crypto.randomUUID(), tamano: datos.length, sha256: sha(datos), titulo: 'Prueba pantalla', creado: '2026-11-16T20:00:00Z', creadoLocal: '2026-11-16T14:00:00', origen: { plataforma: 'windows', modo: 'monitor 2' }, ...extra };
}

before(async () => {
  // Fotos JSE falso: lista el video cuando aparece en la carpeta y sirve sus bytes con Range.
  fotosVistos = [];
  fotosFalso = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    if (u.pathname === '/api/scan') return res.end('{}');
    if (u.pathname === '/api/explorar') {
      const items = [];
      for (const g of svc.almacen.catalogo.values()) if (fs.existsSync(svc.almacen.ruta(g))) items.push({ id: 77, nombre: g.nombreArchivo, tam: g.tamano });
      res.setHeader('Content-Type', 'application/json');
      return res.end(JSON.stringify({ items }));
    }
    if (u.pathname === '/m/77/file') {
      fotosVistos.push(req.headers.range);
      res.writeHead(206);
      return res.end('x');
    }
    res.writeHead(404).end();
  });
  await new Promise((r) => fotosFalso.listen(0, '127.0.0.1', r));
  const cfg = leerConfig({
    JSE_DATOS: path.join(tmp, 'datos'),
    JSE_GRABACIONES: path.join(tmp, 'grabaciones'),
    JSE_CONTRASENA: 'secreta-de-prueba',
    JSE_FOTOS_URL: `http://127.0.0.1:${fotosFalso.address().port}`,
    JSE_FOTOS_ESPERA_S: '5',
    JSE_MAX_ARCHIVO_GB: '0.01', // 10 MB para probar "demasiado grande"
  });
  svc = crearServicio(cfg, { fotosJse: { intervalo: 200 } });
  await new Promise((r) => svc.servidor.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${svc.servidor.address().port}`;
});

after(() => {
  svc.servidor.close();
  fotosFalso.close();
  fs.rmSync(tmp, { recursive: true, force: true });
});

test('salud y credenciales', async () => {
  assert.equal((await api('/api/v1/salud', { auth: false })).json.ok, true);
  const sin = await api('/api/v1/grabaciones', { auth: false });
  assert.equal(sin.status, 401);
  assert.equal(sin.json.error, 'credenciales_invalidas');
  const mala = await api('/api/v1/sesion', { method: 'POST', auth: false, body: { usuario: 'jse-captura', contrasena: 'no' } });
  assert.equal(mala.status, 401);
  const ok = await api('/api/v1/sesion', { method: 'POST', auth: false, body: { usuario: 'jse-captura', contrasena: 'secreta-de-prueba', equipo: 'PC prueba' } });
  assert.equal(ok.status, 200);
  token = ok.json.token;
  const prueba = await api('/api/v1/prueba');
  assert.equal(prueba.json.ok, true);
  assert.equal(prueba.json.fotosJse.alcanzable, true);
  // El token se guarda como hash, nunca en claro.
  assert.ok(!fs.readFileSync(path.join(tmp, 'datos', 'equipos.json'), 'utf8').includes(token));
});

test('subida en partes, publicación atómica y metadatos', async () => {
  const s = nuevaSubida();
  const c = await api('/api/v1/subidas', { method: 'POST', body: s });
  assert.equal(c.status, 201);
  assert.equal(c.json.desplazamiento, 0);
  const t = Math.floor(datos.length / 3);
  assert.equal((await subir(s.id, datos.subarray(0, t), 0)).json.desplazamiento, t);
  // Mientras está incompleto, no aparece en el catálogo ni fuera de la carpeta oculta.
  assert.equal((await api('/api/v1/grabaciones')).json.total, 0);
  assert.equal((await subir(s.id, datos.subarray(t, 2 * t), t)).json.desplazamiento, 2 * t);
  const fin = await subir(s.id, datos.subarray(2 * t), 2 * t);
  assert.equal(fin.status, 201);
  assert.equal(fin.json.estado, 'guardado');
  const g = fin.json.grabacion;
  assert.equal(g.video.codec, 'h264');
  assert.equal(g.audio.codec, 'aac');
  assert.ok(g.duracion > 2.5 && g.duracion < 3.5);
  assert.match(g.rutaRelativa, /^2026\/11\/2026-11-16 .* Prueba pantalla \[[0-9a-f]{8}\]\.mp4$/);
  const enDisco = fs.readFileSync(path.join(tmp, 'grabaciones', ...g.rutaRelativa.split('/')));
  assert.equal(sha(enDisco), s.sha256);
});

test('corte de red a mitad y reintento tras respuesta perdida: una sola copia íntegra', async () => {
  const s = nuevaSubida({ titulo: 'Corte de red' });
  await api('/api/v1/subidas', { method: 'POST', body: s });
  // Enviar la mitad y cortar el socket sin terminar.
  const mitad = Math.floor(datos.length / 2);
  await new Promise((resolve) => {
    const u = new URL(base);
    const req = http.request({ host: u.hostname, port: u.port, path: `/api/v1/subidas/${s.id}`, method: 'PATCH', headers: { Authorization: `Bearer ${token}`, 'Upload-Offset': '0', 'Content-Length': datos.length } });
    req.on('error', () => {});
    req.write(datos.subarray(0, mitad), () => setTimeout(() => { req.destroy(); resolve(); }, 150));
  });
  await new Promise((r) => setTimeout(r, 150));
  const estado = await api(`/api/v1/subidas/${s.id}`);
  assert.equal(estado.json.estado, 'incompleta');
  const hecho = estado.json.desplazamiento;
  assert.ok(hecho > 0 && hecho <= mitad, `desplazamiento ${hecho}`);
  // Desplazamiento equivocado → 409 con el correcto.
  const mal = await subir(s.id, datos.subarray(0, 10), 0);
  assert.equal(mal.status, 409);
  assert.equal(mal.json.desplazamiento, hecho);
  const fin = await subir(s.id, datos.subarray(hecho), hecho);
  assert.equal(fin.json.estado, 'guardado');
  // La respuesta se "perdió": el cliente repite todo desde el inicio.
  const otra = await api('/api/v1/subidas', { method: 'POST', body: s });
  assert.equal(otra.status, 200);
  assert.equal(otra.json.estado, 'guardado');
  const otra2 = await subir(s.id, datos, 0);
  assert.equal(otra2.json.estado, 'guardado');
  const copias = [];
  const recorrer = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) e.isDirectory() ? recorrer(path.join(d, e.name)) : copias.push(e.name); };
  recorrer(path.join(tmp, 'grabaciones'));
  assert.equal(copias.filter((n) => n.includes(s.id.slice(0, 8))).length, 1);
});

test('hash incorrecto, archivo no MP4, demasiado grande y sin espacio', async () => {
  const s = nuevaSubida({ sha256: 'a'.repeat(64) });
  await api('/api/v1/subidas', { method: 'POST', body: s });
  const r = await subir(s.id, datos, 0);
  assert.equal(r.status, 422);
  assert.equal(r.json.error, 'hash_no_coincide');
  assert.equal((await api(`/api/v1/subidas/${s.id}`)).status, 404);

  const basura = crypto.randomBytes(50000);
  const b = { ...nuevaSubida(), tamano: basura.length, sha256: sha(basura) };
  await api('/api/v1/subidas', { method: 'POST', body: b });
  const rb = await subir(b.id, basura, 0);
  assert.equal(rb.status, 422);
  assert.equal(rb.json.error, 'video_invalido');

  const grande = await api('/api/v1/subidas', { method: 'POST', body: nuevaSubida({ tamano: 50 * 1024 ** 2 }) });
  assert.equal(grande.status, 413);
  assert.equal(grande.json.error, 'archivo_demasiado_grande');

  const reserva = svc.almacen.cfg.reservaLibre;
  svc.almacen.cfg.reservaLibre = Number.MAX_SAFE_INTEGER;
  const lleno = await api('/api/v1/subidas', { method: 'POST', body: nuevaSubida() });
  svc.almacen.cfg.reservaLibre = reserva;
  assert.equal(lleno.status, 507);
  assert.equal(lleno.json.error, 'sin_espacio');
});

test('reproducir con Range y con enlace firmado para VLC/mpv', async () => {
  const [g] = (await api('/api/v1/grabaciones?q=prueba')).json.elementos;
  const parcial = await api(`/api/v1/grabaciones/${g.id}/video`, { headers: { Range: 'bytes=100-199' } });
  assert.equal(parcial.status, 206);
  assert.equal(parcial.bytes.length, 100);
  assert.deepEqual(parcial.bytes, datos.subarray(100, 200));
  assert.equal(parcial.headers.get('content-range'), `bytes 100-199/${datos.length}`);
  const e = await api(`/api/v1/grabaciones/${g.id}/enlace`);
  const v = await api(e.json.ruta, { auth: false, headers: { Range: 'bytes=0-9' } });
  assert.equal(v.status, 206);
  const vencido = await api(e.json.ruta.replace(/exp=\d+/, 'exp=1000'), { auth: false });
  assert.equal(vencido.status, 403);
});

test('Fotos JSE: exportado y visible', async () => {
  const [g] = (await api('/api/v1/grabaciones?q=prueba')).json.elementos;
  let f;
  for (let i = 0; i < 30; i++) {
    f = (await api(`/api/v1/grabaciones/${g.id}`)).json.fotosJse;
    if (f.estado === 'visible') break;
    await new Promise((r) => setTimeout(r, 200));
  }
  assert.equal(f.estado, 'visible');
  assert.equal(f.fotosJseId, 77);
  assert.ok(fotosVistos.includes('bytes=0-1023'));
});

test('editar, buscar, favoritos, marcas y borrar solo la copia del NAS', async () => {
  const [g] = (await api('/api/v1/grabaciones?q=corte')).json.elementos;
  const e = await api(`/api/v1/grabaciones/${g.id}`, { method: 'PATCH', body: { titulo: 'Reunión obra', notas: 'revisar planos', etiquetas: ['obra'], favorito: true, marcas: [{ t: 1.5, nota: 'aquí' }] } });
  assert.equal(e.json.id, g.id);
  assert.equal(e.json.titulo, 'Reunión obra');
  assert.equal(e.json.rutaRelativa, g.rutaRelativa, 'cambiar el título conserva el archivo');
  assert.equal((await api('/api/v1/grabaciones?q=planos')).json.total, 1);
  assert.equal((await api('/api/v1/grabaciones?favoritos=1')).json.total, 1);
  const b = await api(`/api/v1/grabaciones/${g.id}`, { method: 'DELETE' });
  assert.equal(b.json.eliminado, true);
  assert.equal(b.json.ubicacion, g.rutaRelativa);
  assert.equal((await api(`/api/v1/grabaciones/${g.id}`)).status, 404);
});

test('recuperación tras caída entre mover y catalogar', async () => {
  const s = nuevaSubida({ titulo: 'Caída' });
  await api('/api/v1/subidas', { method: 'POST', body: s });
  // Simular: el archivo ya se movió pero el catálogo no se escribió.
  const rel = '2026/11/caida.mp4';
  fs.mkdirSync(path.join(tmp, 'grabaciones', '2026', '11'), { recursive: true });
  fs.writeFileSync(path.join(tmp, 'grabaciones', ...rel.split('/')), datos);
  fs.rmSync(svc.almacen.rutaParte(s.id));
  const sesion = JSON.parse(fs.readFileSync(path.join(tmp, 'datos', 'subidas', `${s.id}.json`)));
  fs.writeFileSync(path.join(tmp, 'datos', 'subidas', `${s.id}.json`), JSON.stringify({ ...sesion, publicando: { rel, nombre: 'caida.mp4' } }));
  const rec = await svc.almacen.recuperar();
  assert.deepEqual(rec, [s.id]);
  assert.equal((await api(`/api/v1/grabaciones/${s.id}`)).json.rutaRelativa, rel);
});

test('token revocado deja de ver el catálogo', async () => {
  const otro = svc.auth.crearToken('Teléfono perdido');
  const r1 = await fetch(`${base}/api/v1/grabaciones`, { headers: { Authorization: `Bearer ${otro.token}` } });
  assert.equal(r1.status, 200);
  svc.auth.revocar(otro.equipoId);
  const r2 = await fetch(`${base}/api/v1/grabaciones`, { headers: { Authorization: `Bearer ${otro.token}` } });
  assert.equal(r2.status, 401);
});
