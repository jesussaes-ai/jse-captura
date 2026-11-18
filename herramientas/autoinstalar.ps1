<#
  JSE Captura · auto-instalador (corre en la PC de Jesús como tarea programada cada 15 minutos).

  Qué hace, sin preguntar nada:
   1. Busca en GitHub la última compilación de JSE Captura que pasó TODAS las pruebas
      (inspector + emulador para Android; pruebas + instalador para Windows).
   2. Si es más nueva que la instalada, la instala ENCIMA (sin desinstalar ni borrar datos):
      - Android: `adb install -r` en cada teléfono de equipos.txt que responda por Tailscale.
      - Windows: el instalador en silencio en esta PC.
   3. Los equipos apagados o fuera de alcance se reintentan solos en la siguiente vuelta.
   4. Deja todo en registro.txt.

  Archivos (en %USERPROFILE%\JSE\auto-instalador):
   - equipos.txt  : un teléfono por renglón:  Nombre=IP:5555   (ej. "S23 de Jesus=100.64.1.2:5555")
                    o Nombre=serie-mDNS si el teléfono usa depuración inalámbrica sin puerto fijo
   - registro.txt : historial
  Nunca desinstala, nunca borra datos y no toca nada fuera de JSE Captura.

  Uso:
   powershell -ExecutionPolicy Bypass -File autoinstalar.ps1               # una vuelta
   powershell -ExecutionPolicy Bypass -File autoinstalar.ps1 -Programar    # crea la tarea cada 15 min
   powershell -ExecutionPolicy Bypass -File autoinstalar.ps1 -SoloWindows  # PCs sin teléfonos (hpjsetrabajo)
