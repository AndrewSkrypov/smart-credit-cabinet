#!/usr/bin/env bash
# Signed APK for the site and AAB for RuStore. The keystore stays outside the repository:
#   CABINET_ANDROID_SIGNING=~/.android-keys/pump-cabinet-release.properties bash mobile/scripts/build-android-release.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${CABINET_ANDROID_SIGNING:?Укажите файл с параметрами ключа подписи}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
npm ci --no-audit --no-fund
npx cap sync android
(cd android && ./gradlew assembleRelease bundleRelease --no-daemon)
cp android/app/build/outputs/apk/release/app-release.apk ../frontend/public/downloads/kabinet.apk
echo "APK: frontend/public/downloads/kabinet.apk"
echo "AAB для RuStore: mobile/android/app/build/outputs/bundle/release/app-release.aab"
