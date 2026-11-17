/* global jse, Grabador */
const $ = (s) => document.querySelector(s);
const el = (tag, attrs = {}, ...hijos) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? '' : v);
  }
  for (const h of hijos.flat()) if (h !== null && h !== undefined && h !== false) e.append(h.nodeType ? h : String(h));
  return e;
};

const ESTADOS = {
  pendiente: ['Pendiente', 'pend'],
  copiando: ['Copiando', 'copi'],
  guardado: ['Guardado', 'ok'],
  exportado: ['Exportado a la carpeta', 'expo'],
  visible: ['Visible en Fotos JSE', 'ok'],
  requiere_atencion: ['Requiere atención', 'mal'],
  no_configurado: ['Sin configurar', 'nada'],
  borrado: ['Borrado', 'nada'],
};
const tam = (b) => (b >= 1024 ** 3 ? `${(b / 1024 ** 3).toFixed(2)} GB` : `${(b / 1024 ** 2).toFixed(1)} MB`);
const dur = (s) => {
  s = Math.round(s || 0);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return (h ? `${h}:${String(m).padStart(2, '0')}` : `${m}`) + `:${String(x).padStart(2, '0')}`;
};
const fecha = (iso) => new Date(iso).toLocaleString('es-MX', { dateStyle: 'medium', timeStyle: 'short' });

let ajustes = {};
let fuenteElegida = null;
const grabador = new Grabador();
let reloj = null;

function aviso(texto, tipo = 'info', ms = 8000) {
  const a = $('#aviso');
  a.textContent = texto;
  a.className = `aviso ${tipo}`;
  a.hidden = false;
  clearTimeout(aviso.t);
  if (ms) aviso.t = setTimeout(() => (a.hidden = true), ms);
}

// ---------- navegación ----------
function mostrar(vista) {
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('activo', b.dataset.vista === vista));
  document.querySelectorAll('.vista').forEach((v) => (v.hidden = v.id !== `vista-${vista}`));
  if (vista === 'biblioteca') pintarBiblioteca();
  if (vista === 'nas') pintarNas();
  if (vista === 'ajustes') pintarAjustes();
  if (vista === 'grabar') cargarFuentes();
}
document.querySelectorAll('nav button').forEach((b) => b.addEventListener('click', () => mostrar(b.dataset.vista)));

// ---------- grabar ----------
async function cargarFuentes() {
  if (grabador.recorder) return;
  const fuentes = await jse.fuentes();
  for (const [lista, tipo] of [['#lista-pantallas', 'pantalla'], ['#lista-ventanas', 'ventana']]) {
    const c = $(lista);
    c.replaceChildren(
      ...fuentes
        .filter((f) => f.tipo === tipo)
        .map((f) =>
          el(
            'button',
            { class: `fuente ${fuenteElegida?.id === f.id ? 'elegida' : ''}`, onclick: () => elegir(f) },
            el('img', { src: f.miniatura, alt: '' }),
            el('span', {}, f.icono ? el('img', { class: 'icono', src: f.icono, alt: '' }) : null, f.nombre),
          ),
        ),
    );
  }
  if (!fuenteElegida) {
    const principal = fuentes.find((f) => f.tipo === 'pantalla' && f.nombre.includes('principal')) || fuentes[0];
    if (principal) elegir(principal);
  } else if (!fuentes.find((f) => f.id === fuenteElegida.id)) {
    fuenteElegida = null;
    $('#btn-grabar').disabled = true;
  }
  const e = await jse.espacio();
  $('#espacio').textContent = `Espacio libre en el equipo: ${tam(e.libre)}${e.suficiente ? '' : ' · ¡insuficiente para grabar!'}`;
}

function elegir(f) {
  fuenteElegida = f;
  document.querySelectorAll('.fuente').forEach((b) => b.classList.remove('elegida'));
  cargarFuentes();
  $('#btn-grabar').disabled = false;
  resumen();
}

