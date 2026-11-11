#!/usr/bin/env bash
# Kombain: build the signed APK in GitHub Actions.
# Needs app/libs/libcore.aar and the repository secrets
#   KEYSTORE_BASE64 (release.keystore in base64), KEYSTORE_PASS, ALIAS_NAME, ALIAS_PASS.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${KEYSTORE_BASE64:-}" ]; then
  echo "::error::Не задан секрет KEYSTORE_BASE64 — без ключа подписи APK не встанет поверх уже установленного Kombain"
  exit 1
fi
[ -f app/libs/libcore.aar ] || { echo "::error::нет app/libs/libcore.aar (ядро sing-box)"; exit 1; }
[ -f app/executableSo/arm64-v8a/libxray.so ] || { echo "::error::нет libxray.so (Xray-core)"; exit 1; }
echo "$KEYSTORE_BASE64" | base64 -d > release.keystore
{
  echo "sdk.dir=$ANDROID_HOME"
  echo "ndk.dir=$ANDROID_HOME/ndk/25.0.8775105"
  echo "KEYSTORE_PASS=$KEYSTORE_PASS"
  echo "ALIAS_NAME=$ALIAS_NAME"
  echo "ALIAS_PASS=$ALIAS_PASS"
} > local.properties

bash buildScript/lib/assets.sh # geoip / geosite
./gradlew --no-daemon app:assembleOssRelease

ver=$(grep '^VERSION_NAME=' nb4a.properties | cut -d= -f2)
mkdir -p out
for apk in $(find app/build/outputs/apk -name '*.apk'); do
  abi=$(basename "$apk" .apk | grep -oE 'arm64-v8a|armeabi-v7a|x86_64|x86|universal' || echo all)
  cp "$apk" "out/Kombain-$ver-$abi.apk"
done
[ -n "${GITHUB_ACTIONS:-}" ] && rm -f release.keystore local.properties
ls -l out
echo "VERSION=$ver" >> "${GITHUB_ENV:-/dev/null}"
