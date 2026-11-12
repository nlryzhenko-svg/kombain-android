# Kombain — VPN-комбайн для Android

Форк [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) (GPL-3.0) на ядре sing-box.

## Что добавлено поверх NekoBox

- **AmneziaWG** (AWG 1.x/2.0): в профиле WireGuard есть раздел «AmneziaWG (обход DPI)» —
  туда вставляются строки `Jc / Jmin / Jmax / S1–S4 / H1–H4 / I1–I5` из конфига Amnezia.
  Импорт `.conf` от Amnezia подхватывает их автоматически.
- **Импорт ссылок AmneziaVPN `vpn://…`** — из буфера обмена, QR или подписки; вытаскиваются
  все WireGuard/AWG-конфиги.
- **Экран «Раздача»** — раздача VPN устройствам на точке доступа телефона через встроенный
  HTTP/SOCKS5-прокси: показывает адрес и порт, QR-код для второго телефона, инструкцию.
- Новое имя, иконка, фирменная тема (по умолчанию), убраны пункты рекламы/доков NekoBox.

Протоколы ядра: VLESS (Reality/XTLS), VMess, Trojan, Shadowsocks, Hysteria 1/2, TUIC, AnyTLS,
ShadowTLS, SSH, SOCKS, HTTP, WireGuard + AmneziaWG; плагины Naive / Mieru / Trojan-Go.

## Как устроен AmneziaWG

Ядро — форк sing-box от MatsuriDayo (`1.12.x-neko`). При сборке к нему применяется
`buildScript/lib/core/patches/sing-box-amneziawg.patch`, а `sagernet/wireguard-go` заменяется
на [Leadaxe/wireguard-go](https://github.com/Leadaxe/wireguard-go) (база sagernet + обфускация
amneziawg-go), коммит закреплён в `buildScript/lib/core/get_source_env.sh`.
Если поля AWG пустые — работает как обычный WireGuard.

## Сборка

Нужны: JDK 17, Go ≥ 1.25, Android SDK 35, NDK `25.0.8775105`.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "ndk.dir=$ANDROID_HOME/ndk/25.0.8775105" >> local.properties
# подпись: KEYSTORE_PASS / ALIAS_NAME / ALIAS_PASS в local.properties или env
./run lib core                 # ядро -> app/libs/libcore.aar
bash buildScript/lib/assets.sh # geoip/geosite
./gradlew app:assembleOssRelease
```

APK: `app/build/outputs/apk/oss/release/`. Для телефонов на Snapdragon/Exynos — `arm64-v8a`.

Подпись — собственный ключ `release.keystore`. **Храните его и пароль**: обновления ставятся
поверх только с тем же ключом.
Ключ в репозиторий не кладётся (он в `.gitignore`).

## Сборка в GitHub Actions

- **«Сборка APK»** (`.github/workflows/build.yml`) — Actions → «Сборка APK» → Run workflow.
  APK появляется в артефактах запуска. С галочкой «Опубликовать релиз» (или при пуше тега
  `v3.3.1`) создаётся релиз с APK.
- Нужны секреты репозитория (Settings → Secrets and variables → Actions):
  `KEYSTORE_BASE64` (`base64 -w0 release.keystore`), `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`.
  Без них APK не будет подписан вашим ключом и не встанет поверх установленного Kombain.

## Обновление ядер

В приложении два ядра: **sing-box** (`libcore.aar`, форк MatsuriDayo + патч AmneziaWG,
версии закреплены в `buildScript/lib/core/get_source_env.sh`) и **Xray-core**
(`libxray.so`, версия в `buildScript/lib/xray.sh`).

```bash
bash buildScript/update-core.sh --check          # есть ли новые версии (ничего не меняет)
bash buildScript/update-core.sh                  # обновить и собрать
bash buildScript/update-core.sh --branch 1.14.x  # перейти на другую ветку sing-box
```

Скрипт берёт свежий коммит ветки sing-box, libneko, wireguard-go (AmneziaWG) и последний
стабильный релиз Xray-core, накладывает патч AmneziaWG, делает `go mod tidy` и собирает.
Если патч не наложился или что-то не собралось — все изменения откатываются.

Workflow **«Обновление ядер»** (`.github/workflows/update-core.yml`) делает то же самое
автоматически каждый понедельник: собирает APK с новыми ядрами (он в артефактах запуска —
поставьте и проверьте) и открывает pull request. Влили PR → запустили «Сборка APK» с релизом.
Чтобы бот мог открывать PR: Settings → Actions → General → «Allow GitHub Actions to create
and approve pull requests».

## Ограничения раздачи

Android пускает трафик точки доступа мимо VpnService, поэтому без root раздача идёт через
прокси. В настройках Wi-Fi на устройстве-клиенте указывается HTTP-прокси (браузер и
большинство приложений), а для всего трафика — второй телефон с Kombain/v2rayNG по QR-коду
(SOCKS5). Полная прозрачная раздача (iptables) возможна только с root.

## Движок Xray-core (с 1.2.0)

Свежие серверы Xray (26.x) отвергают Reality-клиент sing-box 1.12 — в журнале это
`reality verification failed`. Поэтому в Kombain встроен Xray-core: у профилей
VLESS/VMess/Trojan есть переключатель «Движок Xray-core». sing-box остаётся
TUN/маршрутизатором, а такой профиль обслуживает отдельный процесс Xray (тот же движок,
что в Happ и v2rayNG). Импорт `vpn://` из Amnezia включает его автоматически.

