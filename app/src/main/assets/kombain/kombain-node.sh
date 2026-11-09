#!/usr/bin/env bash
# Kombain node installer — VLESS+Reality (Xray) and Hysteria2 on Debian/Ubuntu.
#
#   kombain-node.sh install  --host IP [--role single|entry] [--protocols vless,hy2]
#                            [--sni www.microsoft.com] [--port 443] [--hy-port 443]
#                            [--name "Мой сервер"] [--user "Телефон"] [--bridge] [--exit-link vless://...]
#   kombain-node.sh add-user NAME
#   kombain-node.sh links
#   kombain-node.sh status
#   kombain-node.sh uninstall
#
# Roles:
#   single — standalone node: clients exit to the internet from here.
#   entry  — first hop of a cascade: clients connect here, traffic leaves through the
#            exit node given by --exit-link (Russian sites go out directly).
#   --bridge on a single node also creates the credential an entry node uses to reach it.
#
# Output protocol (parsed by the Kombain app):
#   KB_STEP <text>     progress line
#   KB_ERROR <text>    fatal error
#   KB_RESULT <base64 JSON>  {"links":[{"user":..,"link":..}], "bridge": "vless://..."}
set -euo pipefail

KB_DIR=${KB_DIR:-/etc/kombain}
KB_BIN=${KB_BIN:-/usr/local/bin}
KB_SHARE=${KB_SHARE:-/usr/local/share/kombain}
KB_SYSTEMD=${KB_SYSTEMD:-1}          # 0 = don't touch systemd/firewall (testing)
KB_DOWNLOAD=${KB_DOWNLOAD:-1}        # 0 = binaries are already in $KB_BIN (testing)
KB_TEST=${KB_TEST:-0}                # 1 = allow private targets, custom Reality target (testing only)
KB_REALITY_TARGET=${KB_REALITY_TARGET:-}
KB_SOCKS_PORT=${KB_SOCKS_PORT:-10808}  # local hand-off from Hysteria2 to Xray
XRAY_URL_BASE=${XRAY_URL_BASE:-https://github.com/XTLS/Xray-core/releases/latest/download}
HY_URL_BASE=${HY_URL_BASE:-https://github.com/apernet/hysteria/releases/latest/download}

step() { echo "KB_STEP $*"; }
die() { echo "KB_ERROR $*"; exit 1; }
trap 'echo "KB_ERROR команда завершилась с ошибкой (строка $LINENO)"' ERR

need_root() { [ "$(id -u)" = 0 ] || die "нужны права root (запустите через sudo)"; }

# ---------------------------------------------------------------------------- state

state_file() { echo "$KB_DIR/state.env"; }
users_file() { echo "$KB_DIR/users"; }

load_state() {
  [ -f "$(state_file)" ] || die "Kombain на этом сервере ещё не установлен"
  # shellcheck disable=SC1090
  . "$(state_file)"
}

save_state() {
  mkdir -p "$KB_DIR"; chmod 700 "$KB_DIR"
  {
    for v in ROLE HOST PROTOCOLS SNI PORT HY_PORT NAME PRIV PUB SID HY_OBFS BRIDGE_UUID \
             EXIT_ADDR EXIT_PORT EXIT_UUID EXIT_FLOW EXIT_SNI EXIT_PBK EXIT_SID EXIT_FP; do
      printf "%s=%q\n" "$v" "${!v-}"
    done
  } > "$(state_file)"
  chmod 600 "$(state_file)"
}

has_proto() { case ",$PROTOCOLS," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

urlenc() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$1" 2>/dev/null \
  || printf '%s' "$1" | od -An -tx1 | tr -d ' \n' | sed 's/../%&/g'; }

rand_hex() { openssl rand -hex "$1"; }

# ---------------------------------------------------------------------------- install deps

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64) XRAY_ARCH=64; HY_ARCH=amd64 ;;
    aarch64|arm64) XRAY_ARCH=arm64-v8a; HY_ARCH=arm64 ;;
    armv7l) XRAY_ARCH=arm32-v7a; HY_ARCH=arm ;;
    *) die "неподдерживаемая архитектура $(uname -m)" ;;
  esac
}

