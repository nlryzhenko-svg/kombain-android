package io.nekohasekai.sagernet.kombain

import android.content.pm.PackageManager
import io.nekohasekai.sagernet.database.DataStore

/**
 * Russian apps that refuse to work through a foreign VPN (banks, Gosuslugi, marketplaces …).
 * With «Приложения мимо VPN» they bypass the tunnel entirely. The built-in list is grouped for the
 * screen; the user can untick any of them ([DataStore.kbDirectOff]) and add any other app
 * ([DataStore.kbDirectExtra]).
 */
object RuApps {

    class Category(val title: String, val packages: List<String>)

    val categories = listOf(
        Category("Банки и платежи", listOf(
            "ru.sberbankmobile", "ru.sberbank.sberbankid", "ru.sberbank.spasibo", "com.idamob.tinkoff.android",
            "ru.tinkoff.investing", "ru.tinkoff.sme", "ru.vtb24.mobilebanking.android", "ru.vtb.invest",
            "ru.alfabank.mobile.android", "ru.alfabank.oavdo.amc", "ru.alfadirect.app",
            "ru.gazprombank.android.mobilebank.app", "ru.raiffeisen.rmobile", "ru.rosbank.android",
            "ru.psbank.mobile", "ru.mtsbank.mtsbank", "ru.mts.money", "ru.pochtabank.pochtaapp",
            "ru.sovcombank.halvacard", "ru.otpbank.mobile", "ru.ozon.fintech.finance", "com.yandex.bank",
            "ru.yandex.money", "ru.rshb.dbo", "ru.bspb", "ru.akbars.mobile", "ru.uralsib.mb", "ru.mkb.mobile",
            "ru.nspk.mirpay", "ru.nspk.sbpay", "ru.vk.pay", "ru.wildberries.bank",
        )),
        Category("Госуслуги и налоги", listOf(
            "ru.rostel", "ru.gosuslugi.goskey", "ru.gosuslugi.pos", "ru.fns.lkfl", "ru.nalog.www",
            "ru.mos.app", "ru.mos.polls", "ru.pfr.mobile", "ru.fss.mobile", "ru.gibdd.app", "ru.mvd.app",
        )),
        Category("Маркетплейсы и доставка", listOf(
            "com.wildberries.ru", "ru.ozon.app.android", "ru.beru.android", "ru.avito.app", "ru.yandex.lavka",
            "ru.foodfox.client", "ru.sbermarket.android", "ru.samokat.app", "com.deliveryclub", "ru.megamarket.marketplace",
            "ru.lamoda", "ru.detmir.dmbonus", "ru.leroymerlin.mobile",
        )),
        Category("Такси, карты и транспорт", listOf(
            "ru.yandex.taxi", "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.dublgis.dgismobile",
            "ru.rzd.pass", "ru.aeroflot", "ru.citymobil.app", "ru.yandex.metro", "ru.tutu.tutu",
        )),
        Category("Связь, почта и соцсети", listOf(
            "com.vkontakte.android", "ru.vk.store", "ru.mail.mailapp", "ru.ok.android", "ru.oneme.app",
            "ru.mts.mymts", "ru.megafon.mlk", "ru.beeline.services", "ru.tele2.mytele2", "ru.yandex.mail",
        )),
        Category("Кино, музыка и прочее", listOf(
            "ru.kinopoisk", "ru.ivi.client", "ru.more.play", "ru.okko.app", "ru.yandex.music", "com.vk.vkvideo",
            "ru.yandex.searchplugin", "com.yandex.browser",
        )),
    )

    val packages: List<String> get() = categories.flatMap { it.packages }

    private fun isInstalled(pm: PackageManager, pkg: String) =
        runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun installed(pm: PackageManager): List<String> = packages.filter { isInstalled(pm, it) }

    fun off(): Set<String> = DataStore.kbDirectOff.split('\n').filter { it.isNotBlank() }.toSet()
    fun extra(): Set<String> = DataStore.kbDirectExtra.split('\n').filter { it.isNotBlank() }.toSet()

    fun setOff(set: Set<String>) { DataStore.kbDirectOff = set.joinToString("\n") }
    fun setExtra(set: Set<String>) { DataStore.kbDirectExtra = set.joinToString("\n") }

    /** What really goes around the tunnel: the installed built-in apps the user kept + the ones added. */
    fun effective(pm: PackageManager): List<String> =
        (installed(pm).filterNot { it in off() } + extra().filter { isInstalled(pm, it) }).distinct()
}
