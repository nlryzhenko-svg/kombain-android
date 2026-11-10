package io.nekohasekai.sagernet.kombain

import android.content.pm.PackageManager

/**
 * Russian apps that refuse to work through a foreign VPN (banks, Gosuslugi, marketplaces).
 * With «Российские приложения мимо VPN» they bypass the tunnel entirely.
 */
object RuApps {

    val packages = listOf(
        // banks
        "ru.sberbankmobile", "ru.sberbank.sberbankid", "com.idamob.tinkoff.android", "ru.tinkoff.investing",
        "ru.vtb24.mobilebanking.android", "ru.alfabank.mobile.android", "ru.alfabank.oavdo.amc",
        "ru.gazprombank.android.mobilebank.app", "ru.raiffeisen.rmobile", "ru.rosbank.android",
        "ru.psbank.mobile", "ru.mtsbank.mtsbank", "ru.mts.money", "ru.pochtabank.pochtaapp",
        "ru.sovcombank.halvacard", "ru.otpbank.mobile", "ru.ozon.fintech.finance", "com.yandex.bank",
        "ru.yandex.money", "ru.rshb.dbo", "ru.bspb", "ru.akbars.mobile", "ru.uralsib.mb", "ru.mkb.mobile",
        "ru.nspk.mirpay", "ru.nspk.sbpay",
        // government
        "ru.rostel", "ru.gosuslugi.goskey", "ru.fns.lkfl", "ru.nalog.www", "ru.mos.app", "ru.pfr.mobile",
        "ru.fss.mobile", "ru.gibdd.app",
        // marketplaces / services that block foreign IPs
        "com.wildberries.ru", "ru.ozon.app.android", "ru.beru.android", "ru.avito.app",
        "ru.yandex.taxi", "ru.dublgis.dgismobile", "ru.rzd.pass", "ru.vk.store", "com.vkontakte.android",
        "ru.mail.mailapp", "ru.ok.android", "ru.yandex.yandexmaps", "ru.kinopoisk", "ru.gosuslugi.pos",
        "ru.mts.mymts", "ru.megafon.mlk", "ru.beeline.services", "ru.tele2.mytele2",
    )

    fun installed(pm: PackageManager): List<String> = packages.filter {
        runCatching { pm.getApplicationInfo(it, 0); true }.getOrDefault(false)
    }
}
