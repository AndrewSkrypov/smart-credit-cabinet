#!/usr/bin/env bash
# Builds the iOS shell and installs it on a connected iPhone with a free Apple ID (Personal Team).
#   bash mobile/scripts/install-ios.sh                 # first connected iPhone, stand https://credit-cabinet.ru
#   IPHONE=<UDID> CABINET_APP_URL=http://192.168.1.10:8080 bash mobile/scripts/install-ios.sh
set -euo pipefail
cd "$(dirname "$0")/.."

TEAM="${DEVELOPMENT_TEAM:-$(defaults read com.apple.dt.Xcode IDEProvisioningTeamByIdentifier 2>/dev/null | awk -F'= ' '/teamID/{gsub(/[ ;"]/,"",$2); print $2; exit}')}"
[ -n "$TEAM" ] || { echo "Войдите в Xcode → Settings → Accounts или задайте DEVELOPMENT_TEAM" >&2; exit 1; }
DEVICE="${IPHONE:-$(xcrun devicectl list devices 2>/dev/null | awk '/available \(paired\)/ && /iPhone/ {for (i=1;i<=NF;i++) if ($i ~ /^[0-9A-F]{8}-[0-9A-F]{16}$/) {print $i; exit}}')}"
[ -n "$DEVICE" ] || { echo "Подключите разблокированный iPhone с включённым режимом разработчика" >&2; exit 1; }

npm ci --no-audit --no-fund
npx cap sync ios
BUILD="$(mktemp -d)"
xcodebuild -project ios/App/App.xcodeproj -scheme App -configuration Debug -destination "id=$DEVICE" \
  -derivedDataPath "$BUILD" -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic build -quiet
xcrun devicectl device install app --device "$DEVICE" "$BUILD/Build/Products/Debug-iphoneos/App.app"
xcrun devicectl device process launch --device "$DEVICE" ru.creditcabinet.pump || \
  echo "Установлено. На телефоне: Настройки → Основные → VPN и управление устройством → доверять разработчику"