function resumen() {
  const partes = [];
  if (fuenteElegida) partes.push(`🖥 ${fuenteElegida.nombre}`);
  partes.push($('#op-calidad').value === '720' ? '720p · 30 fps' : 'hasta 1080p · 30 fps');
  const mic = $('#op-microfono').checked, sis = $('#op-sistema').checked;
  partes.push(mic || sis ? `🔊 ${[mic && 'micrófono', sis && (fuenteElegida?.tipo === 'pantalla' ? 'audio del equipo' : 'audio del equipo (no disponible con ventanas)')].filter(Boolean).join(' + ')}` : '🔇 sin audio');
  $('#resumen-fuentes').textContent = `Se grabará: ${partes.join(' · ')}`;
}
for (const id of ['#op-calidad', '#op-microfono', '#op-sistema']) $(id).addEventListener('change', async () => {
  resumen();
  await jse.ajustesGuardar({ calidad: $('#op-calidad').value, microfono: $('#op-microfono').checked, audioSistema: $('#op-sistema').checked });
});
$('#btn-actualizar-fuentes').addEventListener('click', cargarFuentes);

async function empezar(fuente) {
  try {
    const r = await grabador.iniciar({
      fuente,
      calidad: $('#op-calidad').value,
      microfono: $('#op-microfono').checked,
      audioSistema: $('#op-sistema').checked,
      titulo: $('#op-titulo').value.trim() || undefined,
    });
    if (r.avisoAudio) aviso(r.avisoAudio, 'alerta', 12000);
    $('#panel-elegir').hidden = true;
    $('#panel-grabando').hidden = false;
    $('#grabando-fuente').textContent = `${fuente.nombre} · ${r.resolucion}${r.tipo.includes('webm') ? ' · se convertirá a MP4 al detener' : ''}`;
    $('#como-detener').textContent = `Puedes cambiar a otra app: detén con ${ajustes.atajo.replace('CommandOrControl', 'Ctrl')}, con el indicador flotante o desde el icono junto al reloj. Marca momentos con ${ajustes.atajoMarca.replace('CommandOrControl', 'Ctrl')}.`;
    const inicio = Date.now();
    reloj = setInterval(() => ($('#tiempo').textContent = dur((Date.now() - inicio) / 1000)), 500);
    grabador.alTerminar = (motivo) => {
      aviso(motivo, 'alerta', 15000);
      terminar();
    };
  } catch (e) {
    const motivo =
      e.name === 'NotAllowedError'
        ? 'Windows no permitió capturar esa fuente (puede ser contenido protegido o una ventana del sistema).'
        : e.name === 'NotReadableError'
          ? 'La fuente no se puede capturar ahora mismo (¿la pantalla está apagada o la ventana minimizada?).'
          : e.message;
    aviso(`No se pudo empezar a grabar: ${motivo}`, 'error', 15000);
    await grabador.detener().catch(() => {});
  }
}
$('#btn-grabar').addEventListener('click', () => fuenteElegida && empezar(fuenteElegida));

async function terminar() {
  if (!grabador.recorder) return;
  clearInterval(reloj);
  $('#panel-grabando').hidden = true;
  $('#panel-procesando').hidden = false;
  try {
    const g = await grabador.detener();
    aviso(`Guardado: ${g.titulo} (${dur(g.duracion)})`, 'ok');
    mostrar('biblioteca');
  } catch (e) {
    aviso(`No se pudo guardar el MP4: ${e.message}`, 'error', 0);
  } finally {
    $('#panel-procesando').hidden = true;
    $('#panel-elegir').hidden = false;
    $('#op-titulo').value = '';
  }
}
$('#btn-detener').addEventListener('click', terminar);
$('#btn-marcar').addEventListener('click', async () => {
  const m = await jse.marcar();
  if (m) aviso(`Momento marcado en ${dur(m.t)}`, 'ok', 2500);
});

jse.al('detener', terminar);
jse.al('atajo-grabar', ({ fuenteId, nombre }) => !grabador.recorder && empezar({ id: fuenteId, tipo: 'pantalla', nombre }));
jse.al('marca', (m) => aviso(`Momento marcado en ${dur(m.t)}`, 'ok', 2500));
jse.al('procesando', (p) => ($('#prog-procesando').value = p));
jse.al('aviso', (t) => aviso(t, 'alerta', 15000));
jse.al('recuperadas', (l) => {
  const ok = l.filter((r) => r.ok), mal = l.filter((r) => !r.ok);
  aviso(
    [ok.length && `Se recuperaron ${ok.length} grabación(es) interrumpidas: ${ok.map((r) => `${r.titulo} (${dur(r.duracion)})`).join(', ')}.`, mal.length && `No se pudo recuperar: ${mal.map((r) => `${r.titulo} — ${r.motivo}`).join('; ')}.`].filter(Boolean).join(' '),
    mal.length ? 'alerta' : 'ok',
    0,
  );
});

