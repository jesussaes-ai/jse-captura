const { contextBridge, ipcRenderer } = require('electron');
const { pathToFileURL } = require('node:url');

const invocar = (canal) => (datos) => ipcRenderer.invoke(canal, datos);
const canales = ['fuentes', 'ajustes', 'ajustes:guardar', 'espacio', 'preparar', 'iniciar', 'marcar', 'detener', 'biblioteca', 'editar', 'borrar-local', 'borrar-nas', 'abrir', 'mostrar-carpeta', 'compartir', 'recortar', 'nas:vincular', 'nas:desvincular', 'nas:probar', 'nas:carpetas', 'nas:remoto', 'nas:enlace', 'nas:abrir-enlace', 'copiar-texto', 'sincronizar', 'elegir-carpeta', 'abrir-link', 'leer-portapapeles'];

contextBridge.exposeInMainWorld('jse', {
  ...Object.fromEntries(canales.map((c) => [c.replace(/[:-](\w)/g, (_, l) => l.toUpperCase()), invocar(c)])),
  trozo: (buffer) => ipcRenderer.send('trozo', buffer),
  redDisponible: () => ipcRenderer.send('red-disponible'),
  pedirDetener: () => ipcRenderer.send('pedir-detener'),
  urlArchivo: (ruta) => (ruta ? pathToFileURL(ruta).href : ''),
  al: (canal, fn) => {
    const f = (_e, d) => fn(d);
    ipcRenderer.on(canal, f);
    return () => ipcRenderer.removeListener(canal, f);
  },
});
