# Guía rápida de JSE Captura

## 1. Instalar

### Windows 11
1. Descarga `JSE-Captura-Instalador-x.y.z.exe` (Actions → «Windows · pruebas e instalador» → artefacto `JSE-Captura-Windows`).
2. Ábrelo. Si Windows SmartScreen avisa, elige «Más información → Ejecutar de todas formas»: el instalador no está firmado con certificado comercial.
3. Si no quieres instalar nada, usa `JSE-Captura-Portatil-x.y.z.exe`, que corre sin instalar.

### Android 10 o más nuevo
1. Descarga `app-release.apk` (artefacto `JSE-Captura-Android`) y ábrelo en el teléfono.
2. Permite «Instalar apps desconocidas» para el navegador o la app de Archivos.
3. Si prefieres usar cable o ADB inalámbrico: `adb install -r app-release.apk`.
4. La primera vez acepta las notificaciones; ahí aparecen los botones Detener y Marcar. El micrófono solo se pide si lo activas.
5. Opcional: desliza los Ajustes rápidos → lápiz → arrastra el mosaico **JSE Captura**.

### iPhone / iPad (iOS 16 o más nuevo)
Apple no permite instalar apps fuera de la App Store sin firmarlas, así que se necesita una Mac:
1. En la Mac: `brew install xcodegen`, luego `cd ios && xcodegen generate && open JSECaptura.xcodeproj`.
2. En Xcode → Signing, elige tu Apple ID (sirve una cuenta gratuita) en los dos targets: **JSECaptura** y **Transmision**.
3. Si Xcode pide cambiar el identificador, cambia `PRODUCT_BUNDLE_IDENTIFIER` de los dos targets en `project.yml`, el `preferredExtension` en `JSECapturaApp.swift` y el App Group en los dos sitios.
4. Conecta el iPhone y pulsa ▶. Con una cuenta gratuita la app caduca a los 7 días y se vuelve a instalar igual; con la cuenta de desarrollador (99 USD/año) dura 1 año o se puede pasar por TestFlight.

## 2. Primera grabación (sin NAS)
- **Windows**: elige un monitor o una ventana, activa o desactiva el micrófono y el audio del sistema, y pulsa **● Grabar**. Mientras graba aparece un indicador flotante que no sale en el video. Atajos: `Ctrl+Shift+R` inicia o detiene la grabación y `Ctrl+Shift+M` marca un momento (se cambian en Ajustes).
- **Android**: pulsa **● Grabar** y luego «Iniciar ahora». En Android 14+ puedes elegir una sola app. Para detener usa la notificación, el mosaico de Ajustes rápidos o la app.
- **iPhone**: pulsa el botón rojo, elige «JSE Captura» y luego «Iniciar transmisión». Para detener toca la barra o burbuja roja de arriba. Al volver a la app, el MP4 aparece en la Biblioteca.

El MP4 se guarda en el equipo:
- Windows: `Videos\JSE Captura`.
- Android: memoria de la app, y opcionalmente en la Galería (`Movies/JSE Captura`).
- iPhone: app Archivos → En mi iPhone → JSE Captura, y opcionalmente Fotos.

## 3. Poner el servicio en el NAS (una sola vez)
1. Copia la carpeta `nas-servicio` al NAS, por ejemplo a `/volume1/docker/jse-captura`.
2. Copia `.env.ejemplo` a `.env` y ajusta estos valores:
   - `JSE_CONTRASENA`: una contraseña larga.
   - `JSE_IP_LAN`: la IP del NAS.
   - `JSE_CARPETA_NAS`: la carpeta que indexa Fotos JSE.
   - `PUID` y `PGID`: los del dueño de esa carpeta.
3. En la app Docker de UGOS → Proyecto → Crear, usa ese `docker-compose.yml`. Por SSH también funciona: `docker compose up -d --build`.
4. Prueba desde la PC: `curl http://IP-DEL-NAS:8790/api/v1/salud`. Debe responder `"ok":true`.

El puerto solo escucha en la red de casa. **No lo abras en el router.** Desde fuera usa Tailscale.

