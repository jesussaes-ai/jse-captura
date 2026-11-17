# Protocolo del servicio NAS de JSE Captura (v1)

Servicio HTTP pequeño (`nas-servicio/`) que corre en el NAS con Docker Compose. Solo red local o Tailscale.
Todas las respuestas son JSON en español. Los errores tienen la forma `{ "error": "<código>", "mensaje": "<texto legible>" }`.

## Autenticación

| Paso | Petición | Respuesta |
|---|---|---|
| Vincular un equipo | `POST /api/v1/sesion` `{usuario, contrasena, equipo}` | `200 {token, equipoId, equipo}` · `401 credenciales_invalidas` · `429 demasiados_intentos` (10 fallos / 10 min por IP) |
| Usar el token | cabecera `Authorization: Bearer jsec_…` | sin token o revocado → `401 credenciales_invalidas` |
| Desvincular | `DELETE /api/v1/sesion` | revoca el token de ese equipo |

El NAS guarda solo el hash SHA-256 de cada token (`datos/equipos.json`). Revocar desde el NAS:
`docker compose exec jse-captura node src/cli.mjs revocar "<nombre o id>"`.

## Probar conexión

`GET /api/v1/salud` (sin token) → `{servicio, version, ok}`.
`GET /api/v1/prueba` (con token) → `{ok, escritura, motivo, espacioLibre, maxArchivo, grabaciones, fotosJse:{modo,url,alcanzable}}`.

## Carga idempotente y reanudable

1. `POST /api/v1/subidas` con `{id (UUID de la grabación), tamano, sha256, titulo, carpeta, creado, creadoLocal, duracion, origen, notas, etiquetas, favorito, marcas, recorteDe}`
   - `201 {estado:"incompleta", desplazamiento}`: enviar desde `desplazamiento`.
   - `200 {estado:"guardado", grabacion}`: ya estaba completa con el mismo SHA-256 (reintento tras perder la respuesta) → no se crea otra copia.
   - `409 conflicto_id` (mismo id, otro contenido) · `413 archivo_demasiado_grande` · `507 sin_espacio` · `400 carpeta_invalida`.
2. `PATCH /api/v1/subidas/{id}` con `Upload-Offset: <n>` y el cuerpo binario (trozos de 8–16 MB).
   - `200 {estado:"incompleta", desplazamiento}` → siguiente trozo.
   - `201 {estado:"guardado", grabacion}` → publicado.
   - `409 desplazamiento_incorrecto {desplazamiento}` → continuar desde ahí.
   - `400 transferencia_incompleta {desplazamiento}` → la conexión se cortó; lo recibido se conserva.
   - `422 hash_no_coincide` → el archivo llegó dañado; se descarta y hay que reenviarlo desde 0.
   - `422 video_invalido` → no tiene pista de video o duración válida (ffprobe).
3. `GET /api/v1/subidas/{id}` → `{estado, desplazamiento, tamano}` para saber desde dónde seguir.

**Reintentos grandes: se reanudan por partes**, no reinician la transferencia. Mientras llega, el archivo vive en
`<grabaciones>/.jse-incompletos/<id>.part` (carpeta oculta: Fotos JSE y el explorador no la muestran). Al completar:
SHA-256 → ffprobe → `fsync` → **renombrado atómico** a `<carpeta>/AAAA/MM/AAAA-MM-DD HH.mm.ss <título> [<id8>].mp4` → catálogo.
Los temporales abandonados se borran a las 72 h (`JSE_HORAS_INCOMPLETOS`). Si el servicio se cae entre mover y catalogar,
al arrancar se completa el catálogo verificando el hash.

## Catálogo y reproducción

| Petición | Uso |
|---|---|
| `GET /api/v1/grabaciones?pagina=1&limite=50&q=&favoritos=1` | listar por páginas (más recientes primero) |
| `GET/PATCH/DELETE /api/v1/grabaciones/{id}` | ficha · editar `titulo, notas, etiquetas, favorito, marcas` (el id y el archivo no cambian) · borrar **solo la copia del NAS** |
| `GET /api/v1/grabaciones/{id}/video` | MP4 con `Range` (adelantar sin descargar todo) |
| `GET /api/v1/grabaciones/{id}/enlace` | enlace firmado `/v/{id}.mp4?exp&firma` (4 h) para VLC, mpv, navegador o galería sin cabeceras |
| `GET /api/v1/grabaciones/{id}/miniatura` | JPG 480 px |
| `POST /api/v1/grabaciones/{id}/fotos-jse` | exportar/comprobar en Fotos JSE → `{estado: exportado|visible|requiere_atencion, motivo}` |

## Pruebas con curl

```bash
NAS=http://192.168.100.249:8790
curl $NAS/api/v1/salud
TOKEN=$(curl -s -X POST $NAS/api/v1/sesion -H 'Content-Type: application/json' \
  -d '{"usuario":"jse-captura","contrasena":"…","equipo":"curl"}' | jq -r .token)
curl -H "Authorization: Bearer $TOKEN" $NAS/api/v1/prueba
# Subir un MP4 en un solo trozo
F=prueba.mp4; ID=$(uuidgen | tr A-Z a-z); SHA=$(sha256sum $F | cut -d' ' -f1); SIZE=$(stat -c%s $F)
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -X POST $NAS/api/v1/subidas \
  -d "{\"id\":\"$ID\",\"tamano\":$SIZE,\"sha256\":\"$SHA\",\"titulo\":\"Prueba curl\"}"
curl -H "Authorization: Bearer $TOKEN" -H 'Upload-Offset: 0' -X PATCH --data-binary @$F $NAS/api/v1/subidas/$ID
# Repetir el mismo PATCH → {"estado":"guardado"} (no se duplica)
curl -H "Authorization: Bearer $TOKEN" "$NAS/api/v1/grabaciones?limite=5"
curl -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=0-1023' -o /dev/null -w '%{http_code}\n' $NAS/api/v1/grabaciones/$ID/video   # 206
curl -s $NAS/api/v1/grabaciones   # sin token → 401
```
