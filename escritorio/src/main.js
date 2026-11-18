// JSE Captura · Windows (Electron). Proceso principal.
// Captura: desktopCapturer + getDisplayMedia (en Windows 10 2004+/11 Chromium usa Windows Graphics Capture).
// Codificación: MediaRecorder (H.264 por hardware cuando está disponible) → ffmpeg incluido → MP4 H.264/AAC estándar.
const { app, BrowserWindow, desktopCapturer, globalShortcut, ipcMain, Menu, Notification, safeStorage, screen, session, shell, Tray, clipboard, nativeImage, powerMonitor, dialog } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { Biblioteca } = require('./nucleo/biblioteca');
const { Grabaciones } = require('./nucleo/grabaciones');
const { Cola } = require('./nucleo/cola');
const { ClienteNas } = require('./nucleo/nas');
const { escribirJsonAtomico } = require('./nucleo/archivos');
const medios = require('./nucleo/medios');

// Perfil aparte (pruebas automáticas o una segunda configuración): JSE_PERFIL=C:\ruta
if (process.env.JSE_PERFIL) {
  app.setPath('userData', path.join(process.env.JSE_PERFIL, 'datos'));
  app.setPath('videos', path.join(process.env.JSE_PERFIL, 'videos'));
}
if (!app.requestSingleInstanceLock()) app.quit();

// ---------- binarios de ffmpeg incluidos ----------
const desempacado = (p) => p && p.replace('app.asar', 'app.asar.unpacked');
try {
  medios.configurarBinarios({ ffmpeg: desempacado(require('ffmpeg-static')), ffprobe: desempacado(require('ffprobe-static').path) });
} catch {
  // En desarrollo sin dependencias: se usa ffmpeg del PATH.
}

// ---------- ajustes ----------
const RUTA_AJUSTES = () => path.join(app.getPath('userData'), 'ajustes.json');
const RUTA_TOKEN = () => path.join(app.getPath('userData'), 'token-nas.bin');
const PREDETERMINADOS = {
  carpetaLocal: null,
  calidad: '1080', // 1080 | 720
  fps: 30,
  // Audio encendido por defecto (un video sin audio no sirve); se puede apagar en la pantalla principal.
  microfono: true,
  audioSistema: true,
  versionAjustes: 2,
  atajo: 'CommandOrControl+Shift+R',
  atajoMarca: 'CommandOrControl+Shift+M',
  modo: 'servicio', // servicio | smb | ninguno
  direccion: '',
  usuario: 'jse-captura',
  carpeta: '',
  smbCarpeta: '',
  fotosJseEnSmb: false,
};
let ajustes = { ...PREDETERMINADOS };
function cargarAjustes() {
  try {
    const guardados = JSON.parse(fs.readFileSync(RUTA_AJUSTES(), 'utf8'));
    // v0.1.0 guardaba el audio apagado por defecto: al actualizar se enciende una vez; después manda lo que elija el usuario.
    if ((guardados.versionAjustes || 1) < 2) Object.assign(guardados, { microfono: true, audioSistema: true, versionAjustes: 2 });
    ajustes = { ...PREDETERMINADOS, ...guardados };
  } catch {}
  if (!ajustes.carpetaLocal) ajustes.carpetaLocal = path.join(app.getPath('videos'), 'JSE Captura');
}
function guardarAjustes(cambios) {
  ajustes = { ...ajustes, ...cambios };
  escribirJsonAtomico(RUTA_AJUSTES(), ajustes);
  return ajustes;
}

// Token del NAS cifrado con DPAPI (almacén de credenciales de Windows para este usuario).
function leerToken() {
  try {
    return safeStorage.decryptString(fs.readFileSync(RUTA_TOKEN()));
  } catch {
    return null;
  }
}
function guardarToken(t) {
  if (!t) return fs.rmSync(RUTA_TOKEN(), { force: true });
  if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows no ofrece cifrado de credenciales para este usuario.');
  fs.writeFileSync(RUTA_TOKEN(), safeStorage.encryptString(t));
}

// ---------- estado ----------
let ventana, indicador, bandeja, bib, grab, cola;
let fuentePendiente = null; // fuente elegida para el próximo getDisplayMedia
let grabando = null; // { id, inicio, fuente, displayId }

