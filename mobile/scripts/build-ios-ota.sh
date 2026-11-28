#!/usr/bin/env bash
# Over-the-air iPhone install: builds an ad-hoc IPA and the manifest iOS reads via itms-services.
# Needs a paid Apple Developer team (a free Personal Team cannot export a distributable IPA)
# and the target iPhones registered in that team.
#   DEVELOPMENT_TEAM=<team id> PUBLIC_URL=https://credit-cabinet.ru bash mobile/scripts/build-ios-ota.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DEVELOPMENT_TEAM:?Укажите DEVELOPMENT_TEAM платной команды Apple Developer}"
PUBLIC_URL="${PUBLIC_URL:-https://credit-cabinet.ru}"
VERSION="$(node -p "require('./package.json').version || '1.0.0'")"
OUT="../frontend/public/downloads/ios"
WORK="$(mktemp -d)"

npm ci --no-audit --no-fund
npx cap sync ios
xcodebuild -project ios/App/App.xcodeproj -scheme App -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "$WORK/App.xcarchive" -allowProvisioningUpdates DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" CODE_SIGN_STYLE=Automatic archive -quiet
cat > "$WORK/export.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>release-testing</string>
  <key>teamID</key><string>${DEVELOPMENT_TEAM}</string>
  <key>signingStyle</key><string>automatic</string>
  <key>manifest</key><dict>
    <key>appURL</key><string>${PUBLIC_URL}/downloads/ios/kabinet.ipa</string>
    <key>displayImageURL</key><string>${PUBLIC_URL}/icons/icon-192.png</string>
    <key>fullSizeImageURL</key><string>${PUBLIC_URL}/icons/icon-512.png</string>
  </dict>
</dict></plist>
PLIST
xcodebuild -exportArchive -archivePath "$WORK/App.xcarchive" -exportOptionsPlist "$WORK/export.plist" \
  -exportPath "$WORK/export" -allowProvisioningUpdates -quiet
mkdir -p "$OUT"
cp "$WORK/export/App.ipa" "$OUT/kabinet.ipa"
cp "$WORK/export/manifest.plist" "$OUT/manifest.plist"
python3 - "$PUBLIC_URL" "$VERSION" <<'PY'
import json, sys
path = "../frontend/public/downloads/apps.json"
data = json.load(open(path, encoding="utf-8"))
data["ios"] = {"otaManifestUrl": sys.argv[1] + "/downloads/ios/manifest.plist", "version": sys.argv[2]}
json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
echo "Готово: ${PUBLIC_URL}/app покажет кнопку «Установить на iPhone» после выкладки фронтенда."
