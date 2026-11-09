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

## Ограничения раздачи

Android пускает трафик точки доступа мимо VpnService, поэтому без root раздача идёт через
прокси. В настройках Wi-Fi на устройстве-клиенте указывается HTTP-прокси (браузер и
большинство приложений), а для всего трафика — второй телефон с Kombain/v2rayNG по QR-коду
(SOCKS5). Полная прозрачная раздача (iptables) возможна только с root.
