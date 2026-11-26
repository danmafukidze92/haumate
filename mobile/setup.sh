#!/usr/bin/env bash
# Same as setup.ps1, for the Mac (iOS builds). Run inside mobile/.
set -e
flutter create --org zw.haulmate --project-name haulmate_app --platforms android,ios .
cp platform_overrides/AndroidManifest.xml android/app/src/main/AndroidManifest.xml
cp platform_overrides/Info.plist ios/Runner/Info.plist
for g in android/app/build.gradle.kts android/app/build.gradle; do
  [ -f "$g" ] && sed -i '' -E 's/minSdk *= *flutter\.minSdkVersion/minSdk = 23/; s/minSdkVersion +flutter\.minSdkVersion/minSdkVersion 23/' "$g"
done
flutter pub get
# Google ML Kit needs iOS 15.5+
cd ios && (grep -q "^platform :ios" Podfile 2>/dev/null || flutter build ios --config-only) && \
  sed -i '' -E "s/^#? *platform :ios.*/platform :ios, '15.5'/" Podfile && pod install && cd ..
echo "Done. In Xcode (ios/Runner.xcworkspace) set your Team, then enable Push Notifications + Background Modes capabilities."