function ajustesCola() {
  return { modo: ajustes.modo, direccion: ajustes.direccion, token: leerToken(), carpeta: ajustes.carpeta, smbCarpeta: ajustes.smbCarpeta };
}

function avisar(titulo, cuerpo) {
  if (Notification.isSupported()) new Notification({ title: titulo, body: cuerpo, silent: true }).show();
}

function enviar(canal, datos) {
  for (const w of [ventana, indicador]) if (w && !w.isDestroyed()) w.webContents.send(canal, datos);
}

// ---------- ventanas ----------
function crearVentana() {
  ventana = new BrowserWindow({
    width: 1180,
    height: 780,
    minWidth: 760,
    minHeight: 560,
    title: 'JSE Captura',
    backgroundColor: '#101418',
    icon: path.join(__dirname, '..', 'recursos', 'icono.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false },
  });
  ventana.removeMenu();
  ventana.loadFile(path.join(__dirname, 'ui', 'index.html'));
  ventana.on('close', (e) => {
    // Mientras graba, cerrar solo oculta la ventana (la grabación sigue y se detiene con el atajo o la bandeja).
    if (grabando && !app.salir) {
      e.preventDefault();
      ventana.hide();
    }
  });
}

// Indicador flotante: tiempo, marcar y detener. Excluido de la captura (no sale en el video).
function mostrarIndicador() {
  if (indicador && !indicador.isDestroyed()) return indicador.showInactive();
  const area = screen.getPrimaryDisplay().workArea;
  indicador = new BrowserWindow({
    width: 360,
    height: 56,
    x: area.x + area.width - 380,
    y: area.y + 16,
    frame: false,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    focusable: true,
    transparent: false,
    backgroundColor: '#1a1f24',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false },
  });
  indicador.setAlwaysOnTop(true, 'screen-saver');
  indicador.setContentProtection(true);
  indicador.loadFile(path.join(__dirname, 'ui', 'indicador.html'), { hash: app.getVersion() });
}
function ocultarIndicador() {
  if (indicador && !indicador.isDestroyed()) indicador.close();
  indicador = null;
}

function actualizarBandeja() {
  if (!bandeja) return;
  bandeja.setToolTip(grabando ? 'JSE Captura · grabando' : 'JSE Captura');
  bandeja.setContextMenu(
    Menu.buildFromTemplate([
      grabando
        ? { label: `Detener grabación (${ajustes.atajo.replace('CommandOrControl', 'Ctrl')})`, click: () => pedirDetener() }
        : { label: `Grabar (${ajustes.atajo.replace('CommandOrControl', 'Ctrl')})`, click: () => alternarGrabacion() },
      ...(grabando ? [{ label: 'Marcar momento', click: () => marcar() }] : []),
      { label: 'Abrir JSE Captura', click: () => (ventana.show(), ventana.focus()) },
      { label: 'Sincronizar ahora', click: () => cola.sincronizarAhora() },
      { type: 'separator' },
      { label: 'Salir', click: () => ((app.salir = true), app.quit()) },
    ]),
  );
}

// ---------- atajos ----------
function registrarAtajos() {
  globalShortcut.unregisterAll();
  const errores = [];
  try {
    if (!globalShortcut.register(ajustes.atajo, () => alternarGrabacion())) errores.push(ajustes.atajo);
  } catch {
    errores.push(ajustes.atajo);
  }
  try {
    if (ajustes.atajoMarca && !globalShortcut.register(ajustes.atajoMarca, () => marcar())) errores.push(ajustes.atajoMarca);
  } catch {
    errores.push(ajustes.atajoMarca);
  }
  return errores;
}

// Con el atajo sin haber elegido fuente: graba la pantalla donde está el ratón.
async function alternarGrabacion() {
  if (grabando) return pedirDetener();
  const punto = screen.getCursorScreenPoint();
  const pantalla = screen.getDisplayNearestPoint(punto);
  const fuentes = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } });
  const f = fuentes.find((s) => String(s.display_id) === String(pantalla.id)) || fuentes[0];
  enviar('atajo-grabar', { fuenteId: f.id, nombre: nombrePantalla(pantalla, fuentes.indexOf(f)) });
}
function pedirDetener() {
  enviar('detener', {});
}
function marcar() {
  const m = grab.marcar();
  if (m) enviar('marca', m);
}

