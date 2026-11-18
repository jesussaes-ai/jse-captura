#!/usr/bin/env bash
# Regla de Jesús: Android, Windows e iPhone llevan SIEMPRE el mismo número de versión.
set -euo pipefail
cd "$(dirname "$0")/../.."   # raíz del repo, sin importar desde dónde se llame
A=$(grep -oE 'versionName = "[^"]+"' android/app/build.gradle.kts | cut -d'"' -f2)
W=$(grep -m1 -oE '"version": "[^"]+"' escritorio/package.json | cut -d'"' -f4)
V=$(tr -d " \r\n" < VERSION)
I=$(grep -oE 'MARKETING_VERSION: "[^"]+"' ios/project.yml | cut -d'"' -f2)
echo "VERSION $V · Android $A · Windows $W · iPhone $I"
if [ "$A" != "$V" ] || [ "$W" != "$V" ] || [ "$I" != "$V" ]; then echo "✗ Las versiones no coinciden: súbelas todas al mismo número."; exit 1; fi
echo "✓ Misma versión en todas las plataformas: $A"