// ---------- biblioteca ----------
function chip(nombre, d) {
  const [texto, clase] = ESTADOS[d?.estado] || [d?.estado || '—', 'nada'];
  const pct = d?.estado === 'copiando' ? ` ${Math.round((d.progreso || 0) * 100)}%` : '';
  return el('span', { class: `chip ${clase}`, title: d?.motivo || '' }, `${nombre}: ${texto}${pct}`);
}

async function pintarBiblioteca() {
  const l = await jse.biblioteca({ q: $('#buscar').value, pendientes: $('#f-pendientes').checked, favoritos: $('#f-favoritos').checked });
  $('#vacio').hidden = l.length > 0;
  $('#lista-videos').replaceChildren(
    ...l.map((g) =>
      el(
        'article',
        { class: 'video' },
        el('button', { class: 'mini', onclick: () => abrirReproductor(g.id), title: 'Reproducir' }, g.miniatura ? el('img', { src: `${jse.urlArchivo(g.miniatura)}?${g.actualizado}`, alt: '' }) : el('div', { class: 'sin-mini' }, '▶'), el('span', { class: 'dur' }, dur(g.duracion))),
        el(
          'div',
          { class: 'info' },
          el('div', { class: 'titulo' }, g.favorito ? '★ ' : '', g.titulo, g.recorteDe ? el('span', { class: 'sutil' }, ' · recorte') : null, g.recuperada ? el('span', { class: 'sutil' }, ' · recuperada') : null),
          el('div', { class: 'sutil' }, `${fecha(g.creado)} · ${dur(g.duracion)} · ${tam(g.tamano || 0)} · ${g.origen?.modo || ''}`),
          el('div', { class: 'chips' }, chip('Equipo', g.destinos.dispositivo), chip('NAS', g.destinos.nas), chip('Fotos JSE', g.destinos.fotosJse)),
          g.destinos.nas.estado === 'requiere_atencion' || (g.destinos.nas.estado === 'pendiente' && g.destinos.nas.motivo)
            ? el('div', { class: 'motivo' }, g.destinos.nas.motivo, ' ', accionCorregir(g.destinos.nas))
            : null,
          g.destinos.nas.estado === 'copiando' ? el('progress', { max: 1, value: g.destinos.nas.progreso || 0 }) : null,
          el(
            'div',
            { class: 'fila' },
            g.archivo ? el('button', { class: 'secundario', onclick: () => abrirReproductor(g.id) }, '▶ Reproducir') : null,
            g.archivo ? el('button', { class: 'secundario', onclick: () => jse.abrir(g.id) }, 'Abrir') : null,
            g.archivo ? el('button', { class: 'secundario', onclick: () => jse.compartir(g.id).then(() => aviso('Archivo copiado: pégalo (Ctrl+V) en WhatsApp, correo o una carpeta.', 'ok')) }, 'Compartir') : null,
            g.destinos.nas.estado === 'guardado' ? el('button', { class: 'secundario', onclick: () => jse.nasAbrirEnlace(g.id) }, 'Ver desde el NAS') : null,
          ),
        ),
      ),
    ),
  );
  const pend = l.filter((g) => ['pendiente', 'requiere_atencion', 'copiando'].includes(g.destinos.nas.estado)).length;
  $('#contador-pendientes').hidden = !pend;
  $('#contador-pendientes').textContent = pend;
}

