#!/usr/bin/env bash
# Build APKs in GitHub Actions.
# Signed builds require KEYSTORE_BASE64, KEYSTORE_PASS, ALIAS_NAME and ALIAS_PASS.
# Unsigned builds are allowed only when ALLOW_UNSIGNED_APK=1 (CI/test artifacts).
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077

had_local_properties=0
local_properties_created=0
cleanup() {
  rm -f release.keystore
  if [[ "$had_local_properties" == "1" ]]; then
    mv .local.properties.ci-backup local.properties
  elif [[ "$local_properties_created" == "1" ]]; then
    rm -f local.properties
  fi
  rm -f .local.properties.ci-backup
}
trap cleanup EXIT

if [[ -f local.properties ]]; then
  cp local.properties .local.properties.ci-backup
  had_local_properties=1
fi

signed=0
if [[ -n "${KEYSTORE_BASE64:-}" ]]; then
  for name in KEYSTORE_PASS ALIAS_NAME ALIAS_PASS; do
    if [[ -z "${!name:-}" ]]; then
      echo "::error::Не задан секрет $name для подписи APK"
      exit 1
    fi
  done
  if ! printf '%s' "$KEYSTORE_BASE64" | base64 --decode > release.keystore || [[ ! -s release.keystore ]]; then
    echo "::error::KEYSTORE_BASE64 не содержит корректный keystore"
    rm -f release.keystore
    exit 1
  fi
  signed=1
elif [[ "${ALLOW_UNSIGNED_APK:-0}" == "1" || "${ALLOW_UNSIGNED_APK:-0}" == "true" ]]; then
  echo "::warning::KEYSTORE_BASE64 не задан. Собираю НЕПОДПИСАННЫЕ APK только для тестирования; установить поверх подписанной версии их нельзя."
else
  echo "::error::Нет KEYSTORE_BASE64. Для релизной сборки необходим ключ подписи. Для тестовой сборки задайте ALLOW_UNSIGNED_APK=1."
  exit 1
fi

[ -f app/libs/libcore.aar ] || { echo "::error::нет app/libs/libcore.aar (ядро sing-box)"; exit 1; }
[ -f app/executableSo/arm64-v8a/libxray.so ] || { echo "::error::нет libxray.so (Xray-core)"; exit 1; }
[ -f app/executableSo/arm64-v8a/libsudoku.so ] || { echo "::error::нет libsudoku.so (Sudoku)"; exit 1; }

local_properties_created=1
{
  echo "sdk.dir=$ANDROID_HOME"
  echo "ndk.dir=$ANDROID_HOME/ndk/25.0.8775105"
  if [[ "$signed" == "1" ]]; then
    echo "KEYSTORE_PASS=$KEYSTORE_PASS"
    echo "ALIAS_NAME=$ALIAS_NAME"
    echo "ALIAS_PASS=$ALIAS_PASS"
  fi
} > local.properties

bash buildScript/lib/assets.sh # geoip / geosite
./gradlew --no-daemon app:assembleOssRelease

ver=$(grep '^VERSION_NAME=' nb4a.properties | cut -d= -f2)
mkdir -p out
copied=0
while IFS= read -r -d '' apk; do
  abi=$(basename "$apk" .apk | grep -oE 'arm64-v8a|armeabi-v7a|x86_64|x86|universal' || echo all)
  suffix=""
  if [[ "$signed" == "0" ]]; then suffix="-unsigned"; fi
  cp "$apk" "out/DAK-$ver$suffix-$abi.apk"
  copied=$((copied + 1))
done < <(find app/build/outputs/apk -name '*.apk' -print0)

if [[ "$copied" -eq 0 ]]; then
  echo "::error::Gradle не создал APK"
  exit 1
fi

if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
  echo "VERSION=$ver" >> "$GITHUB_ENV"
  echo "APK_SIGNED=$signed" >> "$GITHUB_ENV"
fi
ls -l out
if [[ "$signed" == "0" ]]; then
  echo "::notice::Готовы тестовые APK без подписи. Для обновления установленного приложения требуется подписанный APK с исходным ключом."
fi
