<#
.SYNOPSIS
  Holt das letzte gruene CI-Build und bringt es auf die Uhr.

.DESCRIPTION
  Ein Durchlauf: Uhr suchen und verbinden, APKs aus dem letzten erfolgreichen
  CI-Run laden, installieren, Kalenderrecht erteilen, App starten und die
  eigenen Logzeilen zeigen.

  Warum deinstalliert wird statt zu aktualisieren: GitHub-Runner erzeugen bei
  jedem Lauf einen frischen Debug-Keystore. Jedes CI-Build ist damit anders
  signiert und laesst sich nicht ueber das vorige installieren
  (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Wer das loswerden will, legt einen
  festen Keystore als GitHub-Secret ab — siehe docs/SETUP.md.

.EXAMPLE
  pwsh tools/deploy.ps1
  pwsh tools/deploy.ps1 -SkipDownload      # lokal vorhandene APKs nehmen
  pwsh tools/deploy.ps1 -Shot              # zusaetzlich Screenshot ziehen
#>
param(
  [switch]$SkipDownload,
  [switch]$Shot,
  [string]$Adb = "C:\Android\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Find-Watch {
  $env:ADB_MDNS_OPENSCREEN = "1"
  & $Adb start-server 2>&1 | Out-Null
  Start-Sleep -Seconds 2

  # Bereits verbundene TCP-Instanz bevorzugen
  $line = (& $Adb devices) | Where-Object { $_ -match '^(\d+\.\d+\.\d+\.\d+:\d+)\s+device' }
  if ($line) { return ($line -split '\s+')[0] }

  $svc = (& $Adb mdns services 2>&1) -join "`n"
  $m = [regex]::Match($svc, '_adb-tls-connect\._tcp\s+(\d+\.\d+\.\d+\.\d+:\d+)')
  if (-not $m.Success) {
    throw "Uhr nicht gefunden. Wireless Debugging auf der Uhr pruefen (Port aendert sich beim Aufwachen)."
  }
  & $Adb connect $m.Groups[1].Value 2>&1 | Out-Null
  Start-Sleep -Seconds 2
  return $m.Groups[1].Value
}

$dev = Find-Watch
Write-Host "Uhr: $dev" -ForegroundColor Green

if (-not $SkipDownload) {
  # gh selbst filtern lassen. Windows PowerShell 5.1 enumeriert das Array aus
  # ConvertFrom-Json nicht, ein Where-Object darauf liefert die ganze Liste
  # zurueck — und gh run download bekaeme dann saemtliche Run-IDs auf einmal.
  $runId = @((gh run list --status success --limit 1 --json databaseId | ConvertFrom-Json).databaseId)[0]
  if (-not $runId) { throw "Kein gruener CI-Run gefunden." }
  Write-Host "CI-Run $runId" -ForegroundColor Green
  Remove-Item "build\ci-apks" -Recurse -Force -ErrorAction SilentlyContinue
  gh run download $runId -n apks -D build\ci-apks | Out-Null
}

$apks = @(
  @{ pkg = "de.agendadial.wear";      file = "wear-release.apk" }
  @{ pkg = "de.agendadial.watchface"; file = "watchface-release.apk" }
)

foreach ($a in $apks) {
  $path = (Get-ChildItem "build\ci-apks" -Recurse -Filter $a.file | Select-Object -First 1).FullName
  if (-not $path) { throw "$($a.file) nicht gefunden." }
  & $Adb -s $dev uninstall $a.pkg 2>&1 | Out-Null
  $res = (& $Adb -s $dev install $path 2>&1) | Select-Object -Last 1
  Write-Host ("{0,-28} {1}" -f $a.file, $res)
}

& $Adb -s $dev shell "pm grant de.agendadial.wear android.permission.READ_CALENDAR" 2>&1 | Out-Null

# Auto-Rotation aus. Ein rundes Zifferblatt hat keine Querformat-Variante.
& $Adb -s $dev shell "settings put system accelerometer_rotation 0" 2>&1 | Out-Null

& $Adb -s $dev logcat -c 2>&1 | Out-Null
& $Adb -s $dev shell "am start -n de.agendadial.wear/.OrganizerActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 5

Write-Host "`n--- Logzeilen von AgendaDial ---" -ForegroundColor Cyan
(& $Adb -s $dev logcat -d 2>&1) | Select-String -Pattern 'AgendaDial' |
  ForEach-Object { ($_.Line -replace '^.*AgendaDial', 'AgendaDial') }

if ($Shot) {
  & $Adb -s $dev shell "screencap -p /sdcard/ad.png" 2>&1 | Out-Null
  & $Adb -s $dev pull /sdcard/ad.png "build\watch.png" 2>&1 | Out-Null
  & $Adb -s $dev shell "rm /sdcard/ad.png" 2>&1 | Out-Null
  Write-Host "`nScreenshot: build\watch.png" -ForegroundColor Cyan
}
