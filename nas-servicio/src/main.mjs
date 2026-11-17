import { leerConfig } from './config.mjs';
import { crearServicio } from './servidor.mjs';

const cfg = leerConfig();
const { servidor, almacen } = crearServicio(cfg);
const rec = await almacen.recuperar();
if (rec.length) console.log(`Recuperadas ${rec.length} grabaciones que quedaron a medio publicar.`);
const borrados = almacen.limpiarIncompletos();
if (borrados) console.log(`Borrados ${borrados} temporales abandonados (más de ${cfg.horasIncompletos} h).`);
setInterval(() => almacen.limpiarIncompletos(), 3600e3).unref();
servidor.listen(cfg.puerto, cfg.host, () => {
  console.log(`JSE Captura · servicio NAS ${cfg.version} en http://${cfg.host}:${cfg.puerto}`);
  console.log(`  grabaciones: ${cfg.grabaciones}   datos: ${cfg.datos}   Fotos JSE: ${cfg.fotosJse.modo} ${cfg.fotosJse.url}`);
  if (!cfg.contrasena) console.warn('  ⚠ Falta JSE_CONTRASENA: ningún equipo podrá vincularse.');
});
for (const s of ['SIGTERM', 'SIGINT']) process.on(s, () => servidor.close(() => process.exit(0)));