function accionCorregir(d) {
  if (['credenciales_invalidas'].includes(d.codigo)) return el('button', { class: 'enlace', onclick: () => mostrar('ajustes') }, 'Volver a vincular →');
  if (['sin_espacio', 'archivo_demasiado_grande'].includes(d.codigo)) return el('button', { class: 'enlace', onclick: () => mostrar('nas') }, 'Liberar espacio en el NAS →');
  if (['smb_no_disponible', 'ECONNREFUSED', 'EHOSTUNREACH', 'ENOTFOUND', 'ETIMEDOUT'].includes(d.codigo)) return el('button', { class: 'enlace', onclick: () => mostrar('ajustes') }, 'Revisar la dirección del NAS →');
  return null;
}

let repaint = null;
jse.al('biblioteca-cambio', () => {
  // Varios avances por segundo durante la copia: se agrupan.
  if (repaint) return;
  repaint = setTimeout(() => {
    repaint = null;
    if (!$('#vista-biblioteca').hidden) pintarBiblioteca();
    pintarEstadoRed();
  }, 300);
});
$('#buscar').addEventListener('input', pintarBiblioteca);
$('#f-pendientes').addEventListener('change', pintarBiblioteca);
$('#f-favoritos').addEventListener('change', pintarBiblioteca);
$('#btn-sincronizar').addEventListener('click', async () => {
  aviso('Sincronizando…', 'info', 2000);
  await jse.sincronizar();
  pintarBiblioteca();
});

// ---------- reproductor ----------
let actual = null;
async function abrirReproductor(id) {
  const [g] = (await jse.biblioteca({})).filter((x) => x.id === id);
  if (!g) return;
  actual = g;
  const v = $('#rep-video');
  v.src = jse.urlArchivo(g.archivo);
  $('#rep-titulo').value = g.titulo;
  $('#rep-notas').value = g.notas || '';
  $('#rep-etiquetas').value = (g.etiquetas || []).join(', ');
  $('#rep-favorito').textContent = g.favorito ? '★ Favorito' : '☆ Favorito';
  $('#rec-ini').value = 0;
  $('#rec-fin').value = (g.duracion || 0).toFixed(1);
  $('#rec-fin').max = g.duracion;
  $('#rec-info').textContent = '';
  pintarMarcas();
  $('#rep-destinos').replaceChildren(
    chip('Equipo', g.destinos.dispositivo),
    chip('NAS', g.destinos.nas),
    chip('Fotos JSE', g.destinos.fotosJse),
    g.destinos.nas.ruta ? el('span', { class: 'sutil' }, ` NAS: ${g.destinos.nas.ruta}`) : null,
    g.destinos.fotosJse?.motivo ? el('div', { class: 'sutil' }, g.destinos.fotosJse.motivo) : null,
  );
  $('#rep-borrar-nas').hidden = g.destinos.nas.estado !== 'guardado';
  $('#reproductor').showModal();
}
function pintarMarcas() {
  $('#rep-marcas').replaceChildren(
    ...(actual.marcas || []).map((m, i) =>
      el('span', { class: 'marca' }, el('button', { class: 'enlace', onclick: () => (($('#rep-video').currentTime = m.t), $('#rep-video').play()) }, `⚑ ${dur(m.t)}${m.nota ? ` ${m.nota}` : ''}`), el('button', { class: 'x', title: 'Quitar', onclick: () => guardarMeta({ marcas: actual.marcas.filter((_, j) => j !== i) }) }, '×')),
    ),
  );
}
async function guardarMeta(cambios) {
  actual = await jse.editar({ id: actual.id, cambios });
  pintarMarcas();
}
$('#rep-cerrar').addEventListener('click', () => {
  $('#rep-video').pause();
  $('#rep-video').removeAttribute('src');
  $('#reproductor').close();
  pintarBiblioteca();
});
$('#rep-titulo').addEventListener('change', () => guardarMeta({ titulo: $('#rep-titulo').value.trim() || actual.titulo }));
$('#rep-notas').addEventListener('change', () => guardarMeta({ notas: $('#rep-notas').value }));
$('#rep-etiquetas').addEventListener('change', () => guardarMeta({ etiquetas: $('#rep-etiquetas').value.split(',').map((s) => s.trim()).filter(Boolean) }));
$('#rep-favorito').addEventListener('click', async () => {
  await guardarMeta({ favorito: !actual.favorito });
  $('#rep-favorito').textContent = actual.favorito ? '★ Favorito' : '☆ Favorito';
});
$('#rep-marcar').addEventListener('click', () => guardarMeta({ marcas: [...(actual.marcas || []), { t: $('#rep-video').currentTime, nota: '' }].sort((a, b) => a.t - b.t) }));
$('#rep-abrir').addEventListener('click', () => jse.abrir(actual.id));
$('#rep-carpeta').addEventListener('click', () => jse.mostrarCarpeta(actual.id));
$('#rep-compartir').addEventListener('click', () => jse.compartir(actual.id).then(() => aviso('Archivo copiado: pégalo (Ctrl+V) en WhatsApp, correo o una carpeta.', 'ok')));
$('#rep-borrar-local').addEventListener('click', async () => {
  const r = await jse.borrarLocal(actual.id);
  if (r) {
    $('#rep-cerrar').click();
    aviso(`Borrado de ${r.ubicacion}.${r.quedaEnNas ? ' La copia del NAS sigue disponible.' : ''}`, 'ok');
  }
});
$('#rep-borrar-nas').addEventListener('click', async () => {
  try {
    const r = await jse.borrarNas(actual.id);
    if (r) aviso(`Borrado del NAS: ${r.ubicacion}.${r.afectaFotosJse ? ' También deja de verse en Fotos JSE.' : ''} La copia de este equipo sigue aquí.`, 'ok');
  } catch (e) {
    aviso(`No se pudo borrar del NAS: ${e.message}`, 'error');
  }
});
// Recorte con vista previa.
$('#rec-ini-aqui').addEventListener('click', () => ($('#rec-ini').value = $('#rep-video').currentTime.toFixed(1)));
$('#rec-fin-aqui').addEventListener('click', () => ($('#rec-fin').value = $('#rep-video').currentTime.toFixed(1)));
$('#rec-vista').addEventListener('click', () => {
  const v = $('#rep-video'), ini = Number($('#rec-ini').value), fin = Number($('#rec-fin').value);
  v.currentTime = ini;
  v.play();
  const parar = () => {
    if (v.currentTime >= fin) {
      v.pause();
      v.removeEventListener('timeupdate', parar);
    }
  };
  v.addEventListener('timeupdate', parar);
});
$('#rec-guardar').addEventListener('click', async () => {
  const ini = Number($('#rec-ini').value), fin = Number($('#rec-fin').value);
  if (!(fin > ini)) return ($('#rec-info').textContent = 'El fin debe ser mayor que el inicio.');
  $('#rec-info').textContent = 'Creando el recorte…';
  try {
    const n = await jse.recortar({ id: actual.id, inicio: ini, fin });
    $('#rec-info').textContent = `Listo: «${n.titulo}» (${dur(n.duracion)}). El original no cambió.`;
  } catch (e) {
    $('#rec-info').textContent = `No se pudo recortar: ${e.message}`;
  }
});