install_packages() {
  local missing=()
  for c in curl unzip openssl; do command -v "$c" >/dev/null || missing+=("$c"); done
  [ ${#missing[@]} -eq 0 ] && return 0
  step "Ставлю пакеты: ${missing[*]}"
  if command -v apt-get >/dev/null; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${missing[@]}" >/dev/null
  elif command -v dnf >/dev/null; then dnf install -y -q "${missing[@]}" >/dev/null
  elif command -v yum >/dev/null; then yum install -y -q "${missing[@]}" >/dev/null
  else die "не найден apt/dnf/yum — поставьте вручную: ${missing[*]}"; fi
}

download_binaries() {
  [ "$KB_DOWNLOAD" = 1 ] || return 0
  detect_arch
  local tmp; tmp=$(mktemp -d)
  mkdir -p "$KB_BIN" "$KB_SHARE"
  step "Скачиваю Xray-core"
  curl -fsSL --retry 3 -o "$tmp/xray.zip" "$XRAY_URL_BASE/Xray-linux-$XRAY_ARCH.zip" || die "не удалось скачать Xray (нет доступа к GitHub?)"
  unzip -qo "$tmp/xray.zip" -d "$tmp/xray"
  install -m 755 "$tmp/xray/xray" "$KB_BIN/kombain-xray"
  install -m 644 "$tmp/xray/geoip.dat" "$tmp/xray/geosite.dat" "$KB_SHARE/"
  if has_proto hy2; then
    step "Скачиваю Hysteria2"
    curl -fsSL --retry 3 -o "$tmp/hysteria" "$HY_URL_BASE/hysteria-linux-$HY_ARCH" || die "не удалось скачать Hysteria2"
    install -m 755 "$tmp/hysteria" "$KB_BIN/kombain-hysteria"
  fi
  rm -rf "$tmp"
}

# ---------------------------------------------------------------------------- configs

gen_keys() {
  step "Генерирую ключи Reality"
  local out; out=$("$KB_BIN/kombain-xray" x25519)
  PRIV=$(echo "$out" | awk -F': *' 'tolower($1) ~ /private/ {print $2; exit}')
  PUB=$(echo "$out" | awk -F': *' 'tolower($1) ~ /public|password/ {print $2; exit}')
  [ -n "$PRIV" ] && [ -n "$PUB" ] || die "не удалось получить ключи Reality"
  SID=$(rand_hex 8)
  HY_OBFS=$(rand_hex 16)
}

new_user_line() { # name -> "name|uuid|hypass"
  printf '%s|%s|%s\n' "$1" "$("$KB_BIN/kombain-xray" uuid)" "$(rand_hex 16)"
}

parse_exit_link() { # vless://uuid@host:port?query#name
  local link=$1 rest q
  [[ $link == vless://* ]] || die "ссылка выходной ноды должна быть vless://"
  rest=${link#vless://}; rest=${rest%%#*}
  EXIT_UUID=${rest%%@*}; rest=${rest#*@}
  q=${rest#*\?}; rest=${rest%%\?*}
  EXIT_PORT=${rest##*:}; EXIT_ADDR=${rest%:*}; EXIT_ADDR=${EXIT_ADDR#[}; EXIT_ADDR=${EXIT_ADDR%]}
  getq() { echo "$q" | tr '&' '\n' | awk -F= -v k="$1" '$1==k {print $2; exit}'; }
  EXIT_FLOW=$(getq flow); EXIT_SNI=$(getq sni); EXIT_PBK=$(getq pbk); EXIT_SID=$(getq sid); EXIT_FP=$(getq fp)
  EXIT_FP=${EXIT_FP:-chrome}
  [ -n "$EXIT_UUID" ] && [ -n "$EXIT_PBK" ] && [ -n "$EXIT_SNI" ] || die "в ссылке выходной ноды не хватает данных Reality"
}

json_clients() {
  local first=1
  while IFS='|' read -r name uuid _; do
    [ -n "$uuid" ] || continue
    [ $first = 1 ] || printf ','
    first=0
    printf '{"id":"%s","flow":"xtls-rprx-vision","email":"%s"}' "$uuid" "$(echo "$name" | sed 's/["\\]//g')"
  done < "$(users_file)"
  if [ -n "${BRIDGE_UUID-}" ]; then
    [ $first = 1 ] || printf ','
    printf '{"id":"%s","flow":"xtls-rprx-vision","email":"kombain-bridge"}' "$BRIDGE_UUID"
  fi
}

render_xray() {
  local ru_rules="" outbound_main="direct" extra_out="" socks_in="" freedom_settings=""
  [ "$KB_TEST" = 1 ] && freedom_settings=', "settings": {"finalRules": [{"action": "allow"}]}'
  if has_proto hy2; then
    socks_in=',{"tag":"hy-in","listen":"127.0.0.1","port":'"$KB_SOCKS_PORT"',"protocol":"socks","settings":{"udp":true}}'
  fi
  if [ "$ROLE" = entry ]; then
    outbound_main="to-exit"
    extra_out=$(printf ',{"tag":"to-exit","protocol":"vless","settings":{"vnext":[{"address":"%s","port":%s,"users":[{"id":"%s","encryption":"none","flow":"%s"}]}]},"streamSettings":{"network":"raw","security":"reality","realitySettings":{"serverName":"%s","fingerprint":"%s","publicKey":"%s","shortId":"%s"}}}' \
      "$EXIT_ADDR" "$EXIT_PORT" "$EXIT_UUID" "${EXIT_FLOW:-xtls-rprx-vision}" "$EXIT_SNI" "$EXIT_FP" "$EXIT_PBK" "$EXIT_SID")
    if [ -f "$KB_SHARE/geosite.dat" ]; then
      ru_rules='{"type":"field","domain":["geosite:category-ru"],"outboundTag":"direct"},{"type":"field","ip":["geoip:ru"],"outboundTag":"direct"},'
    fi
  fi
  cat > "$KB_DIR/xray.json" <<EOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "tag": "vless-in", "listen": "0.0.0.0", "port": $PORT, "protocol": "vless",
    "settings": {"clients": [$(json_clients)], "decryption": "none"},
    "streamSettings": {"network": "raw", "security": "reality",
      "realitySettings": {"target": "${KB_REALITY_TARGET:-$SNI:443}", "serverNames": ["$SNI"], "privateKey": "$PRIV", "shortIds": ["$SID"]}},
    "sniffing": {"enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true}
  }$socks_in],
  "outbounds": [{"tag": "direct", "protocol": "freedom"$freedom_settings}, {"tag": "block", "protocol": "blackhole"}$extra_out],
  "routing": {"domainStrategy": "IPIfNonMatch", "rules": [
    ${ru_rules}
    {"type": "field", "ip": ["geoip:private"], "outboundTag": "block"},
    {"type": "field", "network": "tcp,udp", "outboundTag": "$outbound_main"}
  ]}
}
EOF
  # geoip:private needs geoip.dat; drop the rule if it is missing (testing setups)
  if [ ! -f "$KB_SHARE/geoip.dat" ] || [ "$KB_TEST" = 1 ]; then
    sed -i '/"geoip:private"/d' "$KB_DIR/xray.json"
  fi
  chmod 600 "$KB_DIR/xray.json"
}

render_hysteria() {
  has_proto hy2 || return 0
  if [ ! -f "$KB_DIR/hy.crt" ]; then
    openssl req -x509 -nodes -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -days 3650 \
      -keyout "$KB_DIR/hy.key" -out "$KB_DIR/hy.crt" -subj "/CN=$SNI" >/dev/null 2>&1
    chmod 600 "$KB_DIR/hy.key"
  fi
  {
    echo "listen: :$HY_PORT"
    echo "tls:"
    echo "  cert: $KB_DIR/hy.crt"
    echo "  key: $KB_DIR/hy.key"
    echo "obfs:"
    echo "  type: salamander"
    echo "  salamander:"
    echo "    password: $HY_OBFS"
    echo "auth:"
    echo "  type: userpass"
    echo "  userpass:"
    local i=0
    while IFS='|' read -r _ _ hypass; do
      [ -n "$hypass" ] || continue
      i=$((i + 1)); echo "    u$i: $hypass"
    done < "$(users_file)"
    echo "masquerade:"
    echo "  type: proxy"
    echo "  proxy:"
    echo "    url: https://$SNI/"
    echo "    rewriteHost: true"
    # all traffic goes through Xray, so routing (direct / cascade) is the same for both protocols
    echo "outbounds:"
    echo "  - name: xray"
    echo "    type: socks5"
    echo "    socks5:"
    echo "      addr: 127.0.0.1:$KB_SOCKS_PORT"
  } > "$KB_DIR/hysteria.yaml"
  chmod 600 "$KB_DIR/hysteria.yaml"
}

# ---------------------------------------------------------------------------- services

write_units() {
  [ "$KB_SYSTEMD" = 1 ] || return 0
  cat > /etc/systemd/system/kombain-xray.service <<EOF
[Unit]
Description=Kombain Xray
After=network-online.target
[Service]
Environment=XRAY_LOCATION_ASSET=$KB_SHARE
ExecStart=$KB_BIN/kombain-xray run -c $KB_DIR/xray.json
Restart=always
RestartSec=3
LimitNOFILE=1048576
[Install]
WantedBy=multi-user.target
EOF
  if has_proto hy2; then
    cat > /etc/systemd/system/kombain-hysteria.service <<EOF
[Unit]
Description=Kombain Hysteria2
After=network-online.target kombain-xray.service
[Service]
ExecStart=$KB_BIN/kombain-hysteria server -c $KB_DIR/hysteria.yaml
Restart=always
RestartSec=3
LimitNOFILE=1048576
[Install]
WantedBy=multi-user.target
EOF
  fi
  systemctl daemon-reload
}

restart_services() {
  [ "$KB_SYSTEMD" = 1 ] || return 0
  step "Запускаю службы"
  systemctl enable --now kombain-xray >/dev/null 2>&1
  systemctl restart kombain-xray
  if has_proto hy2; then
    systemctl enable --now kombain-hysteria >/dev/null 2>&1
    systemctl restart kombain-hysteria
  fi
  sleep 2
  systemctl is-active --quiet kombain-xray || die "Xray не запустился: $(journalctl -u kombain-xray -n 5 --no-pager | tail -3 | tr '\n' ' ')"
  if has_proto hy2; then
    systemctl is-active --quiet kombain-hysteria || die "Hysteria2 не запустилась: $(journalctl -u kombain-hysteria -n 5 --no-pager | tail -3 | tr '\n' ' ')"
  fi
}

open_firewall() {
  [ "$KB_SYSTEMD" = 1 ] || return 0
  if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
    step "Открываю порты в ufw"
    ufw allow "$PORT/tcp" >/dev/null
    has_proto hy2 && ufw allow "$HY_PORT/udp" >/dev/null
  elif command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
    step "Открываю порты в firewalld"
    firewall-cmd -q --permanent --add-port="$PORT/tcp"
    has_proto hy2 && firewall-cmd -q --permanent --add-port="$HY_PORT/udp"
    firewall-cmd -q --reload
  fi
  return 0
}

enable_bbr() {
  [ "$KB_SYSTEMD" = 1 ] || return 0
  if ! sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null | grep -q bbr; then
    step "Включаю BBR"
    printf 'net.core.default_qdisc=fq\nnet.ipv4.tcp_congestion_control=bbr\n' > /etc/sysctl.d/99-kombain.conf
    sysctl -q --system >/dev/null 2>&1 || true
  fi
}

# ---------------------------------------------------------------------------- output

host_for_link() { [[ $HOST == *:* ]] && echo "[$HOST]" || echo "$HOST"; }

print_result() {
  local h links="" first=1 label
  h=$(host_for_link)
  local i=0
  while IFS='|' read -r name uuid hypass; do
    [ -n "$uuid" ] || continue
    i=$((i + 1))  # same numbering as render_hysteria
    if has_proto vless; then
      label=$(urlenc "$NAME · $name · VLESS")
      local l="vless://$uuid@$h:$PORT?encryption=none&flow=xtls-rprx-vision&security=reality&sni=$SNI&fp=chrome&pbk=$PUB&sid=$SID&type=tcp#$label"
      [ $first = 1 ] || links+=","; first=0
      links+=$(printf '{"user":"%s","proto":"vless","link":"%s"}' "$name" "$l")
    fi
    if has_proto hy2; then
      label=$(urlenc "$NAME · $name · Hysteria2")
      local l="hysteria2://u$i:$hypass@$h:$HY_PORT/?sni=$SNI&insecure=1&obfs=salamander&obfs-password=$HY_OBFS#$label"
      [ $first = 1 ] || links+=","; first=0
      links+=$(printf '{"user":"%s","proto":"hy2","link":"%s"}' "$name" "$l")
    fi
  done < "$(users_file)"
  local bridge=""
  if [ -n "${BRIDGE_UUID-}" ]; then
    bridge="vless://$BRIDGE_UUID@$h:$PORT?encryption=none&flow=xtls-rprx-vision&security=reality&sni=$SNI&fp=chrome&pbk=$PUB&sid=$SID&type=tcp#bridge"
  fi
  local json; json=$(printf '{"role":"%s","links":[%s],"bridge":"%s"}' "$ROLE" "$links" "$bridge")
  echo "KB_RESULT $(printf '%s' "$json" | base64 | tr -d '\n')"
}

# ---------------------------------------------------------------------------- commands

cmd_install() {
  ROLE=single; HOST=""; PROTOCOLS="vless,hy2"; SNI="www.microsoft.com"; PORT=443; HY_PORT=443
  NAME="Kombain"; local first_user="Телефон" bridge=0 exit_link=""
  while [ $# -gt 0 ]; do
    case $1 in
      --host) HOST=$2; shift 2 ;;
      --role) ROLE=$2; shift 2 ;;
      --protocols) PROTOCOLS=$2; shift 2 ;;
      --sni) SNI=$2; shift 2 ;;
      --port) PORT=$2; shift 2 ;;
      --hy-port) HY_PORT=$2; shift 2 ;;
      --name) NAME=$2; shift 2 ;;
      --user) first_user=$2; shift 2 ;;
      --bridge) bridge=1; shift ;;
      --exit-link) exit_link=$2; shift 2 ;;
      *) die "неизвестный параметр $1" ;;
    esac
  done
  [ -n "$HOST" ] || die "не указан --host"
  [ "$ROLE" = single ] || [ "$ROLE" = entry ] || die "роль должна быть single или entry"
  [ "$KB_SYSTEMD" = 1 ] && need_root
  if [ "$ROLE" = entry ]; then
    [ -n "$exit_link" ] || die "для входной ноды нужна --exit-link"
    parse_exit_link "$exit_link"
  fi
  step "Сервер: $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Linux}"), $(uname -m)"
  install_packages
  download_binaries
  mkdir -p "$KB_DIR"; chmod 700 "$KB_DIR"
  gen_keys
  [ $bridge = 1 ] && BRIDGE_UUID=$("$KB_BIN/kombain-xray" uuid) || BRIDGE_UUID=""
  new_user_line "$first_user" > "$(users_file)"; chmod 600 "$(users_file)"
  save_state
  step "Пишу конфиги"
  render_xray; render_hysteria; write_units
  open_firewall; enable_bbr
  restart_services
  step "Готово"
  print_result
}

