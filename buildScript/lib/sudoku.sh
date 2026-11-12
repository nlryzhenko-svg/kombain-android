#!/usr/bin/env bash
# Kombain: build the Sudoku client (github.com/SUDOKU-ASCII/sudoku) as an Android executable
# app/executableSo/<abi>/libsudoku.so — runs like libxray.so: a local SOCKS/HTTP proxy for sing-box.
set -e
source "buildScript/init/env.sh"
SUDOKU_VERSION="${SUDOKU_VERSION:-v0.5.2-1}"
SRC="$SRC_ROOT/../sudoku"
if [ -d "$SRC/.git" ]; then
  git -C "$SRC" fetch -q --depth 1 origin "refs/tags/$SUDOKU_VERSION:refs/tags/$SUDOKU_VERSION" 2>/dev/null || true
  git -C "$SRC" checkout -q -f "$SUDOKU_VERSION"
else
  git clone --depth 1 -b "$SUDOKU_VERSION" https://github.com/SUDOKU-ASCII/sudoku.git "$SRC"
fi
# In "global" mode the client quietly downloads an ad-block list from jsdelivr and rejects those
# domains. sing-box already does the routing in Kombain, so the client must pass everything through.
sed -i 's/^\t\treturn append(reject, DefaultImplicitRejectRuleURL)$/\t\treturn reject \/\/ Kombain: no implicit ad-block list/' \
  "$SRC/internal/config/rules.go"
grep -q 'Kombain: no implicit ad-block list' "$SRC/internal/config/rules.go" || { echo "rules.go patch failed"; exit 1; }
BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
cd "$SRC"
for spec in "arm64:aarch64-linux-android21-clang:arm64-v8a" "amd64:x86_64-linux-android21-clang:x86_64" "arm:armv7a-linux-androideabi21-clang:armeabi-v7a"; do
  IFS=: read -r goarch cc abi <<<"$spec"
  mkdir -p "$SRC_ROOT/app/executableSo/$abi"
  GOTOOLCHAIN=auto GOOS=android GOARCH=$goarch GOARM=7 CGO_ENABLED=1 CC="$BIN/$cc" \
    go build -o "$SRC_ROOT/app/executableSo/$abi/libsudoku.so" -trimpath -buildvcs=false \
    -ldflags="-s -w -buildid=" ./cmd/sudoku-tunnel
done
git checkout -q -- internal/config/rules.go