// ---------- NAS: copias remotas ----------
let paginaNas = 1;
async function pintarNas() {
  const msg = $('#nas-mensaje');
  msg.hidden = true;
  if (ajustes.modo !== 'servicio' || !ajustes.vinculado) {
    $('#lista-nas').replaceChildren();
    msg.hidden = false;
    msg.textContent = 'Vincula el servicio del NAS en Ajustes para ver aquí las grabaciones que ya están en el NAS (de este y otros equipos).';
    return;
  }
  try {
    const r = await jse.nasRemoto({ q: $('#buscar-nas').value, pagina: paginaNas, limite: 30 });
    const locales = new Set((await jse.biblioteca({})).map((g) => g.id));
    $('#nas-pagina').textContent = `Página ${r.pagina} de ${Math.max(1, Math.ceil(r.total / r.limite))} · ${r.total} en el NAS`;
    $('#lista-nas').replaceChildren(
      ...r.elementos.map((g) =>
        el(
          'article',
          { class: 'video' },
          el('div', { class: 'info' },
            el('div', { class: 'titulo' }, g.favorito ? '★ ' : '', g.titulo),
            el('div', { class: 'sutil' }, `${fecha(g.creado)} · ${dur(g.duracion)} · ${tam(g.tamano)} · ${g.origen?.plataforma || ''} ${g.origen?.equipo || ''}`),
            el('div', { class: 'chips' }, el('span', { class: 'chip ok' }, 'NAS: Guardado'), locales.has(g.id) ? el('span', { class: 'chip ok' }, 'También en este equipo') : el('span', { class: 'chip nada' }, 'Solo en el NAS'), chip('Fotos JSE', g.fotosJse)),
            el('div', { class: 'fila' },
              el('button', { class: 'secundario', onclick: () => jse.nasAbrirEnlace(g.id) }, '▶ Reproducir (sin descargar)'),
              el('button', { class: 'secundario', onclick: async () => (await jse.copiarTexto(await jse.nasEnlace(g.id)), aviso('Enlace copiado: ábrelo en VLC (Medio → Abrir ubicación de red) o mpv. Vence en unas horas.', 'ok')) }, 'Copiar enlace para VLC/mpv'),
            ),
          ),
        ),
      ),
    );
  } catch (e) {
    msg.hidden = false;
    msg.textContent = `No se pudo consultar el NAS: ${e.message}`;
  }
}
$('#btn-nas-recargar').addEventListener('click', pintarNas);
$('#buscar-nas').addEventListener('change', () => ((paginaNas = 1), pintarNas()));
$('#nas-anterior').addEventListener('click', () => paginaNas > 1 && (paginaNas--, pintarNas()));
$('#nas-siguiente').addEventListener('click', () => (paginaNas++, pintarNas()));