function nombrePantalla(d, i) {
  const principal = d.id === screen.getPrimaryDisplay().id;
  return `Pantalla ${i + 1}${principal ? ' (principal)' : ''} · ${d.size.width * d.scaleFactor}×${d.size.height * d.scaleFactor}`;
}

// ---------- IPC ----------
function manejadores() {
  ipcMain.handle('fuentes', async () => {
    const pantallas = screen.getAllDisplays();
    const fuentes = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 320, height: 180 }, fetchWindowIcons: true });
    return fuentes
      .filter((f) => !/^JSE Captura/.test(f.name))
      .map((f) => {
        const d = pantallas.find((p) => String(p.id) === String(f.display_id));
        const i = d ? pantallas.indexOf(d) : -1;
        return {
          id: f.id,
          tipo: f.id.startsWith('screen') ? 'pantalla' : 'ventana',
          nombre: d ? nombrePantalla(d, i) : f.name,
          miniatura: f.thumbnail.toDataURL(),
          icono: f.appIcon?.toDataURL() || null,
          displayId: f.display_id || null,
          escala: d?.scaleFactor || 1,
          ancho: d ? Math.round(d.size.width * d.scaleFactor) : null,
          alto: d ? Math.round(d.size.height * d.scaleFactor) : null,
        };
      });
  });

  ipcMain.handle('ajustes', () => ({ ...ajustes, vinculado: !!leerToken(), cifrado: safeStorage.isEncryptionAvailable(), version: app.getVersion() }));
  ipcMain.handle('ajustes:guardar', (_e, cambios) => {
    const antes = { ...ajustes };
    guardarAjustes(cambios);
    let erroresAtajo = [];
    if (antes.atajo !== ajustes.atajo || antes.atajoMarca !== ajustes.atajoMarca) erroresAtajo = registrarAtajos();
    if (antes.carpetaLocal !== ajustes.carpetaLocal) {
      bib = new Biblioteca(ajustes.carpetaLocal);
      iniciarNucleo();
    }
    actualizarBandeja();
    cola.procesar();
    return { ...ajustes, erroresAtajo };
  });

  ipcMain.handle('espacio', () => grab.comprobarEspacio());

  ipcMain.handle('preparar', (_e, { fuenteId }) => {
    fuentePendiente = { fuenteId, audioSistema: ajustes.audioSistema };
    return true;
  });

  ipcMain.handle('iniciar', (_e, { titulo, origen, extension, audio, displayId }) => {
    const a = grab.iniciar({ titulo, origen, extension, audio });
    grabando = { id: a.id, inicio: a.inicio, displayId, titulo: a.titulo };
    mostrarIndicador();
    actualizarBandeja();
    enviar('estado-grabacion', { grabando: true, inicio: a.inicio, titulo: a.titulo, fuente: origen?.modo });
    return a;
  });

  ipcMain.on('trozo', (_e, datos) => {
    try {
      if (!grab.escribir(Buffer.from(datos))) {
        avisar('JSE Captura', 'El disco está casi lleno: se detuvo la grabación para no dañar nada.');
        pedirDetener();
      }
    } catch (err) {
      avisar('JSE Captura', `Error al escribir: ${err.message}. Se detiene la grabación.`);
      pedirDetener();
    }
  });

  ipcMain.handle('marcar', () => grab.marcar());
  ipcMain.on('pedir-detener', () => pedirDetener());

  ipcMain.handle('detener', async () => {
    if (!grabando) return null;
    ocultarIndicador();
    grabando = null;
    actualizarBandeja();
    enviar('estado-grabacion', { grabando: false, procesando: true });
    try {
      const g = await grab.detener({ alProgreso: (p) => enviar('procesando', p) });
      enviar('estado-grabacion', { grabando: false, procesando: false, listo: g.id });
      avisar('Grabación guardada', `${g.titulo} · ${Math.round(g.duracion)} s`);
      cola.procesar();
      return g;
    } catch (err) {
      enviar('estado-grabacion', { grabando: false, procesando: false, error: err.message });
      avisar('JSE Captura', `No se pudo guardar: ${err.message}. El archivo crudo quedó en «Grabando» para recuperarlo.`);
      throw err;
    }
  });

  // ----- biblioteca -----
  ipcMain.handle('biblioteca', (_e, filtros) => bib.listar(filtros || {}));
  ipcMain.handle('editar', (_e, { id, cambios }) => {
    const g = bib.editar(id, cambios);
    cola.procesar();
    return g;
  });
  ipcMain.handle('borrar-local', async (_e, id) => {
    const g = bib.obtener(id);
    const { response } = await dialog.showMessageBox(ventana, {
      type: 'warning',
      buttons: ['Cancelar', 'Borrar de este equipo'],
      defaultId: 0,
      cancelId: 0,
      title: 'Borrar del equipo',
      message: `¿Borrar «${g.titulo}» de este equipo?`,
      detail:
        g.destinos.nas.estado === 'guardado'
          ? `Se borra solo el archivo de ${g.archivo}. La copia del NAS (${g.destinos.nas.ruta}) NO se toca y podrás seguir viéndola desde el NAS.`
          : `Se borra ${g.archivo}. ¡Ojo! Todavía NO está en el NAS: esta es la única copia.`,
    });
    return response === 1 ? bib.borrarLocal(id) : null;
  });
  ipcMain.handle('borrar-nas', async (_e, id) => {
    const g = bib.obtener(id);
    const c = new ClienteNas({ direccion: ajustes.direccion, token: leerToken() });
    const { response } = await dialog.showMessageBox(ventana, {
      type: 'warning',
      buttons: ['Cancelar', 'Borrar del NAS'],
      defaultId: 0,
      cancelId: 0,
      title: 'Borrar del NAS',
      message: `¿Borrar «${g?.titulo || id}» del NAS?`,
      detail: `Se borra ${g?.destinos?.nas?.ruta || 'la copia del NAS'}. Si esa carpeta es la de Fotos JSE, también desaparece de la galería. La copia de este equipo no se toca.`,
    });
    if (response !== 1) return null;
    const r = await c.eliminar(id);
    if (g) bib.destino(id, 'nas', { estado: 'pendiente', ruta: null, siguiente: Date.now() + 365 * 864e5, motivo: 'Borrado del NAS por ti. Usa «Sincronizar» para volver a copiarlo.' });
    return r;
  });
  ipcMain.handle('abrir', (_e, id) => shell.openPath(bib.obtener(id).archivo)); // reproductor predeterminado (VLC, mpv, Películas y TV…)
  ipcMain.handle('mostrar-carpeta', (_e, id) => shell.showItemInFolder(bib.obtener(id).archivo));
  ipcMain.handle('compartir', (_e, id) => {
    // Copia el archivo al portapapeles de Windows: se pega en WhatsApp, correo, Explorador…
    const f = bib.obtener(id).archivo;
    clipboard.writeBuffer('FileNameW', Buffer.from(`${f}\0`, 'ucs2'));
    return true;
  });
  ipcMain.handle('recortar', async (_e, { id, inicio, fin }) => {
    const g = await grab.recortar(id, inicio, fin, { alProgreso: (p) => enviar('procesando', p) });
    cola.procesar();
    return g;
  });

  // ----- NAS -----
  ipcMain.handle('nas:vincular', async (_e, { direccion, usuario, contrasena }) => {
    const c = new ClienteNas({ direccion });
    await c.salud();
    const r = await c.iniciarSesion(usuario, contrasena, `${require('node:os').hostname()} (Windows)`);
    guardarToken(r.token);
    guardarAjustes({ direccion, usuario, modo: 'servicio' });
    cola.sincronizarAhora();
    return { ok: true, equipo: r.equipo };
  });
  ipcMain.handle('nas:desvincular', async () => {
    try {
      await new ClienteNas({ direccion: ajustes.direccion, token: leerToken() }).cerrarSesion();
    } catch {}
    guardarToken(null);
    return true;
  });
  ipcMain.handle('nas:probar', async () => {
    if (ajustes.modo === 'smb') {
      const ok = ajustes.smbCarpeta && fs.existsSync(ajustes.smbCarpeta);
      let escritura = false;
      if (ok) {
        try {
          const p = path.join(ajustes.smbCarpeta, `.jse-prueba-${Date.now()}`);
          fs.writeFileSync(p, 'ok');
          fs.rmSync(p);
          escritura = true;
        } catch {}
      }
      return { ok: ok && escritura, motivo: !ok ? 'No se encuentra la carpeta (¿NAS apagado o unidad desconectada?).' : !escritura ? 'Windows no puede escribir en esa carpeta.' : null };
    }
    return new ClienteNas({ direccion: ajustes.direccion, token: leerToken() }).prueba();
  });
  ipcMain.handle('nas:carpetas', () => new ClienteNas({ direccion: ajustes.direccion, token: leerToken() }).carpetas());
  ipcMain.handle('nas:remoto', (_e, params) => new ClienteNas({ direccion: ajustes.direccion, token: leerToken() }).listar(params));
  ipcMain.handle('nas:enlace', (_e, id) => new ClienteNas({ direccion: ajustes.direccion, token: leerToken() }).enlace(id));
  ipcMain.handle('nas:abrir-enlace', async (_e, id) => shell.openExternal(await new ClienteNas({ direccion: ajustes.direccion, token: leerToken() }).enlace(id)));
  ipcMain.handle('copiar-texto', (_e, t) => clipboard.writeText(t));
  ipcMain.handle('sincronizar', () => cola.sincronizarAhora());
  ipcMain.handle('elegir-carpeta', async () => {
    const r = await dialog.showOpenDialog(ventana, { properties: ['openDirectory', 'createDirectory'] });
    return r.canceled ? null : r.filePaths[0];
  });
}

