#!/usr/bin/env bash
# Abre cada pantalla de JSE Captura en el emulador y comprueba:
#  1) la versión (vX.Y.Z) aparece y está arriba a la derecha;
#  2) ningún elemento queda debajo de la barra de estado del sistema.
set -euo pipefail
APK=$(ls "$1"/*.apk | head -1)
VER=$(basename "$APK" .apk | sed 's/.*-//')
mkdir -p pantallas
adb install -r -g "$APK"
ANCHO=$(adb shell wm size | sed 's/.*: //' | cut -dx -f1 | tr -d '\r')
BARRA=$(adb shell dumpsys window | grep -m1 -oE 'statusBars[^]]*frame=\[0,0\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+\]$' | tr -d ']' || true)
BARRA=${BARRA:-0}
echo "versión=$VER ancho=$ANCHO barra_estado=$BARRA px"
fallos=0
tocar() { # toca el botón con ese texto en la pantalla principal
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/p.xml >/dev/null
  b=$(grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" /tmp/p.xml | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
for act in MainActivity AjustesActivity NasActivity; do
  adb shell am force-stop mx.jse.captura
  adb shell am start -W -n mx.jse.captura/.MainActivity >/dev/null; sleep 3
  [ "$act" = AjustesActivity ] && { tocar Ajustes; sleep 3; }
  [ "$act" = NasActivity ] && { tocar NAS; sleep 3; }
  adb exec-out screencap -p > "pantallas/$act.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml "pantallas/$act.xml" >/dev/null
  # Versión visible y en la mitad derecha
  linea=$(grep -oE "text=\"JSE Captura v$VER\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "pantallas/$act.xml" || true)
  if [ -z "$linea" ]; then echo "✗ $act: no se ve la versión v$VER"; fallos=1; else
    x1=$(echo "$linea" | grep -oE 'bounds="\[[0-9]+' | grep -oE '[0-9]+$')
    y1=$(echo "$linea" | grep -oE 'bounds="\[[0-9]+,[0-9]+' | grep -oE '[0-9]+$')
    x2=$(echo "$linea" | grep -oE '\]\[[0-9]+' | grep -oE '[0-9]+$')
    if [ "$x2" -lt $((ANCHO * 3 / 4)) ]; then echo "✗ $act: la versión no está a la derecha ($x1..$x2)"; fallos=1; fi
    if [ "$y1" -lt "$BARRA" ]; then echo "✗ $act: la versión queda bajo la barra de estado ($y1 < $BARRA)"; fallos=1; fi
    echo "✓ $act: v$VER en x=$x1..$x2 y=$y1"
  fi
  # Ningún elemento de la app (clickable o con texto) empieza dentro de la barra de estado
  dentro=$(grep -oE 'package="mx.jse.captura"[^>]*bounds="\[[0-9]+,[0-9]+\]' "pantallas/$act.xml" | grep -oE ',[0-9]+\]$' | tr -d ',]' | awk -v b="$BARRA" '$1>0 && $1<b' | wc -l)
  if [ "$dentro" -gt 0 ]; then echo "✗ $act: $dentro elementos bajo la barra de estado"; fallos=1; else echo "✓ $act: nada bajo la barra de estado"; fi
  # La app no se cerró
  if ! adb shell pidof mx.jse.captura >/dev/null; then echo "✗ $act: la app se cerró"; adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -40; fallos=1; fi
done
# Letra extra grande (como el teléfono de Remedios): la pantalla debe desplazarse y llegar a los filtros de la lista.
adb shell settings put system font_scale 2.0; sleep 2
adb shell am force-stop mx.jse.captura
adb shell am start -W -n mx.jse.captura/.MainActivity >/dev/null; sleep 3
adb exec-out screencap -p > pantallas/letra-grande-1.png
TAM=$(adb shell wm size | sed 's/.*: //' | tr -d '\r'); W=${TAM%x*}; H=${TAM#*x}
for i in 1 2 3; do adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/5)) 300; sleep 1; done
adb exec-out screencap -p > pantallas/letra-grande-2.png
adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml pantallas/letra-grande.xml >/dev/null
if grep -q 'text="Pendientes"' pantallas/letra-grande.xml; then echo "✓ letra grande: la pantalla se desplaza hasta la lista"; else echo "✗ letra grande: no se llega a la lista"; fallos=1; fi
adb shell settings put system font_scale 1.0
exit $fallos
