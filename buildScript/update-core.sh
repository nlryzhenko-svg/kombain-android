#!/usr/bin/env bash
# Kombain: обновление ядер приложения:
#   - sing-box (libcore.aar) с зависимостями libneko и wireguard-go (AmneziaWG);
#   - Xray-core (app/executableSo/*/libxray.so) — движок для VLESS/Reality.
#
#   bash buildScript/update-core.sh            обновить до свежих версий и собрать оба ядра
#   bash buildScript/update-core.sh --check    только показать, есть ли обновление (ничего не меняет)
#   bash buildScript/update-core.sh --branch 1.14.x
#                                              перейти на другую ветку sing-box (крупное обновление,
#                                              может потребовать правок в libcore)
#   bash buildScript/update-core.sh --no-build обновить версии, наложить патч, но не собирать
#
# Если патч AmneziaWG не накладывается или ядро не собирается, все изменения откатываются.
# Последняя строка вывода для CI: UPDATE=yes|no  CORE=<версия sing-box>
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
ENV_FILE=buildScript/lib/core/get_source_env.sh
export GOTOOLCHAIN=${GOTOOLCHAIN:-go1.25.0}
export PATH="$(go env GOPATH)/bin:$PATH"

CHECK=0 BUILD=1 BRANCH=""
while [ $# -gt 0 ]; do
  case $1 in
    --check) CHECK=1; shift ;;
    --no-build) BUILD=0; shift ;;
    --branch) BRANCH=$2; shift 2 ;;
    -h|--help) sed -n 2,14p "$0"; exit 0 ;;
    *) echo "неизвестный параметр: $1" >&2; exit 2 ;;
  esac
done

source "$ENV_FILE"
BRANCH=${BRANCH:-${BRANCH_SING_BOX:-1.12.x}}

remote_head() { # repo ref
  local h
  h=$(git ls-remote "https://github.com/$1.git" "$2" | awk 'NR==1{print $1}')
  [ -n "$h" ] || { echo "не нашёл $2 в $1" >&2; exit 1; }
  echo "$h"
}

new_sb=$(remote_head MatsuriDayo/sing-box "refs/heads/$BRANCH")
new_ln=$(remote_head MatsuriDayo/libneko HEAD)
new_wg=$(remote_head Leadaxe/wireguard-go HEAD)
XRAY_SH=buildScript/lib/xray.sh
old_xray=$(sed -n 's/^XRAY_VERSION="${XRAY_VERSION:-\(v[^}]*\)}"$/\1/p' "$XRAY_SH")
[ -n "$old_xray" ] || { echo "не нашёл XRAY_VERSION в $XRAY_SH" >&2; exit 1; }
# only stable releases: vX.Y.Z
new_xray=$(git ls-remote --tags --refs https://github.com/XTLS/Xray-core.git | awk -F/ '{print $3}' \
  | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)
[ -n "$new_xray" ] || { echo "не нашёл релизы Xray-core" >&2; exit 1; }

changed=0 sb_changed=0 xray_changed=0
show() { # name old new
  if [ "$2" = "$3" ]; then printf '  %-12s %s  (без изменений)\n' "$1" "${2:0:10}"
  else printf '  %-12s %s → %s\n' "$1" "${2:0:10}" "${3:0:10}"; changed=1; fi
}
echo "Ядра Kombain (sing-box из ветки $BRANCH):"
show sing-box "$COMMIT_SING_BOX" "$new_sb"
show libneko "$COMMIT_LIBNEKO" "$new_ln"
show wireguard "$COMMIT_WIREGUARD_AWG" "$new_wg"
[ "$changed" = 1 ] && sb_changed=1
show Xray-core "$old_xray" "$new_xray"
[ "$old_xray" != "$new_xray" ] && xray_changed=1

core_version() { # human version of the sing-box checkout, e.g. 1.12.19-neko-1
  git -C "$ROOT/../sing-box" log -1 --format=%s 2>/dev/null | grep -oE '^[0-9]+\.[0-9]+\.[0-9]+[^ ]*' || echo "${COMMIT_SING_BOX:0:10}"
}

if [ "$changed" = 0 ]; then
  echo "Обновлений нет."
  echo "UPDATE=no CORE=$(core_version) XRAY=$old_xray"
  exit 0
fi
if [ "$CHECK" = 1 ]; then
  echo "UPDATE=yes CORE=? XRAY=$new_xray"
  exit 0
fi

# keep the old state to roll back
backup=$(mktemp -d)
cp "$ENV_FILE" "$XRAY_SH" libcore/go.mod libcore/go.sum "$backup/"
rollback() {
  echo "!! $1 — откатываю изменения" >&2
  cp "$backup/get_source_env.sh" "$ENV_FILE"
  cp "$backup/xray.sh" "$XRAY_SH"
  cp "$backup/go.mod" "$backup/go.sum" libcore/
  source "$ENV_FILE"
  bash buildScript/lib/core/get_source.sh >/dev/null 2>&1 || true
  exit 1
}

if [ "$sb_changed" = 1 ]; then
cat > "$ENV_FILE" <<EOF
export BRANCH_SING_BOX="$BRANCH"
export COMMIT_SING_BOX="$new_sb"
export COMMIT_LIBNEKO="$new_ln"
export COMMIT_WIREGUARD_AWG="$new_wg"
EOF

echo ">> исходники и патч AmneziaWG"
(cd ../sing-box 2>/dev/null && git fetch -q origin "$BRANCH") || true
(cd ../libneko 2>/dev/null && git fetch -q origin) || true
bash buildScript/lib/core/get_source.sh || rollback "патч AmneziaWG не накладывается на новую версию sing-box"

echo ">> зависимости Go"
(cd libcore && go mod tidy) || rollback "go mod tidy не прошёл"

if [ "$BUILD" = 1 ]; then
  echo ">> сборка libcore.aar (несколько минут)"
  (cd libcore && ./init.sh) >/dev/null || rollback "не установился gomobile"
  bash buildScript/lib/core/build.sh || rollback "новое ядро sing-box не собирается"
fi
fi

if [ "$xray_changed" = 1 ]; then
  sed -i "s/^XRAY_VERSION=.*/XRAY_VERSION=\"\${XRAY_VERSION:-$new_xray}\"/" "$XRAY_SH"
  if [ "$BUILD" = 1 ]; then
    echo ">> сборка Xray-core $new_xray"
    bash "$XRAY_SH" || rollback "Xray-core $new_xray не собирается"
  fi
fi

ver=$(core_version)
echo "Готово: sing-box $ver, Xray-core $new_xray"
echo "UPDATE=yes CORE=$ver XRAY=$new_xray"
