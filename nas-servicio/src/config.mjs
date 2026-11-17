// Configuración del servicio NAS de JSE Captura. Todo por variables de entorno (ver .env.ejemplo).
import path from 'node:path';

const num = (v, d) => (v === undefined || v === '' || !Number.isFinite(Number(v)) ? d : Number(v));
const GB = 1024 ** 3;

export function leerConfig(env = process.env) {
  const datos = path.resolve(env.JSE_DATOS || '/datos');
  const grabaciones = path.resolve(env.JSE_GRABACIONES || '/grabaciones');
  return {
    version: '0.1.0',
    puerto: num(env.JSE_PUERTO, 8790),
    host: env.JSE_HOST || '0.0.0.0',
    // Catálogo (JSON por grabación), tokens y registros. Volumen pequeño.
    datos,
    // Donde se publican los MP4. Recomendado: una subcarpeta de la biblioteca de Fotos JSE en el NAS.
    grabaciones,
    // Temporales de subida: DENTRO del mismo volumen para que publicar sea un renombrado atómico.
    // Empieza con "." para que Fotos JSE y el explorador del NAS no los muestren.
    incompletos: path.join(grabaciones, '.jse-incompletos'),
    // Cuenta de servicio con la que cada equipo se vincula (los equipos reciben su propio token).
    usuario: env.JSE_USUARIO || 'jse-captura',
    contrasena: env.JSE_CONTRASENA || '',
    // Límites
    maxArchivo: num(env.JSE_MAX_ARCHIVO_GB, 20) * GB,
    reservaLibre: num(env.JSE_RESERVA_LIBRE_GB, 2) * GB,
    horasIncompletos: num(env.JSE_HORAS_INCOMPLETOS, 72),
    // Fotos JSE
    fotosJse: {
      // misma-carpeta: JSE_GRABACIONES ya está dentro de la biblioteca que indexa Fotos JSE (no se copia nada).
      // copiar: se copia (o enlaza) a JSE_FOTOS_CARPETA con verificación SHA-256.
      // no: sin integración.
      modo: env.JSE_FOTOS_MODO || 'misma-carpeta',
      carpeta: env.JSE_FOTOS_CARPETA ? path.resolve(env.JSE_FOTOS_CARPETA) : '',
      // Dirección de Fotos JSE para pedir un escaneo y comprobar que el video se ve (opcional).
      url: (env.JSE_FOTOS_URL || '').replace(/\/+$/, ''),
      // Segundos máximos para esperar a que Fotos JSE indexe antes de dejarlo como "Exportado".
      espera: num(env.JSE_FOTOS_ESPERA_S, 120),
    },
    // Duración de los enlaces firmados para reproducir en VLC / mpv / navegador sin cabeceras.
    minutosEnlace: num(env.JSE_MINUTOS_ENLACE, 240),
    ffprobe: env.JSE_FFPROBE || 'ffprobe',
    ffmpeg: env.JSE_FFMPEG || 'ffmpeg',
  };
}
