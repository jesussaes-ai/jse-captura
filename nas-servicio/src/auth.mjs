// Autenticación: cuenta de servicio → token por equipo (guardado como hash) y enlaces firmados de corta vida.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { escribirJsonAtomico } from './util.mjs';

const sha = (s) => crypto.createHash('sha256').update(s).digest();
const igual = (a, b) => {
  const x = sha(String(a)), y = sha(String(b));
  return crypto.timingSafeEqual(x, y);
};

export class Autenticacion {
  constructor(cfg) {
    this.cfg = cfg;
    this.archivo = path.join(cfg.datos, 'equipos.json');
    this.secretoArchivo = path.join(cfg.datos, 'secreto-enlaces');
    this.intentos = new Map(); // ip → [marcas de tiempo]
    fs.mkdirSync(cfg.datos, { recursive: true });
    if (!fs.existsSync(this.secretoArchivo)) fs.writeFileSync(this.secretoArchivo, crypto.randomBytes(32).toString('hex'), { mode: 0o600 });
    this.secreto = fs.readFileSync(this.secretoArchivo, 'utf8').trim();
    this.equipos = fs.existsSync(this.archivo) ? JSON.parse(fs.readFileSync(this.archivo, 'utf8')) : [];
  }

  guardar() {
    escribirJsonAtomico(this.archivo, this.equipos);
  }

  // Máximo 10 intentos fallidos por IP cada 10 minutos.
  bloqueado(ip) {
    const ahora = Date.now();
    const l = (this.intentos.get(ip) || []).filter((t) => ahora - t < 10 * 60000);
    this.intentos.set(ip, l);
    return l.length >= 10;
  }

  iniciarSesion({ usuario, contrasena, equipo }, ip) {
    if (this.bloqueado(ip)) return { error: 'demasiados_intentos' };
    if (!this.cfg.contrasena) return { error: 'sin_contrasena_configurada' };
    if (!igual(usuario || '', this.cfg.usuario) || !igual(contrasena || '', this.cfg.contrasena)) {
      this.intentos.get(ip).push(Date.now());
      return { error: 'credenciales_invalidas' };
    }
    return this.crearToken(equipo);
  }

  crearToken(equipo) {
    const token = 'jsec_' + crypto.randomBytes(32).toString('base64url');
    const reg = {
      id: crypto.randomUUID(),
      equipo: String(equipo || 'Equipo').slice(0, 80),
      hash: sha(token).toString('hex'),
      creado: new Date().toISOString(),
      ultimoUso: null,
    };
    this.equipos.push(reg);
    this.guardar();
    return { token, equipoId: reg.id, equipo: reg.equipo };
  }

  // Devuelve el equipo del token o null.
  verificar(token) {
    if (!token || !token.startsWith('jsec_')) return null;
    const h = sha(token).toString('hex');
    const reg = this.equipos.find((e) => crypto.timingSafeEqual(Buffer.from(e.hash, 'hex'), Buffer.from(h, 'hex')));
    if (!reg) return null;
    const ahora = new Date().toISOString();
    // Guardar el último uso como mucho una vez por minuto.
    if (!reg.ultimoUso || Date.parse(ahora) - Date.parse(reg.ultimoUso) > 60000) {
      reg.ultimoUso = ahora;
      this.guardar();
    }
    return reg;
  }

  revocar(id) {
    const antes = this.equipos.length;
    this.equipos = this.equipos.filter((e) => e.id !== id && e.equipo !== id);
    this.guardar();
    return antes - this.equipos.length;
  }

  // Enlace firmado: permite abrir el video en VLC/mpv/navegador sin enviar cabeceras.
  firmar(recurso, minutos = this.cfg.minutosEnlace) {
    const exp = Math.floor(Date.now() / 1000) + minutos * 60;
    const firma = crypto.createHmac('sha256', this.secreto).update(`${recurso}|${exp}`).digest('base64url');
    return { exp, firma };
  }

  firmaValida(recurso, exp, firma) {
    if (!exp || !firma || Number(exp) < Date.now() / 1000) return false;
    const esperada = crypto.createHmac('sha256', this.secreto).update(`${recurso}|${exp}`).digest('base64url');
    return esperada.length === firma.length && crypto.timingSafeEqual(Buffer.from(esperada), Buffer.from(firma));
  }
}
