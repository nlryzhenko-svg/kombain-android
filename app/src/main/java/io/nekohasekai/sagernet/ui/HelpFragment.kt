package io.nekohasekai.sagernet.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/** Kombain help: short, plain-language answers, one card per question. */
class HelpFragment : ToolbarFragment(R.layout.layout_help) {

    private val topics = listOf(
        "С чего начать" to
                "1. Добавьте сервер: кнопка «+» вверху → «Вставить из буфера» (скопируйте ссылку заранее), «Сканировать QR-код» или «Из файла».\n" +
                "2. Нажмите на строку сервера — она подсветится.\n" +
                "3. Нажмите круглую кнопку на карточке сверху. Первый раз Android спросит разрешение на VPN — нажмите «OK».\n\n" +
                "Ссылки `vless://`, `vmess://`, `trojan://`, `ss://`, `hy2://`, `tuic://`, `vpn://` можно открывать прямо из Telegram или браузера — Kombain предложит добавить сервер.",

        "Какие протоколы поддерживаются" to
                "VLESS (в т. ч. Reality и XTLS Vision), VMess, Trojan, Shadowsocks, Hysteria 1 и 2, TUIC, AnyTLS, ShadowTLS, SSH, SOCKS, HTTP, WireGuard и AmneziaWG.\n\n" +
                "Источники ссылок: панель 3x-ui (кнопка «Поделиться» у клиента или ссылка подписки), приложение Amnezia (Поделиться → файл или ссылка vpn://), любые подписки в формате v2rayN/Clash.",

        "Подписка: как обновлять серверы автоматически" to
                "Если у вас ссылка подписки (обычно начинается с https:// и даёт список серверов), добавьте её в «Ещё → Подписки». Kombain раз в сутки сам подтягивает новые серверы (Настройки → «Обновлять подписки»). Обновить вручную можно там же, через меню подписки.",

        "Что за «Движок Xray-core» в профиле" to
                "В Kombain два движка. Основной — sing-box, он быстрый и умеет всё. Но новые серверы Xray (например, от Amnezia и свежих 3x-ui) иногда не пускают sing-box по Reality — в журнале это «reality verification failed», а сайты не открываются.\n\n" +
                "Тогда откройте профиль (карандаш на плитке) и включите «Движок Xray-core». Это тот же движок, что в Happ и v2rayNG. Серверы из Amnezia получают его автоматически, на плитке видно «· Xray».",

        "AmneziaWG" to
                "Это WireGuard с маскировкой от блокировок. Импортируйте .conf-файл или ссылку vpn:// из приложения Amnezia — параметры маскировки (Jc, S1, H1…) подхватятся сами.\n\n" +
                "Вручную: профиль WireGuard → раздел «AmneziaWG» → вставьте строки Jc/Jmin/Jmax/S1–S4/H1–H4/I1–I5 из конфига, по одной на строку.",

        "Какой протокол выбрать" to
                "VLESS Reality — основной: маскируется под обычный сайт, работает почти везде.\n" +
                "XHTTP — тот же VLESS, но трафик похож на обычные веб-запросы. Включайте, если Reality начали резать.\n" +
                "Hysteria2 и TUIC — работают через UDP (QUIC), очень быстрые на плохом мобильном интернете. Если один из них не работает, попробуйте другой.\n\n" +
                "Не знаете, что выбрать, — нажмите спидометр вверху («Проверить все») и возьмите самый быстрый живой.",

        "Связь «зависает», помогает только переподключение" to
                "Включите Настройки → «Сторож связи» (включён по умолчанию). Он раз в 30 секунд проверяет интернет через VPN и, если связь пропала, сам сбрасывает соединения и переподключается.\n\n" +
                "Если в группе несколько серверов, включите ещё «Автовыбор сервера» — тогда при падении сервера VPN перейдёт на другой живой сервер. Что делал сторож, видно в «Ещё → Журнал» (строки «[Kombain] сторож»).",

        "VPN включён, а сайты не открываются" to
                "1. Нажмите на зелёную карточку — она проверит пинг. «Сервер не отвечает» значит проблема со связью до сервера.\n" +
                "2. Откройте «Ещё → Журналы». Если видите «reality verification failed» — включите в профиле «Движок Xray-core».\n" +
                "3. Попробуйте другой сервер или другой DNS (Настройки → DNS-сервер).\n" +
                "4. Если не помогло — пришлите скриншот журнала тому, кто настраивал сервер.",

        "VPN сам отключается в фоне" to
                "Чаще всего его выгружает экономия батареи (особенно на Samsung).\n\n" +
                "Настройки → «Не усыплять Kombain» → разрешите работу без ограничений. На Samsung дополнительно: Настройки телефона → Батарея → Ограничения фоновой работы → уберите Kombain из «спящих» приложений.\n\n" +
                "Если не помогло — включите «Держать процессор активным».",

        "Банки и госуслуги не открываются через VPN" to
                "Многие российские сервисы не пускают зарубежные IP. Включите Настройки → «Российские сайты напрямую»: они пойдут без VPN, а остальное — через VPN.\n\n" +
                "Приложения банков, Госуслуги, маркетплейсы и операторов можно целиком пустить мимо VPN: Настройки → «Российские приложения мимо VPN». Нужного нет в списке — добавьте его в «Приложения через VPN» → «Все, кроме выбранных».",

        "Свой сервер: как поднять" to
                "Нужен VPS с Debian или Ubuntu (хватит 1 ГБ памяти) и вход по SSH: IP, логин (обычно root) и пароль или ключ.\n\n" +
                "«Ещё → Мои серверы» (или «+» → «Поднять свой сервер») → «Поднять сервер». Выберите «Одна нода» или «Каскад», заполните поля и нажмите «Установить». " +
                "Отметьте протоколы (VLESS Reality, XHTTP, Hysteria2, TUIC) — свой домен не нужен. Через 1–3 минуты профили появятся в отдельной группе.\n\n" +
                "Каскад: входной сервер в РФ (лучше с «белым» IP), выходной — за рубежом. Телефон подключается к входному, тот передаёт трафик на выходной; российские сайты идут с входного напрямую.\n\n" +
                "Поделиться с близкими: «Мои серверы» → «Пользователи» → «Добавить пользователя». Появятся QR-коды — их сканируют в Kombain, Happ, v2rayNG или Hiddify. Удалить пользователя можно там же — его ссылки сразу перестанут работать.\n\n" +
                "Если сервер начали блокировать: «Ещё» у сервера → «Сменить ключи» или «Сменить порты и ключи». Профили на этом телефоне обновятся сами.\n\n" +
                "Kombain раз в 6 часов проверяет ваши серверы и пришлёт уведомление, если служба упала. Нагрузка, память и трафик видны на карточке сервера.",

        "Telegram без VPN" to
                "Kombain может поставить на ваш сервер прокси для Telegram (MTProto с маскировкой под обычный сайт). Telegram подключается к нему сам — VPN включать не нужно, остальные приложения работают как обычно.\n\n" +
                "Новый сервер: в мастере отметьте «Прокси для Telegram». Уже поднятый сервер: «Ещё» у сервера → «Включить прокси для Telegram».\n\n" +
                "Подключить: «Пользователи» → «Прокси для Telegram» → «Подключить» → «Открыть в Telegram», Telegram спросит «Подключить прокси?». На другой телефон или компьютер отправьте ссылку или покажите QR-код. Ссылка одна на всех.\n\n" +
                "В каскаде прокси стоит на входном сервере в РФ, а трафик Telegram уходит через выходной.",

        "Сервер, поднятый с другого устройства" to
                "«Мои серверы» → «Подключить существующий». Введите IP, логин и пароль SSH — Kombain зайдёт на сервер, заберёт ссылки и покажет сервер в списке. Ничего не переустанавливается, ключи не меняются, другие устройства продолжают работать.\n\n" +
                "Не нажимайте для такого сервера «Поднять сервер»: установка заново создаст новые ключи, и старые ссылки перестанут работать.\n\n" +
                "Только профили, без управления сервером: на другом устройстве откройте «Пользователи» → «QR-коды» → «Копировать все ссылки» и вставьте их сюда через «+» → «Импорт из буфера обмена».",

        "Быстрый переключатель в шторке" to
                "В шторке: потяните шторку вниз, нажмите карандаш и перетащите плитку «Kombain» в быстрые настройки.",

        "Раздать VPN на ноутбук или другой телефон" to
                "Вкладка «Раздача». Включите точку доступа, затем «Пускать устройства из сети». На втором устройстве в настройках Wi-Fi укажите прокси с адресом и портом с экрана «Раздача».\n\n" +
                "Для всего трафика (игры, звонки) поставьте Kombain на второй телефон и отсканируйте там QR-код с экрана «Раздача».",

        "Обновление Kombain и резервная копия" to
                "Новая версия ставится поверх старой, серверы и настройки сохраняются (если APK подписан тем же ключом).\n\n" +
                "Резервная копия всех серверов и настроек: «Ещё → Бэкап и сеть». Сохраните файл перед переустановкой.",

        "Где тонкие настройки" to
                "Настройки → «Для продвинутых»: все параметры ядра — свой DNS, MTU, sniffing, IPv6, порты, собственный конфиг sing-box. Меняйте, только если понимаете, зачем.",
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.ks_help)
        val list = view.findViewById<LinearLayout>(R.id.help_list)
        val ctx = requireContext()
        for ((q, a) in topics) {
            val card = MaterialCardView(ctx).apply {
                radius = dp2px(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp2px(1)
                strokeColor = ContextCompat.getColor(ctx, R.color.kombain_tile_stroke)
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.kombain_tile))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp2px(10) }
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp2px(18), dp2px(14), dp2px(14), dp2px(14))
            }
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(ctx).apply {
                text = q
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val chevron = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_kombain_chevron)
                layoutParams = LinearLayout.LayoutParams(dp2px(24), dp2px(24))
            }
            val body = TextView(ctx).apply {
                text = a.replace("`", "")
                textSize = 14.5f
                setLineSpacing(dp2px(3).toFloat(), 1f)
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
                setPadding(0, dp2px(10), dp2px(4), 0)
                isVisible = false
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
            head.addView(title)
            head.addView(chevron)
            inner.addView(head)
            inner.addView(body)
            card.addView(inner)
            card.setOnClickListener {
                body.isVisible = !body.isVisible
                chevron.animate().rotation(if (body.isVisible) 180f else 0f).setDuration(150).start()
            }
            list.addView(card)
        }
    }
}