Сборка бинарника: `bash buildScript/lib/xray.sh` → `app/executableSo/<abi>/libxray.so`.

## Сторож связи и автовыбор (с 2.1.0)

`bg/Watchdog.kt` в процессе VPN раз в 30 с делает URL-тест через туннель. После трёх
неудач (и если прямой интернет у телефона есть) сбрасывает соединения и переподключается,
а с «Автовыбором сервера» — переходит на самый быстрый живой сервер группы
(`kombain/AutoPick.kt`). «Проверить все» (спидометр) — тот же тест для всей группы.

## Обслуживание (с 3.0.0)

`kombain/Maintenance.kt` — WorkManager раз в 6 часов: проверка своих серверов по SSH
(`kombain-node.sh stats`) с уведомлением о падении служб, обновление подписок раз в сутки,
geoip/geosite раз в неделю. «Российские приложения мимо VPN» — `kombain/RuApps.kt`.
Виджет — `kombain/KombainWidget.kt`.

## Свой сервер из приложения (с 1.4.0)

«Ещё → Мои серверы» → «Поднять сервер». Kombain заходит на VPS по SSH (пароль или ключ;
root или пользователь с sudo), загружает `app/src/main/assets/kombain/kombain-node.sh` и
ставит на выбор VLESS + Reality, VLESS + XHTTP + Reality (Xray), Hysteria2 и TUIC v5 (официальный
sing-box) как службы systemd; домен не нужен. Режимы: одна нода или каскад
(вход в РФ → выход за рубежом; на входе `geosite:category-ru`/`geoip:ru` идут напрямую,
Hysteria2 передаёт трафик в Xray, поэтому маршрутизация общая). Профили импортируются в
отдельную группу, VLESS — с движком Xray. Доступы к серверам шифруются ключом из Android
Keystore. На сервере: `/etc/kombain`, `/usr/local/bin/kombain-{xray,hysteria}`,
службы `kombain-xray` и `kombain-hysteria`. Скрипт можно запускать и вручную:
`bash kombain-node.sh install --host IP [--protocols vless,xhttp,hy2,tuic] [--role entry --exit-link vless://…]`.
Другие команды: `add-user`, `remove-user`, `links`, `stats` (JSON для мониторинга: службы,
нагрузка, трафик по пользователям через Xray stats API), `rotate [--port …]` (новые ключи,
пароли и пути; для каскада приложение затем вызывает `set-exit` на входной ноде), `uninstall`.

## Защита от блокировок (с 3.4.0)

Команда сервера `kombain-node.sh harden [--antifreeze on|off] [--fp …] [--sni-rotation on|off] [--hop on|off]`,
в приложении — «Ещё» у сервера → «Защита от блокировок…». У новых серверов всё, кроме прыжков портов, включено (прыжки на мобильном интернете в РФ давали «нет связи»).

- **Против «заморозки»**: XHTTP-вход получает VLESS Encryption (`xray vlessenc`, X25519) и Vision;
  в ссылке `mode=stream-one` и `extra={"xmux":{"maxConnections":2}}`.
- **Отпечаток** (`fp=` в ссылках и в связи каскада), по умолчанию `firefox`.
- **Чередование имён**: `serverNames` Reality = имя сайта + до двух других имён из его сертификата,
  которые тот же адрес отдаёт с тем же сертификатом. В ссылке `kbsni=a,b`; Kombain строит для
  Xray несколько outbound'ов и балансировщик `random`, другие клиенты параметр игнорируют.
- **Прыжки портов Hysteria2**: диапазон из 1000 UDP-портов перенаправляется на порт Hysteria2
  (nftables, иначе iptables; служба `kombain-hop`), в ссылке `mport=A-B`.
- **Белые списки**: `kombain/Whitelist.kt` проверяет IP и сайт маскировки входного сервера
  по спискам ancored/ru-mobile-whitelist (копия в APK, свежая скачивается раз в 3 дня).

## Sudoku (с 3.5.0)

[Sudoku](https://github.com/SUDOKU-ASCII/sudoku) кодирует трафик головоломками судоку 4×4:
в канале печатные символы / низкая энтропия вместо «случайного шума».

- Сервер: `kombain-node.sh install --protocols …,sudoku [--sd-port 8880]` или `enable-sudoku` / `disable-sudoku`.
  Бинарник — официальный релиз `sudoku-linux-{amd64,arm64}`, служба `kombain-sudoku`. У сервера
  мастер-ключ, у каждого пользователя свой ключ (`sudoku -keygen -more`), сервер пускает только их
  (`users`). Неудачное рукопожатие уходит на `SNI:80`. Только одиночный сервер (в каскаде не умеет
  отдавать трафик выходному).
- Ссылки: `sudoku://<base64url JSON>` — формат официальных клиентов.
- Клиент: `buildScript/lib/sudoku.sh` собирает `libsudoku.so` (версия закреплена, обновляется
  `update-core.sh`); `fmt/sudoku/*` — профиль, процесс запускается как движок Xray: локальный
  SOCKS-прокси для sing-box, к серверу ходит через mapping-вход sing-box. В сборке выключен
  встроенный в клиент список блокировки рекламы (маршрутизирует sing-box).
