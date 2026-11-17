/* global jse */
const ini = Date.now();
const z = (n) => String(n).padStart(2, '0');
setInterval(() => {
  const s = Math.floor((Date.now() - ini) / 1000);
  document.getElementById('t').textContent = (s >= 3600 ? `${Math.floor(s / 3600)}:` : '') + `${z(Math.floor((s % 3600) / 60))}:${z(s % 60)}`;
}, 500);
// La grabación vive en la ventana principal: el indicador solo pide detenerla o marcar.
document.getElementById('d').onclick = () => jse.pedirDetener();
document.getElementById('m').onclick = () => jse.marcar();
