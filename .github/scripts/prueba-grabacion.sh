#!/usr/bin/env bash
# Prueba completa del flujo de grabación con botón flotante en el emulador:
#  Preparar → (no graba todavía) → tocar ● REC → graba ~6 s → tocar ■ → pide el nombre → se guarda con ese nombre
#  → el MP4 existe con video y audio → la miniatura abre la pantalla completa con la versión arriba a la derecha.
set -uo pipefail
APK=$(ls "$1"/*.apk | head -1)
VER=$(basename "$APK" .apk | sed 's/.*-//')
P=mx.jse.captura
mkdir -p pantallas
fallos=0
mal() { echo "✗ $*"; fallos=1; }
bien() { echo "✓ $*"; }
volcar() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/g.xml >/dev/null 2>&1; }
centro() { # centro del elemento cuyo atributo (text o content-desc) empieza con $1
  b=$(grep -oE "(text|content-desc)=\"$1[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" /tmp/g.xml | head -1 | grep -oE 'bounds=.*' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && return 1
  set -- $b; echo "$(( ($1+$3)/2 )) $(( ($2+$4)/2 ))"
}
tocar() { volcar; c=$(centro "$1") || { mal "no encontré «$1» en pantalla"; return 1; }; adb shell input tap $c; }
burbuja() { # centro de la ventana flotante (por su título)
  f=$(adb shell dumpsys window windows | grep -A40 'JSE Captura burbuja' | grep -m1 -oE ' frame=\[-?[0-9]+,-?[0-9]+\]\[-?[0-9]+,-?[0-9]+\]' | grep -oE -- '-?[0-9]+' | tr '\n' ' ')
  [ -z "$f" ] && return 1
  set -- $f; echo "$(( ($1+$3)/2 )) $(( ($2+$4)/2 ))"
}
biblioteca() { adb shell run-as $P cat files/biblioteca.json 2>/dev/null; }

adb install -r -g "$APK" >/dev/null
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell appops set $P PROJECT_MEDIA allow
adb shell pm grant $P android.permission.RECORD_AUDIO || true
adb shell am force-stop $P
adb logcat -c
adb shell am start -W -n $P/.MainActivity >/dev/null; sleep 3
antes=$(biblioteca | grep -o '"id"' | wc -l)

# 1) Preparar: acepta el permiso del sistema si aparece y debe quedar el botón flotante, sin grabar aún.
tocar "● Preparar grabación"; sleep 3
volcar
for t in "Start now" "Iniciar ahora" "Start" "Iniciar"; do c=$(centro "$t") && { adb shell input tap $c; sleep 3; break; }; done
volcar
if grep -q 'text="Entire screen"\|text="Pantalla completa"\|text="A single app"' /tmp/g.xml; then
  c=$(centro "A single app") && { adb shell input tap $c; sleep 1; volcar; c=$(centro "Entire screen") && adb shell input tap $c; sleep 1; }
  for t in "Start" "Next" "Iniciar"; do c=$(centro "$t") && { adb shell input tap $c; sleep 3; break; }; done
fi
adb exec-out screencap -p > pantallas/1-preparado.png
pos=$(burbuja) && bien "botón flotante visible en ($pos)" || mal "no apareció el botón flotante"
sleep 3
n=$(biblioteca | grep -o '"id"' | wc -l)
[ "$n" = "$antes" ] && bien "preparado: todavía no se creó ninguna grabación" || mal "se creó una grabación antes de tocar ● REC"

# 2) Ir a la pantalla de inicio (como al abrir YouTube) y empezar desde la burbuja.
#    Ojo: la app Ajustes de Android oculta a propósito los botones flotantes de todas las apps (anti-engaños).
adb shell input keyevent KEYCODE_HOME; sleep 2
pos=$(burbuja) && { adb shell input tap $pos; bien "toqué ● REC"; } || mal "sin burbuja para empezar"
sleep 7
adb exec-out screencap -p > pantallas/2-grabando.png
dumpsys_n=$(adb shell dumpsys notification --noredact | grep -c 'Grabando la pantalla' || true)
[ "$dumpsys_n" -gt 0 ] && bien "notificación «Grabando»" || { mal "no se ve la notificación de grabando"; adb shell dumpsys notification --noredact | grep -E "mx.jse.captura|android.title|android.text" | head -20; }

# 3) Detener desde la burbuja y poner nombre.
pos=$(burbuja) && { adb shell input tap $pos; bien "toqué ■ para detener"; } || mal "sin burbuja para detener"
sleep 8
volcar; adb exec-out screencap -p > pantallas/3-nombre.png
if grep -q 'Cómo se llama esta grabación' /tmp/g.xml; then
  bien "pidió el nombre"
  c=$(grep -oE 'class="android.widget.EditText"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' /tmp/g.xml | head -1 | grep -oE 'bounds=.*' | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $c; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
  adb shell input keyevent KEYCODE_MOVE_END; for i in $(seq 1 60); do adb shell input keyevent KEYCODE_DEL; done
  adb shell input text "Curso%sde%sprueba"; sleep 1
  volcar; c=$(centro "Guardar") || c=$(centro "GUARDAR") || { adb shell input keyevent KEYCODE_ESCAPE; sleep 1; volcar; c=$(centro "Guardar") || c=$(centro "GUARDAR"); }
  if [ -n "${c:-}" ]; then adb shell input tap $c; else adb shell input keyevent KEYCODE_ENTER; fi; sleep 3
else mal "no apareció la ventana para poner el nombre"; fi

j=$(biblioteca)
echo "títulos: $(echo "$j" | grep -oE '"titulo":"[^"]*"' | tr '\n' ' ')"
echo "$j" | grep -q '"titulo":"Curso de prueba"' && bien "guardada con el nombre «Curso de prueba»" || mal "el título no quedó como «Curso de prueba»"
ruta=$(echo "$j" | grep -oE '"archivo":"[^"]*Curso de prueba\.mp4"' | head -1 | sed 's/"archivo":"//; s/"$//; s#\\/#/#g')
if [ -n "$ruta" ]; then
  bien "archivo: $ruta"
  adb shell "ls -l '$ruta'" || mal "el MP4 no existe"
  adb pull "$ruta" pantallas/prueba.mp4 >/dev/null 2>&1 || true
else mal "el MP4 no se renombró con el nombre"; fi
if [ -s pantallas/prueba.mp4 ] && command -v ffprobe >/dev/null; then
  ffprobe -v error -show_entries stream=codec_name,width,height -show_entries format=duration -of compact pantallas/prueba.mp4 | tee /tmp/probe.txt
  grep -q 'codec_name=h264' /tmp/probe.txt && bien "video H.264" || mal "sin pista H.264"
  grep -q 'codec_name=aac' /tmp/probe.txt && bien "audio AAC" || echo "• sin pista de audio (el emulador puede no tener audio que capturar)"
  d=$(grep -oE 'duration=[0-9.]+' /tmp/probe.txt | tail -1 | cut -d= -f2)
  awk -v d="$d" 'BEGIN{exit !(d>=4 && d<=12)}' && bien "duración ${d}s (empezó al tocar ● REC, no al preparar)" || mal "duración inesperada: ${d}s"
fi

# 4) Miniatura → pantalla completa con la versión.
adb shell am start -W -n $P/.MainActivity >/dev/null; sleep 3
tocar "Ver «Curso de prueba»" && sleep 4
volcar; adb exec-out screencap -p > pantallas/4-pantalla-completa.png
cp /tmp/g.xml pantallas/4-pantalla-completa.xml
grep -q "text=\"v$VER\"" /tmp/g.xml && bien "pantalla completa con v$VER" || mal "pantalla completa sin la versión"
adb shell dumpsys activity activities | grep -q 'PantallaCompletaActivity' && bien "abrió la pantalla completa" || mal "no abrió la pantalla completa"
TAM=$(adb shell wm size | sed 's/.*: //' | tr -d '\r'); W=${TAM%x*}; H=${TAM#*x}
adb shell input tap $((W/2)) $((H/2)); sleep 1; volcar
tocar "1:1" && { sleep 1; adb exec-out screencap -p > pantallas/5-tamano-original.png; bien "tamaño original 1:1"; } || true

if ! adb shell pidof $P >/dev/null; then mal "la app se cerró"; fi
adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -50
echo "---- registro de la app ----"
adb logcat -d | grep -E "JSECaptura|JSECodificador|AndroidRuntime|ActivityTaskManager.*(mx.jse|Background activity)|MediaProjection" | tail -60 | tee pantallas/logcat.txt
exit $fallos
