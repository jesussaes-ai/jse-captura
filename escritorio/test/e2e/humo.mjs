// Prueba de humo de la app real (Electron) con Playwright: elegir pantalla → grabar 6 s → detener → biblioteca → MP4 válido.
// Linux: xvfb-run -s "-screen 0 1920x1080x24" node test/e2e/humo.mjs     Windows: node test/e2e/humo.mjs
import { createRequire } from 'node:module';
const { _electron: electron } = createRequire(import.meta.url)(process.env.PW || 'playwright');
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const perfil = fs.mkdtempSync(path.join(process.env.HUMO_DIR || os.tmpdir(), 'jse-humo-'));
const app = await electron.launch({ executablePath: process.env.ELECTRON_BIN || undefined, args: ['.', '--no-sandbox'], env: { ...process.env, JSE_PERFIL: perfil } });
const w = await app.firstWindow();
w.on('console', (m) => console.log('[ui]', m.text()));
await w.waitForSelector('#lista-pantallas .fuente', { timeout: 20000 });
console.log('Fuentes:', await w.$$eval('.fuente span', (l) => l.map((x) => x.textContent)));
await w.screenshot({ path: path.join(perfil, '1-grabar.png') });
await w.fill('#op-titulo', 'Prueba de humo');
await w.click('#btn-grabar');
await w.waitForSelector('#panel-grabando:not([hidden])', { timeout: 15000 });
await w.waitForTimeout(6000);
await w.click('#btn-marcar');
await w.waitForTimeout(1000);
await w.click('#btn-detener');
await w.waitForSelector('#vista-biblioteca:not([hidden]) .video', { timeout: 60000 });
await w.screenshot({ path: path.join(perfil, '2-biblioteca.png') });
const lib = JSON.parse(fs.readFileSync(path.join(perfil, 'videos', 'JSE Captura', 'biblioteca.json'), 'utf8')).grabaciones;
const g = lib[0];
const info = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-print_format', 'json', '-show_streams', '-show_format', g.archivo]));
console.log('MP4:', g.archivo, info.streams.map((s) => `${s.codec_type}:${s.codec_name} ${s.width || ''}x${s.height || ''}`), 'duración', info.format.duration, 'marcas', g.marcas);
await w.click('.video .mini');
await w.waitForTimeout(1500);
await w.screenshot({ path: path.join(perfil, '3-reproductor.png') });
await app.close();
console.log('Perfil y capturas en', perfil);