## 4. Vincular cada equipo
Ve a Ajustes → NAS, escribe `IP-DEL-NAS:8790`, el usuario `jse-captura` y la contraseña del `.env`, y pulsa **Vincular**.

La contraseña no se guarda: el equipo recibe su propio token cifrado. Para quitarle el acceso a un equipo perdido:
- `docker compose exec jse-captura node src/cli.mjs equipos` muestra los equipos vinculados.
- `docker compose exec jse-captura node src/cli.mjs revocar <id>` le quita el acceso.

## 5. Comprobar la copia
Cada grabación muestra cómo va su copia en cada destino:

| Estado | Qué significa |
|---|---|
| **Pendiente** | Todavía no se copia (por ejemplo, el NAS no está disponible). Se reintenta sola. |
| **Copiando nn %** | La copia está en curso. Si se corta, sigue desde donde iba. |
| **Guardado** | El NAS comprobó el SHA-256 y el MP4 ya está en su carpeta final. |
| **Visible en Fotos JSE** | Fotos JSE ya lo indexó y lo reproduce. |
| **Requiere atención** | El problema necesita que hagas algo. El motivo viene escrito en la grabación. |

El botón **Sincronizar ahora** reintenta todo lo pendiente al momento.

## 6. HTTPS y certificados
En casa basta `http://IP:8790`, porque la red local es de confianza y el token viaja solo dentro de ella.

Si quieres HTTPS, usa `tailscale serve` en el NAS o un proxy con certificado válido. Un certificado autofirmado hace que iPhone y Android rechacen la conexión, y la app lo indica como «certificado».

## 7. Versiones compatibles
- Windows 10 21H2 o 11, de 64 bits.
- Android 10 o más nuevo. El audio interno requiere Android 10+ y que la app no lo bloquee.
- iOS/iPadOS 16 o más nuevo.
- En el NAS, Docker con x86-64 o ARM64.

## 8. Estado de cada función
Leyenda: ✅ verificado · 🧪 implementado, falta probar en el equipo real · ⏳ pendiente

| Función | Windows | Android | iPhone | NAS |
|---|---|---|---|---|
| Grabar pantalla completa | ✅ (en tu PC: H.264 1728×1080) | 🧪 | 🧪 | — |
| Elegir monitor o ventana / app | ✅ monitor · 🧪 ventana | 🧪 (Android 14+) | — (iOS solo pantalla completa) | — |
| Micrófono / audio interno | 🧪 | 🧪 | 🧪 (micrófono en 2.ª pista) | — |
| MP4 H.264/AAC con faststart (VLC, mpv) | ✅ (decodificado con ffmpeg) | 🧪 | 🧪 | ✅ valida con ffprobe |
| Marcas, título, notas, etiquetas, favoritos | ✅ marcas · 🧪 resto | 🧪 | 🧪 | ✅ |
| Recuperar tras un cierre inesperado | 🧪 | 🧪 (segmentos de 60 s) | 🧪 (fragmentos de 5 s) | ✅ |
| Sin espacio: se detiene sin perder lo grabado | 🧪 | 🧪 | ⏳ | ✅ reserva libre |
| Subida reanudable + SHA-256 + idempotente | ✅ (pruebas) | 🧪 | 🧪 | ✅ 10/10 pruebas |
| Copia a Fotos JSE y comprobación «Visible» | 🧪 | 🧪 | 🧪 | ✅ con Fotos JSE simulado · 🧪 real |
| Galería del teléfono | — | 🧪 | 🧪 | — |
| Ver catálogo del NAS / enlace para VLC | 🧪 | 🧪 | 🧪 | ✅ |
| Borrar del equipo / borrar del NAS por separado | 🧪 | 🧪 | 🧪 | ✅ |
| Recortar (MP4 nuevo) | 🧪 | 🧪 | 🧪 | — |
| Instalador / paquete | ✅ (generado en Actions) | ✅ APK (Actions) | ✅ compila en Actions · ⏳ instalar necesita Mac + Apple ID | ✅ Docker en CI · ⏳ en tu UGREEN |