// ---------- ajustes ----------
async function pintarAjustes() {
  ajustes = await jse.ajustes();
  $('#aj-modo').value = ajustes.modo;
  $('#aj-direccion').value = ajustes.direccion;
  $('#aj-usuario').value = ajustes.usuario;
  $('#aj-smb-carpeta').value = ajustes.smbCarpeta;
  $('#aj-smb-fotos').checked = ajustes.fotosJseEnSmb;
  $('#aj-atajo').value = ajustes.atajo.replace('CommandOrControl', 'Ctrl');
  $('#aj-atajo-marca').value = ajustes.atajoMarca.replace('CommandOrControl', 'Ctrl');
  $('#aj-carpeta-local').value = ajustes.carpetaLocal;
  $('#aj-vinculado').textContent = ajustes.vinculado ? '✓ Equipo vinculado (token guardado cifrado en Windows)' : 'Sin vincular';
  $('#aj-version').textContent = `JSE Captura ${ajustes.version}`;
  modoVisible();
  const sel = $('#aj-carpeta');
  sel.replaceChildren(el('option', { value: ajustes.carpeta }, ajustes.carpeta || '(carpeta principal)'));
  if (ajustes.vinculado && ajustes.modo === 'servicio') {
    try {
      const { carpetas } = await jse.nasCarpetas();
      sel.replaceChildren(...[...new Set([...carpetas, ajustes.carpeta])].map((c) => el('option', { value: c, selected: c === ajustes.carpeta }, c || '(carpeta principal)')), el('option', { value: '__nueva' }, '+ Nueva carpeta…'));
    } catch {}
  }
}
function modoVisible() {
  $('#aj-servicio').hidden = $('#aj-modo').value !== 'servicio';
  $('#aj-smb').hidden = $('#aj-modo').value !== 'smb';
}
$('#aj-modo').addEventListener('change', modoVisible);
$('#aj-carpeta').addEventListener('change', (e) => {
  if (e.target.value !== '__nueva') return;
  const n = $('#aj-carpeta-nueva');
  n.hidden = false;
  n.focus();
  n.onchange = () => {
    const v = n.value.trim();
    if (v) e.target.prepend(el('option', { value: v, selected: true }, v));
    n.hidden = true;
    n.value = '';
  };
});
$('#aj-smb-elegir').addEventListener('click', async () => {
  const c = await jse.elegirCarpeta();
  if (c) $('#aj-smb-carpeta').value = c;
});
$('#aj-carpeta-local-elegir').addEventListener('click', async () => {
  const c = await jse.elegirCarpeta();
  if (c) $('#aj-carpeta-local').value = c;
});
$('#aj-vincular').addEventListener('click', async () => {
  $('#aj-vinculado').textContent = 'Vinculando…';
  try {
    const r = await jse.nasVincular({ direccion: $('#aj-direccion').value.trim(), usuario: $('#aj-usuario').value.trim(), contrasena: $('#aj-contrasena').value });
    $('#aj-contrasena').value = '';
    aviso(`Vinculado como «${r.equipo}». Las grabaciones pendientes se empiezan a copiar.`, 'ok');
    pintarAjustes();
  } catch (e) {
    $('#aj-vinculado').textContent = `✗ ${e.message}`;
  }
});
$('#aj-desvincular').addEventListener('click', async () => {
  await jse.nasDesvincular();
  pintarAjustes();
});
$('#aj-probar').addEventListener('click', async () => {
  $('#aj-prueba').textContent = 'Probando…';
  await guardarAjustes(false);
  try {
    const r = await jse.nasProbar();
    $('#aj-prueba').textContent = r.ok
      ? `✓ Conexión correcta${r.espacioLibre ? ` · ${tam(r.espacioLibre)} libres en el NAS` : ''}${r.fotosJse ? ` · Fotos JSE: ${r.fotosJse.alcanzable === true ? 'responde' : r.fotosJse.alcanzable === false ? `no responde (${r.fotosJse.motivo || ''})` : 'sin comprobar'}` : ''}`
      : `✗ ${r.motivo}`;
  } catch (e) {
    $('#aj-prueba').textContent = `✗ ${e.message}`;
  }
});
async function guardarAjustes(avisar = true) {
  const atajo = (s) => s.trim().replace(/^Ctrl\+/i, 'CommandOrControl+');
  const r = await jse.ajustesGuardar({
    modo: $('#aj-modo').value,
    direccion: $('#aj-direccion').value.trim(),
    usuario: $('#aj-usuario').value.trim(),
    carpeta: $('#aj-carpeta').value === '__nueva' ? '' : $('#aj-carpeta').value,
    smbCarpeta: $('#aj-smb-carpeta').value.trim(),
    fotosJseEnSmb: $('#aj-smb-fotos').checked,
    atajo: atajo($('#aj-atajo').value) || 'CommandOrControl+Shift+R',
    atajoMarca: atajo($('#aj-atajo-marca').value),
    carpetaLocal: $('#aj-carpeta-local').value,
  });
  ajustes = { ...ajustes, ...r };
  if (avisar) $('#aj-guardado').textContent = r.erroresAtajo?.length ? `✗ El atajo ${r.erroresAtajo.join(', ')} ya lo usa otra app.` : '✓ Guardado';
}
$('#aj-guardar').addEventListener('click', () => guardarAjustes());

