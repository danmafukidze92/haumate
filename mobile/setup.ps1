# One-time setup on Windows: generates the android/ and ios/ folders around the code in lib/
# and applies HaulMate's permissions.  Run from C:\docker\Ubuntushift\haulmate\mobile :
#   powershell -ExecutionPolicy Bypass -File .\setup.ps1
$ErrorActionPreference = "Stop"
flutter create --org zw.haulmate --project-name haulmate_app --platforms android,ios .

Copy-Item platform_overrides\AndroidManifest.xml android\app\src\main\AndroidManifest.xml -Force
Copy-Item platform_overrides\Info.plist ios\Runner\Info.plist -Force

# ML Kit and WebRTC need Android 6.0+ (API 23)
foreach ($g in @("android\app\build.gradle.kts", "android\app\build.gradle")) {
  if (Test-Path $g) {
    (Get-Content $g) -replace 'minSdk\s*=\s*flutter\.minSdkVersion', 'minSdk = 23' `
                     -replace 'minSdkVersion\s+flutter\.minSdkVersion', 'minSdkVersion 23' | Set-Content $g
  }
}
flutter pub get
Write-Host ""
Write-Host "Done. Next:" -ForegroundColor Green
Write-Host "  1. dart pub global activate flutterfire_cli ; flutterfire configure   (adds Firebase push; optional for a first test)"
Write-Host "  2. flutter run --dart-define=API_BASE=http://<your PC's Wi-Fi IP>:8670   (phone on the same Wi-Fi)"
Write-Host "  3. flutter build apk --release   (uses https://cherai.nexigent-labs.co.zw by default)"
