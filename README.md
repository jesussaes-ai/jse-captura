# JSE Captura

Grabador de pantalla en español para **Windows 11, Android e iPhone**. Cada grabación queda como un
**MP4 H.264/AAC con faststart** (abre en VLC, mpv, el reproductor del sistema y cualquier programa open
source), se guarda primero en el equipo y después se copia sola al **NAS UGREEN**, donde aparece en
**Fotos JSE**.

Graba aunque el NAS no exista o esté apagado: la copia queda «Pendiente» y se hace al volver a casa.

| Carpeta | Qué es |
|---|---|
| `escritorio/` | App de Windows (Electron + ffmpeg). Instalador y versión portátil. |
| `android/` | App de Android 10+ (Kotlin, MediaProjection + MediaCodec). |
| `ios/` | App de iPhone/iPad 16+ (SwiftUI + extensión de transmisión ReplayKit). |
| `nas-servicio/` | Servicio Docker para el NAS: recibe subidas reanudables, comprueba SHA-256 y publica en Fotos JSE. |
| `docs/GUIA.md` | **Guía corta para usar e instalar todo** (empieza aquí). |
| `docs/protocolo.md` | Protocolo HTTP del NAS con pruebas `curl`. |

## Paquetes listos

Los arma GitHub Actions en cada cambio (pestaña **Actions** del repositorio):

- **Windows · pruebas e instalador**: artefacto `JSE-Captura-Windows`, con `JSE-Captura-Instalador-x.y.z.exe` y `JSE-Captura-Portatil-x.y.z.exe`.
- **Android · APK**: artefacto `JSE-Captura-Android`, con `app-release.apk`. Está firmado con la clave de depuración; para instalarlo hay que permitir «Instalar apps desconocidas».
- **iOS · compilación**: solo comprueba que compila. Para instalar en el iPhone se necesita una Mac con Xcode (ver la guía).

Para descargar desde una terminal: `gh run download <id> -R jesussaes-ai/jse-captura -n JSE-Captura-Windows`.

## Compilar a mano

```bash
# Windows (en Windows; si falla "Cannot create symbolic link", activa el Modo de desarrollador o usa el instalador de Actions)
cd escritorio && npm ci && npm test && npm run dist

# Android (JDK 17)
cd android && ./gradlew assembleRelease     # app/build/outputs/apk/release/

# iPhone (Mac con Xcode 16)
brew install xcodegen && cd ios && xcodegen generate && open JSECaptura.xcodeproj

# Servicio del NAS
cd nas-servicio && npm test && cp .env.ejemplo .env && docker compose up -d --build
```

No hay secretos en el repositorio. La contraseña del servicio vive solo en el `.env` del NAS, y cada
equipo guarda su token cifrado: DPAPI en Windows, Keystore en Android y el Llavero en iPhone.