cmd_add_user() {
  [ $# -ge 1 ] || die "укажите имя пользователя"
  load_state
  grep -q "^$1|" "$(users_file)" && die "пользователь «$1» уже есть"
  new_user_line "$1" >> "$(users_file)"
  render_xray; render_hysteria; restart_services
  step "Пользователь «$1» добавлен"
  print_result
}

cmd_status() {
  load_state
  echo "KB_STEP Роль: $ROLE, протоколы: $PROTOCOLS, пользователей: $(grep -c . "$(users_file)")"
  if [ "$KB_SYSTEMD" = 1 ]; then
    echo "KB_STEP Xray: $(systemctl is-active kombain-xray 2>/dev/null || true)"
    has_proto hy2 && echo "KB_STEP Hysteria2: $(systemctl is-active kombain-hysteria 2>/dev/null || true)"
  fi
  return 0
}

cmd_uninstall() {
  [ "$KB_SYSTEMD" = 1 ] && need_root
  if [ "$KB_SYSTEMD" = 1 ]; then
    systemctl disable --now kombain-xray kombain-hysteria >/dev/null 2>&1 || true
    rm -f /etc/systemd/system/kombain-xray.service /etc/systemd/system/kombain-hysteria.service /etc/sysctl.d/99-kombain.conf
    systemctl daemon-reload
  fi
  rm -rf "$KB_DIR" "$KB_SHARE" "$KB_BIN/kombain-xray" "$KB_BIN/kombain-hysteria"
  step "Kombain удалён с сервера"
}

case ${1-} in
  install) shift; cmd_install "$@" ;;
  add-user) shift; cmd_add_user "$@" ;;
  links) load_state; print_result ;;
  status) cmd_status ;;
  uninstall) cmd_uninstall ;;
  *) die "команды: install | add-user NAME | links | status | uninstall" ;;
esac