function iniciarNucleo() {
  bib.on('cambio', () => enviar('biblioteca-cambio', {}));
  grab = new Grabaciones(bib);
  cola = new Cola(bib, ajustesCola, { registrar: (m) => console.log(m) });
}

app.whenReady().then(async () => {
  app.setAppUserModelId('mx.jse.captura');
  cargarAjustes();
  bib = new Biblioteca(ajustes.carpetaLocal);
  iniciarNucleo();
  manejadores();

  // getDisplayMedia sin el selector de Chromium: usa la fuente elegida en nuestra pantalla.
  session.defaultSession.setDisplayMediaRequestHandler(async (_req, callback) => {
    const fuentes = await desktopCapturer.getSources({ types: ['screen', 'window'] });
    const f = fuentes.find((s) => s.id === fuentePendiente?.fuenteId);
    if (!f) return callback({});
    // Audio del sistema (loopback de Windows): solo disponible al capturar una pantalla completa.
    callback({ video: f, audio: fuentePendiente.audioSistema && f.id.startsWith('screen') ? 'loopback' : undefined });
  });
  // Micrófono: se pide permiso solo cuando el usuario lo activa.
  session.defaultSession.setPermissionRequestHandler((_wc, permiso, cb) => cb(['media', 'display-capture', 'notifications'].includes(permiso)));

  crearVentana();
  bandeja = new Tray(nativeImage.createFromPath(path.join(__dirname, '..', 'recursos', 'icono.png')).resize({ width: 16, height: 16 }));
  bandeja.on('click', () => (ventana.show(), ventana.focus()));
  actualizarBandeja();
  const erroresAtajo = registrarAtajos();

  ventana.webContents.once('did-finish-load', async () => {
    if (erroresAtajo.length) enviar('aviso', `No se pudo registrar el atajo ${erroresAtajo.join(', ')} (lo usa otra app). Cámbialo en Ajustes.`);
    const rec = await grab.recuperarPendientes();
    if (rec.length) enviar('recuperadas', rec);
    cola.procesar();
  });

  // Reintentos: cada minuto, al volver de suspensión y cuando la interfaz detecta red.
  setInterval(() => cola.procesar(), 60e3);
  powerMonitor.on('resume', () => setTimeout(() => cola.procesar(), 5000));
  ipcMain.on('red-disponible', () => cola.procesar());

  // Se desconectó el monitor que se está grabando: se finaliza de forma segura.
  screen.on('display-removed', (_e, d) => {
    if (grabando && String(grabando.displayId) === String(d.id)) {
      avisar('JSE Captura', 'Se desconectó el monitor que estabas grabando. Se guardó lo grabado; inicia otra grabación si hace falta.');
      pedirDetener();
    }
  });
});

app.on('second-instance', () => ventana && (ventana.show(), ventana.focus()));
app.on('will-quit', () => globalShortcut.unregisterAll());
app.on('window-all-closed', () => {
  if (!grabando) app.quit();
});
