#!/usr/bin/env bash
# Kombain: build Xray-core as an Android executable (app/executableSo/<abi>/libxray.so)
set -e
source "buildScript/init/env.sh"
XRAY_VERSION="${XRAY_VERSION:-v26.11.9}"
SRC="$SRC_ROOT/../xray-core"
[ -d "$SRC" ] || git clone --depth 1 -b "$XRAY_VERSION" https://github.com/XTLS/Xray-core.git "$SRC"
BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
cd "$SRC"
for spec in "arm64:aarch64-linux-android21-clang:arm64-v8a" "amd64:x86_64-linux-android21-clang:x86_64" "arm:armv7a-linux-androideabi21-clang:armeabi-v7a"; do
  IFS=: read -r goarch cc abi <<<"$spec"
  mkdir -p "$SRC_ROOT/app/executableSo/$abi"
  GOTOOLCHAIN=auto GOOS=android GOARCH=$goarch GOARM=7 CGO_ENABLED=1 CC="$BIN/$cc" \
    go build -o "$SRC_ROOT/app/executableSo/$abi/libxray.so" -trimpath -buildvcs=false \
    -ldflags="-s -w -buildid= -checklinkname=0" ./main
done
