#!/usr/bin/env bash
# Prueba completa del flujo de grabación con botón flotante en el emulador:
#  Abrir la app → el botón ● REC queda flotando → ir a otra app → tocar ● REC → graba → ❚❚ pausa ~7 s → ▶ sigue
#  → ■ termina → pide el nombre → se guarda con ese nombre, sin el tiempo de la pausa
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
centro() { # centro del elemento cuyo atributo (text o content-desc) empieza con $1 ($2=exacto: nada después)
  resto='[^"]*'; [ "${2:-}" = exacto ] && resto=''
  b=$(grep -oE "(text|content-desc)=\"$1$resto\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" /tmp/g.xml | head -1 | grep -oE 'bounds=.*' | grep -oE '[0-9]+' | tr '\n' ' ')
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
antes=$(adb shell "ls '/sdcard/Android/data/$P/files/Movies/JSE Captura' 2>/dev/null" | grep -c mp4)

# 1) Al abrir la app, el botón ● REC queda flotando solo (sin «preparar»), y no graba nada todavía.
pos=$(burbuja) && bien "botón ● REC visible al abrir la app ($pos)" || mal "no apareció el botón flotante al abrir la app"
adb exec-out screencap -p > pantallas/1-boton.png

# 2) Ir a otra app (como YouTube) con el botón todavía a la vista, y tocar ● REC: graba de inmediato.
#    Ojo: la app Ajustes de Android oculta a propósito los botones flotantes de todas las apps (anti-engaños).
adb shell appops set $P GET_USAGE_STATS allow || true
adb shell am start -a android.intent.action.VIEW -d "https://example.com/" >/dev/null 2>&1 || adb shell input keyevent KEYCODE_HOME
sleep 5
volcar; for t in "Use without an account" "Accept & continue" "No thanks" "No, thanks"; do c=$(centro "$t") && { adb shell input tap $c; sleep 2; volcar; }; done
pos=$(burbuja) && bien "el botón sigue a la vista en otra app" || { mal "el botón no se ve en otra app"; adb shell input keyevent KEYCODE_HOME; sleep 2; pos=$(burbuja); }
n=$(adb shell "ls '/sdcard/Android/data/$P/files/Movies/JSE Captura' 2>/dev/null" | grep -c mp4)
[ "$n" = "$antes" ] && bien "todavía no se creó ninguna grabación" || mal "se creó una grabación antes de tocar ● REC"
[ -n "${pos:-}" ] && { adb shell input tap $pos; bien "toqué ● REC"; }
sleep 3
volcar
for t in "Start now" "Iniciar ahora" "Start" "Iniciar"; do c=$(centro "$t") && { adb shell input tap $c; sleep 3; break; }; done
volcar
if grep -q 'text="Entire screen"\|text="Pantalla completa"\|text="A single app"' /tmp/g.xml; then
  c=$(centro "A single app") && { adb shell input tap $c; sleep 1; volcar; c=$(centro "Entire screen") && adb shell input tap $c; sleep 1; volcar; }
  for t in "Start" "Next" "Iniciar"; do c=$(centro "$t") && { adb shell input tap $c; sleep 3; break; }; done
fi
t0=$(date +%s)
sleep 3
adb exec-out screencap -p > pantallas/2-grabando.png
adb shell dumpsys notification --noredact | grep -q 'Grabando la pantalla' && bien "graba de inmediato (notificación «Grabando»)" || { mal "no se ve la notificación de grabando"; adb shell dumpsys notification --noredact | grep -E "android.title|android.text" | head -20; }
app=$(adb logcat -d | grep -m1 -oE 'app al frente=[^ ]*.*' | tr -d '\r')
echo "• $app"

# 2b) Pausa ~6 s y seguir: lo de la pausa no debe quedar en el video.
marco() { adb shell dumpsys window windows | grep -A40 'JSE Captura burbuja' | grep -m1 -oE ' frame=\[-?[0-9]+,-?[0-9]+\]\[-?[0-9]+,-?[0-9]+\]' | grep -oE -- '-?[0-9]+' | tr '\n' ' '; }
izq() { set -- $(marco); [ $# -eq 4 ] && echo "$(( $1 + ($3-$1)/4 )) $(( ($2+$4)/2 ))"; }
der() { set -- $(marco); [ $# -eq 4 ] && echo "$(( $3 - ($3-$1)/4 )) $(( ($2+$4)/2 ))"; }
p=$(izq) && { adb shell input tap $p; bien "toqué ❚❚ pausa"; } || mal "sin botón de pausa"
sleep 2
adb shell dumpsys notification --noredact | grep -q 'Grabación en pausa' && bien "notificación «en pausa»" || mal "no se ve la pausa en la notificación"
adb exec-out screencap -p > pantallas/2b-pausa.png
sleep 5
p=$(izq) && { adb shell input tap $p; bien "toqué ▶ seguir"; } || mal "sin botón para seguir"
sleep 3
adb shell dumpsys notification --noredact | grep -q 'Grabando la pantalla' && bien "siguió grabando" || mal "no siguió grabando tras la pausa"

# 3) Detener desde la burbuja y poner nombre.
p=$(der) && { adb shell input tap $p; bien "toqué ■ para terminar"; } || mal "sin botón para terminar"
total=$(( $(date +%s) - t0 ))
sleep 8
volcar; adb exec-out screencap -p > pantallas/3-nombre.png
if grep -q 'Cómo se llama esta grabación' /tmp/g.xml; then
  bien "pidió el nombre"
  c=$(grep -oE 'class="android.widget.EditText"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' /tmp/g.xml | head -1 | grep -oE 'bounds=.*' | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $c; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
  adb shell input keyevent KEYCODE_MOVE_END; for i in $(seq 1 60); do adb shell input keyevent KEYCODE_DEL; done
  adb shell input text "Curso%sde%sprueba"; sleep 1
  volcar; c=$(centro "Guardar" exacto) || c=$(centro "GUARDAR" exacto) || c=""
  if [ -n "${c:-}" ]; then adb shell input tap $c; else adb shell input keyevent KEYCODE_ENTER; fi; sleep 3
else mal "no apareció la ventana para poner el nombre"; fi

CARP="/sdcard/Android/data/$P/files/Movies/JSE Captura"
echo "archivos: $(adb shell "ls '$CARP'" | tr '\r\n' '  ')"
ruta=$(adb shell "ls '$CARP'" | tr -d '\r' | grep 'Curso de prueba\.mp4$' | head -1)
if [ -n "$ruta" ]; then
  bien "MP4 guardado con el nombre: $ruta"
  adb pull "$CARP/$ruta" pantallas/prueba.mp4 >/dev/null 2>&1 || true
else mal "no hay un MP4 llamado «… Curso de prueba.mp4»"; fi
if [ -s pantallas/prueba.mp4 ] && command -v ffprobe >/dev/null; then
  ffprobe -v error -show_entries stream=codec_name,width,height -show_entries format=duration -of compact pantallas/prueba.mp4 | tee /tmp/probe.txt
  grep -q 'codec_name=h264' /tmp/probe.txt && bien "video H.264" || mal "sin pista H.264"
  grep -q 'codec_name=aac' /tmp/probe.txt && bien "audio AAC" || echo "• sin pista de audio (el emulador puede no tener audio que capturar)"
  d=$(grep -oE 'duration=[0-9.]+' /tmp/probe.txt | tail -1 | cut -d= -f2)
  echo "• tiempo total con la pausa: ${total:-?}s"
  awk -v d="$d" -v t="${total:-99}" 'BEGIN{exit !(d>=3 && d<=t-4)}' && bien "duración ${d}s: sin los ~7 s de pausa (total ${total}s)" || mal "duración inesperada: ${d}s (total ${total:-?}s; la pausa debía quitarse)"
  ffprobe -v error -select_streams v -show_entries packet=pts_time -of csv=p=0 pantallas/prueba.mp4 | awk -F, 'NR>1 && $1-p>1.0 {print "hueco de " $1-p " s en " p; h=1} {p=$1} END{exit h}' && bien "video continuo, sin hueco por la pausa" || mal "el video tiene un hueco donde iba la pausa"
fi

# 4) Miniatura → pantalla completa con la versión.
adb shell am start -W -n $P/.MainActivity >/dev/null; sleep 3
TAM=$(adb shell wm size | sed 's/.*: //' | tr -d '\r'); W=${TAM%x*}; H=${TAM#*x}
for i in 1 2; do adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/4)) 300; sleep 1; done   # baja a la lista
volcar; grep -q 'text="Curso de prueba"' /tmp/g.xml && bien "la lista muestra «Curso de prueba»" || mal "la lista no muestra el nombre"
tocar "Ver «Curso de prueba»" && sleep 4
volcar; c=$(centro "Got it") && { adb shell input tap $c; sleep 1; bien "aviso de pantalla completa de Android cerrado"; }
adb shell input keyevent KEYCODE_SPACE; sleep 2   # en pausa la pantalla queda quieta y se puede leer
rm -f /tmp/g.xml; adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml /tmp/g.xml >/dev/null 2>&1; grep -oE 'text="[^"]+"' /tmp/g.xml | head -12 | tr "\n" " "; echo; adb exec-out screencap -p > pantallas/4-pantalla-completa.png
cp /tmp/g.xml pantallas/4-pantalla-completa.xml
grep -q "text=\"JSE Captura v$VER\"" /tmp/g.xml && bien "pantalla completa con v$VER" || mal "pantalla completa sin la versión"
adb shell dumpsys activity activities | grep -q 'PantallaCompletaActivity' && bien "abrió la pantalla completa" || mal "no abrió la pantalla completa"
TAM=$(adb shell wm size | sed 's/.*: //' | tr -d '\r'); W=${TAM%x*}; H=${TAM#*x}
sleep 1; volcar
{ c=$(centro "1:1" exacto) || c=$(centro "2×" exacto); } && adb shell input tap $c && { sleep 1; adb exec-out screencap -p > pantallas/5-tamano-original.png; bien "tamaño original 1:1"; } || true

# 5) Grabar desde un link: compartir un link a la app, abrirlo y que grabe sola.
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Mira este curso https://example.com/curso'" -n $P/.MainActivity >/dev/null; sleep 3
volcar
grep -q 'text="https://example.com/curso"' /tmp/g.xml && bien "link compartido llegó al campo" || { mal "el link compartido no llenó el campo"; grep -oE 'text="[^"]*"' /tmp/g.xml | head -30 | tr '\n' ' '; echo; }
antes=$(adb shell "ls '$CARP'" | grep -c mp4)
c=$(centro "▶ Abrir y grabar" exacto) || { adb shell input swipe $((W/2)) $((H*3/5)) $((W/2)) $((H*2/5)) 300; sleep 1; volcar; c=$(centro "▶ Abrir y grabar" exacto); }
if [ -n "${c:-}" ]; then adb shell input tap $c; bien "toqué «Abrir y grabar»"; else mal "no encontré «Abrir y grabar»"; fi
sleep 3; volcar
for t in "Start now" "Iniciar ahora" "Start" "Iniciar"; do c=$(centro "$t" exacto) && { adb shell input tap $c; sleep 2; break; }; done
sleep 7
foco=$(adb shell dumpsys window | grep -m1 -E 'mCurrentFocus|mFocusedApp' | tr -d '\r')
echo "en pantalla: $foco"
echo "$foco" | grep -q "$P" && echo "• el link no abrió otra app (¿el emulador no tiene navegador?)" || bien "el link se abrió en otra app"
adb shell dumpsys notification --noredact | grep -q 'Grabando la pantalla' && bien "empezó a grabar sola tras abrir el link" || { mal "no empezó a grabar con el link"; adb logcat -d | grep -E "JSECaptura" | tail -8; }
p=$(der) && adb shell input tap $p
sleep 8; volcar
c=$(centro "Guardar" exacto) || c=$(centro "GUARDAR" exacto) || c=""
[ -n "$c" ] && adb shell input tap $c
sleep 3
despues=$(adb shell "ls '$CARP'" | grep -c mp4)
nuevo=$(adb shell "ls '$CARP'" | tr -d '\r' | grep 'example.com' | head -1)
[ "$despues" -gt "$antes" ] && [ -n "$nuevo" ] && bien "grabación del link guardada: $nuevo" || mal "no se guardó la grabación del link ($antes → $despues)"

if ! adb shell pidof $P >/dev/null; then mal "la app se cerró"; fi
adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -50
echo "---- registro de la app ----"
adb logcat -d | grep -E "JSECaptura|JSECodificador|AndroidRuntime|ActivityTaskManager.*(mx.jse|Background activity)|MediaProjection" | tail -60 | tee pantallas/logcat.txt
exit $fallos
