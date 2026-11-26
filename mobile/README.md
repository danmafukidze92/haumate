# HaulMate mobile app (Flutter, Android + iOS)

One app for shippers, drivers and fleet owners; the role is chosen at sign-up.

## First-time setup
Windows (Android): `powershell -ExecutionPolicy Bypass -File .\setup.ps1`
Mac (iOS):         `./setup.sh`

This runs `flutter create` to generate the `android/` and `ios/` folders, then applies the
permissions (GPS incl. background, camera, microphone, notifications) and minimum OS versions.

## Run against your PC
Start the API in IntelliJ (port 8670), then with the phone on the same Wi-Fi:
```
flutter run --dart-define=API_BASE=http://192.168.x.x:8670
```
Android emulator: `--dart-define=API_BASE=http://10.0.2.2:8670`. Allow port 8670 in Windows Firewall.

## Release builds (point at https://cherai.nexigent-labs.co.zw by default)
```
flutter build apk --release          # direct download from the website
flutter build appbundle --release    # Google Play
flutter build ipa --release          # App Store (on the Mac)
```
Upload the APK in Admin › Settings › Mobile apps (or place it at `data/apps/haulmate.apk`) so the
download page at https://cherai.nexigent-labs.co.zw serves it.

## Push notifications
`dart pub global activate flutterfire_cli` then `flutterfire configure` (pick the same Firebase project
whose service-account JSON the worker uses). Without it the app still works; alerts arrive in-app.

## Features
- Sign-up / login, role selection
- KYC: ID or passport, driver's licence, truck + registration book, live selfie with ML Kit liveness (blink, head turns)
- Shipper: map of nearby trucks, address search (OpenStreetMap), recommended price, own offer, offers list,
  accept driver, live truck tracking with ETA, delivery PIN, Paynow (EcoCash/OneMoney/InnBucks/card), rating
- Driver: online/offline, background GPS with offline buffering, nearby loads with radius, accept or counter-offer,
  trip steps with pickup/delivery photos, PIN-confirmed delivery, Google Maps navigation, wallet
- Both: real-time chat, in-app voice calls (WebRTC, numbers stay private), push + in-app alerts