#>
param([switch]$Programar, [switch]$SoloWindows)
$ErrorActionPreference = 'Continue'   # adb y gh escriben avisos en stderr; los errores se revisan uno por uno
$Repo = 'jesussaes-ai/jse-captura'
$Base = Join-Path $env:USERPROFILE 'JSE\auto-instalador'
New-Item -ItemType Directory -Force -Path $Base | Out-Null
$Registro = Join-Path $Base 'registro.txt'
function Log($t) { $l = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $t"; Write-Host $l; Add-Content -Path $Registro -Value $l -Encoding UTF8 }

if ($Programar) {
  $arg = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`"" + $(if ($SoloWindows) { ' -SoloWindows' } else { '' })
  $acc = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg
  $disp = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 15)
  $conf = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
  Register-ScheduledTask -TaskName 'JSE Captura auto-instalador' -Action $acc -Trigger $disp -Settings $conf -Description 'Instala sola cada versión nueva de JSE Captura que pasó las pruebas' -Force | Out-Null
  Log "Tarea programada creada/actualizada (cada 15 min$(if ($SoloWindows) {', solo Windows'}))."
  return
}

function gh-json($argumentos) { $o = & gh @argumentos 2>$null; if ($LASTEXITCODE -ne 0 -or -not $o) { return $null }; $o | ConvertFrom-Json }
function Ultima($workflow) {
  $r = gh-json @('run', 'list', '-R', $Repo, '--workflow', $workflow, '--branch', 'main', '--status', 'success', '-L', '1', '--json', 'databaseId,headSha')
  if ($r) { $r[0] } else { $null }
}
function Bajar($run, $artefacto) {
  $d = Join-Path $Base "descargas\$($run.databaseId)\$artefacto"
  if (-not (Test-Path $d)) {
    & gh run download $run.databaseId -R $Repo -n $artefacto -D $d 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { Remove-Item -Recurse -Force $d -ErrorAction SilentlyContinue; throw "no se pudo descargar $artefacto" }
  }
  $d
}
function VersionDe($nombre) { if ($nombre -match '(\d+\.\d+\.\d+)') { [version]$Matches[1] } else { $null } }

try { & gh auth status 2>$null | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'gh sin sesión' } } catch { Log "✗ GitHub CLI (gh) no tiene sesión iniciada: corre «gh auth login»."; exit 1 }

# ---------------- Windows (esta PC) ----------------
try {
  $run = Ultima 'windows.yml'
  if ($run) {
    $dir = Bajar $run 'JSE-Captura-Windows'
    $inst = Get-ChildItem $dir -Recurse -Filter 'JSE-Captura-Instalador-*.exe' | Select-Object -First 1
    $nueva = VersionDe $inst.Name
    $exe = @('Programs\jse-captura\JSE Captura.exe', 'Programs\JSE Captura\JSE Captura.exe') | ForEach-Object { Join-Path $env:LOCALAPPDATA $_ } |
      Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $exe) { $exe = Join-Path $env:LOCALAPPDATA 'Programs\jse-captura\JSE Captura.exe' }
    $actual = if (Test-Path $exe) { VersionDe (Get-Item $exe).VersionInfo.ProductVersion } else { $null }
    if (-not $actual -or $nueva -gt $actual) {
      Log "Windows: instalando $nueva (había $(if ($actual) { $actual } else { 'nada' }))…"
      Get-Process 'JSE Captura' -ErrorAction SilentlyContinue | ForEach-Object { if (-not $_.CloseMainWindow()) { } }
      Start-Sleep 3
      $p = Start-Process -FilePath $inst.FullName -ArgumentList '/S' -Wait -PassThru
      $exe = @('Programs\jse-captura\JSE Captura.exe', 'Programs\JSE Captura\JSE Captura.exe') | ForEach-Object { Join-Path $env:LOCALAPPDATA $_ } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
      $ahora = if ($exe) { VersionDe (Get-Item $exe).VersionInfo.ProductVersion } else { $null }
      if ($ahora -eq $nueva) { Log "✓ Windows: JSE Captura $nueva instalada." } else { Log "✗ Windows: el instalador terminó (código $($p.ExitCode)) pero la versión es $ahora." }
    }
  }
} catch { Log "✗ Windows: $($_.Exception.Message)" }

if ($SoloWindows) { exit 0 }

# ---------------- Android (teléfonos por Tailscale) ----------------
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$lista = Join-Path $Base 'equipos.txt'
if (-not (Test-Path $lista)) { Log "• Falta $lista (Nombre=IP:5555 por renglón); no se revisan teléfonos."; exit 0 }
try {
  $run = Ultima 'android.yml'
  if (-not $run) { exit 0 }
  $dir = Bajar $run 'JSE-Captura-Android'
  $apk = Get-ChildItem $dir -Recurse -Filter 'JSE-Captura-Android-*.apk' | Select-Object -First 1
  $nueva = VersionDe $apk.Name
  foreach ($renglon in Get-Content $lista -Encoding UTF8) {
    if ($renglon -notmatch '^\s*([^=#]+?)\s*=\s*(\S+)\s*$') { continue }
    $nombre = $Matches[1]; $dir_ = $Matches[2]
    try {  # un teléfono que falla no detiene a los demás
    # IP:puerto -> se conecta con tiempo límite (un teléfono que no contesta no cuelga la vuelta).
    # Otro texto (p. ej. «adb-R5CW…._adb-tls-connect._tcp») -> serie mDNS que adb ya ve sola.
    if ($dir_ -match '^[\d\.]+:\d+$') {
      $j = Start-Job { param($a, $d) & $a connect $d 2>&1 } -ArgumentList $adb, $dir_
      if (-not (Wait-Job $j -Timeout 15)) { Stop-Job $j }
      Remove-Job $j -Force
    }
    $j = Start-Job { param($a, $d) & $a -s $d get-state 2>$null } -ArgumentList $adb, $dir_
    $estado = if (Wait-Job $j -Timeout 10) { (Receive-Job $j) -join '' } else { Stop-Job $j; '' }
    Remove-Job $j -Force
    $estado = "$estado".Trim()
    if ($estado -ne 'device') { continue }   # apagado, bloqueado o fuera de Tailscale: se reintenta en la siguiente vuelta
    $v = (& $adb -s $dir_ shell dumpsys package mx.jse.captura 2>$null | Select-String 'versionName=' | Select-Object -First 1)
    $actual = if ($v) { VersionDe "$v" } else { $null }
    if ($actual -and $actual -ge $nueva) { continue }
    Log "${nombre}: instalando $nueva (había $(if ($actual) { $actual } else { 'nada' }))…"
    # --user 0: el perfil principal (algunos teléfonos tienen perfiles extra, como la Carpeta segura).
    $out = (& $adb -s $dir_ install -r --user 0 $apk.FullName 2>&1) -join ' '
    if ($out -match 'Success') { Log "✓ ${nombre}: JSE Captura $nueva instalada." } else { Log "✗ ${nombre}: $out" }
    } catch { Log "✗ ${nombre}: $($_.Exception.Message)" }
  }
} catch { Log "✗ Android: $($_.Exception.Message)" }