// ---------- estado de red ----------
async function pintarEstadoRed() {
  const l = await jse.biblioteca({ pendientes: true });
  const mal = l.filter((g) => g.destinos.nas.estado === 'requiere_atencion').length;
  const copiando = l.find((g) => g.destinos.nas.estado === 'copiando');
  const sinNas = !ajustes.modo || ajustes.modo === 'ninguno' || (ajustes.modo === 'servicio' && !ajustes.vinculado) || (ajustes.modo === 'smb' && !ajustes.smbCarpeta);
  $('#estado-red').textContent = sinNas ? `NAS: sin vincular${l.length ? ` · ${l.length} esperando` : ''}` : copiando ? `NAS: copiando ${Math.round((copiando.destinos.nas.progreso || 0) * 100)}%` : mal ? `NAS: ${mal} requieren atención` : l.length ? `NAS: ${l.length} pendientes` : 'NAS: al día';
  $('#estado-red').className = `estado-red ${sinNas ? 'pend' : mal ? 'mal' : l.length ? 'pend' : 'ok'}`;
}
window.addEventListener('online', () => jse.redDisponible());

// ---------- inicio ----------
(async () => {
  ajustes = await jse.ajustes();
  $('#op-calidad').value = ajustes.calidad;
  $('#op-microfono').checked = ajustes.microfono;
  $('#op-sistema').checked = ajustes.audioSistema;
  await cargarFuentes();
  resumen();
  pintarEstadoRed();
  pintarBiblioteca();
})();
