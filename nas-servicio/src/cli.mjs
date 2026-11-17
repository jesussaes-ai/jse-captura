// Administración desde el NAS:  docker compose exec jse-captura node src/cli.mjs <orden>
//   equipos                 lista los equipos vinculados
//   vincular "<nombre>"     crea un token para un equipo sin usar la contraseña (se muestra una sola vez)
//   revocar <id|nombre>     quita el acceso a un equipo
//   limpiar                 borra temporales de subidas abandonadas
import { leerConfig } from './config.mjs';
import { Autenticacion } from './auth.mjs';
import { Almacen } from './almacen.mjs';

const cfg = leerConfig();
const [orden, arg] = process.argv.slice(2);
const auth = new Autenticacion(cfg);
if (orden === 'equipos') console.table(auth.equipos.map(({ id, equipo, creado, ultimoUso }) => ({ id, equipo, creado, ultimoUso })));
else if (orden === 'vincular') console.log(auth.crearToken(arg || 'Equipo'));
else if (orden === 'revocar') console.log(`Equipos revocados: ${auth.revocar(arg)}`);
else if (orden === 'limpiar') console.log(`Temporales borrados: ${new Almacen(cfg).limpiarIncompletos()}`);
else console.log('Órdenes: equipos | vincular "<nombre>" | revocar <id|nombre> | limpiar');
