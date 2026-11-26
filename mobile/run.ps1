# Build and run HaulMate on the USB-connected Android phone.
#   .\run.ps1          normal run (fast after the first build)
#   .\run.ps1 -Fresh   after changing pubspec.yaml
# The phone reaches the PC server through the USB cable (adb reverse), so the PC's Wi-Fi IP never matters.
param([switch]$Fresh)
$ErrorActionPreference = "Stop"
if (-not (Get-Command flutter -ErrorAction SilentlyContinue)) { $env:Path += ";C:\src\flutter\bin" }
Set-Location $PSScriptRoot

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb reverse tcp:8670 tcp:8670 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "Phone not found on USB. Unlock it and allow USB debugging." -ForegroundColor Red; exit 1 }
Write-Host "Phone -> PC server via USB: http://localhost:8670" -ForegroundColor Cyan

if ($Fresh) {
  flutter pub get
  flutter analyze --no-fatal-infos
  if ($LASTEXITCODE -ne 0) { Write-Host "Fix the analyzer errors above first." -ForegroundColor Red; exit 1 }
}
flutter run --dart-define=API_BASE=http://localhost:8670
