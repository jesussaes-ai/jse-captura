// Pruebas del núcleo del cliente de escritorio contra el servicio NAS real (sin Electron).
//   node --test test/
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { Biblioteca } = require('../src/nucleo/biblioteca');
const { Grabaciones } = require('../src/nucleo/grabaciones');
const { Cola } = require('../src/nucleo/cola');
const { ClienteNas } = require('../src/nucleo/nas');
const medios = require('../src/nucleo/medios');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'jse-esc-'));
let nas, puerto, ajustes;

// Lo que entrega MediaRecorder de Chromium: MP4 fragmentado (H.264 + AAC) o WebM (VP8/VP9 + Opus).
function crudo(nombre, formato, segundos) {
  const f = path.join(tmp, nombre);
  const comun = ['-v', 'error', '-f', 'lavfi', '-i', `testsrc2=size=1920x1080:rate=30`, '-f', 'lavfi', '-i', 'sine=frequency=330', '-t', String(segundos)];
  if (formato === 'mp4') execFileSync('ffmpeg', [...comun, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-g', '30', '-c:a', 'aac', '-movflags', 'frag_keyframe+empty_moov+default_base_moof', '-f', 'mp4', f]);
  else execFileSync('ffmpeg', [...comun, '-c:v', 'libvpx', '-deadline', 'realtime', '-b:v', '2M', '-c:a', 'libopus', '-f', 'webm', f]);
  return fs.readFileSync(f);
}

async function arrancarNas() {
  const { leerConfig } = await import('../../nas-servicio/src/config.mjs');
  const { crearServicio } = await import('../../nas-servicio/src/servidor.mjs');
  const cfg = leerConfig({ JSE_DATOS: path.join(tmp, 'nas-datos'), JSE_GRABACIONES: path.join(tmp, 'nas-videos'), JSE_CONTRASENA: 'clave', JSE_FOTOS_MODO: 'misma-carpeta' });
  nas = crearServicio(cfg);
  await new Promise((r) => nas.servidor.listen(puerto || 0, '127.0.0.1', r));
  puerto = nas.servidor.address().port;
}

before(async () => {
  await arrancarNas();
  const c = new ClienteNas({ direccion: `http://127.0.0.1:${puerto}` });
  const { token } = await c.iniciarSesion('jse-captura', 'clave', 'PC prueba');
  ajustes = { modo: 'servicio', direccion: `http://127.0.0.1:${puerto}`, token, carpeta: '' };
});
after(() => {
  nas.servidor.close();
  fs.rmSync(tmp, { recursive: true, force: true });
});

const bibDir = () => path.join(tmp, 'equipo');

test('grabar (MP4 fragmentado por trozos) → MP4 estándar H.264/AAC con faststart → biblioteca', async () => {
  const bib = new Biblioteca(bibDir());
  const gr = new Grabaciones(bib);
  const datos = crudo('mr.mp4', 'mp4', 4);
  gr.iniciar({ titulo: 'Monitor 2', origen: { plataforma: 'windows', modo: 'Pantalla 2' } });
  for (let i = 0; i < datos.length; i += 65536) assert.ok(gr.escribir(datos.subarray(i, i + 65536)));
  gr.marcar('importante');
  const g = await gr.detener();
  assert.equal(bib.listar().length, 1);
  const info = await medios.sondear(g.archivo);
  assert.equal(info.codecVideo, 'h264');
  assert.equal(info.codecAudio, 'aac');
  assert.ok(info.duracion > 3.5);
  // faststart: el átomo moov va antes que mdat (se puede reproducir mientras se descarga).
  const cab = fs.readFileSync(g.archivo).subarray(0, 4096).toString('latin1');
  assert.ok(cab.indexOf('moov') > -1 && cab.indexOf('moov') < 4096, 'moov al inicio');
  assert.ok(g.miniatura && fs.existsSync(g.miniatura));
  assert.equal(g.marcas.length, 1);
  assert.equal(g.destinos.nas.estado, 'pendiente');
});

test('WebM (VP8/Opus) se convierte a H.264/AAC', async () => {
  const bib = new Biblioteca(bibDir());
  const gr = new Grabaciones(bib);
  gr.iniciar({ titulo: 'Ventana', origen: { plataforma: 'windows', modo: 'Ventana' }, extension: 'webm' });
  gr.escribir(crudo('mr.webm', 'webm', 3));
  const g = await gr.detener();
  const info = await medios.sondear(g.archivo);
  assert.equal(info.codecVideo, 'h264');
  assert.equal(info.codecAudio, 'aac');
});

test('cierre inesperado: se recupera lo grabado al volver a abrir', async () => {
  const bib = new Biblioteca(bibDir());
  const gr = new Grabaciones(bib);
  const datos = crudo('corte.mp4', 'mp4', 6);
  gr.iniciar({ titulo: 'Se fue la luz', origen: { plataforma: 'windows' } });
  gr.escribir(datos.subarray(0, Math.floor(datos.length * 0.7))); // el proceso "muere" aquí
  fs.closeSync(gr.fd);
  const bib2 = new Biblioteca(bibDir());
  const res = await new Grabaciones(bib2).recuperarPendientes();
  assert.equal(res.length, 1);
  assert.equal(res[0].ok, true, res[0].motivo);
  assert.ok(res[0].duracion > 2);
  assert.ok(bib2.listar({ q: 'luz' })[0].recuperada);
});

test('sin NAS: queda pendiente, sobrevive al reinicio y se copia al volver', async () => {
  nas.servidor.close();
  await new Promise((r) => setTimeout(r, 100));
  let bib = new Biblioteca(bibDir());
  let cola = new Cola(bib, () => ajustes, { registrar: () => {} });
  await cola.procesar();
  for (const g of bib.listar()) {
    assert.equal(g.destinos.nas.estado, 'pendiente');
    assert.match(g.destinos.nas.motivo, /NAS no responde/);
  }
  // Reinicio del cliente + NAS de vuelta.
  bib = new Biblioteca(bibDir());
  await arrancarNas();
  cola = new Cola(bib, () => ajustes, { registrar: () => {} });
  await cola.sincronizarAhora();
  const l = bib.listar();
  assert.equal(l.length, 3);
  for (const g of l) assert.equal(g.destinos.nas.estado, 'guardado', g.destinos.nas.motivo);
  const remoto = await new ClienteNas({ direccion: ajustes.direccion, token: ajustes.token }).listar();
  assert.equal(remoto.total, 3);
  // Repetir no duplica.
  for (const g of bib.listar()) bib.destino(g.id, 'nas', { estado: 'pendiente' });
  await cola.sincronizarAhora();
  assert.equal((await new ClienteNas({ direccion: ajustes.direccion, token: ajustes.token }).listar()).total, 3);
});

test('credenciales revocadas: Requiere atención y el MP4 local se conserva', async () => {
  const bib = new Biblioteca(bibDir());
  const gr = new Grabaciones(bib);
  gr.iniciar({ titulo: 'Sin permiso', origen: { plataforma: 'windows' } });
  gr.escribir(crudo('p.mp4', 'mp4', 2));
  const g = await gr.detener();
  const cola = new Cola(bib, () => ({ ...ajustes, token: 'jsec_inventado' }), { registrar: () => {} });
  await cola.procesar();
  const d = bib.obtener(g.id).destinos.nas;
  assert.equal(d.estado, 'requiere_atencion');
  assert.match(d.motivo, /no está vinculado/);
  assert.ok(fs.existsSync(g.archivo));
});

test('recortar 10 s: MP4 nuevo, original intacto', async () => {
  const bib = new Biblioteca(bibDir());
  const gr = new Grabaciones(bib);
  gr.iniciar({ titulo: 'Larga', origen: { plataforma: 'windows' } });
  gr.escribir(crudo('larga.mp4', 'mp4', 15));
  const o = await gr.detener();
  const r = await gr.recortar(o.id, 2, 12);
  assert.notEqual(r.id, o.id);
  assert.equal(r.recorteDe, o.id);
  assert.ok(Math.abs(r.duracion - 10) < 0.2, `duración ${r.duracion}`);
  const orig = await medios.sondear(o.archivo);
  assert.ok(Math.abs(orig.duracion - o.duracion) < 0.01);
  assert.equal(bib.obtener(o.id).archivo, o.archivo);
});

test('carpeta SMB montada: copia verificada sin temporales visibles', async () => {
  const bib = new Biblioteca(bibDir());
  const smb = path.join(tmp, 'smb');
  fs.mkdirSync(smb);
  for (const g of bib.listar()) bib.destino(g.id, 'nas', { estado: 'pendiente', siguiente: 0 });
  const cola = new Cola(bib, () => ({ modo: 'smb', smbCarpeta: smb }), { registrar: () => {} });
  await cola.procesar();
  const archivos = [];
  const rec = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => (e.isDirectory() ? rec(path.join(d, e.name)) : archivos.push(e.name)));
  rec(smb);
  assert.equal(archivos.length, bib.listar().filter((g) => g.archivo).length);
  assert.ok(archivos.every((n) => n.endsWith('.mp4') && !n.startsWith('.')));
});
