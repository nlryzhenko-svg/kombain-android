package io.nekohasekai.sagernet.ui

import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.kombain.Updater
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

/** «Доступна новая версия DAK» → download → system installer. */
object UpdateDialog {

    private var busy = false

    /** [manual]: from the button — also says when there is nothing new or the check failed. */
    fun check(activity: MainActivity, manual: Boolean) {
        if (busy) return
        busy = true
        runOnDefaultDispatcher {
            val r = runCatching { Updater.check() }
            onMainDispatcher {
                busy = false
                if (activity.isFinishing) return@onMainDispatcher
                val release = r.getOrNull()
                when {
                    release != null -> offer(activity, release)
                    !manual -> {}
                    r.isFailure -> activity.snackbar("Не удалось проверить обновления: ${r.exceptionOrNull()?.message ?: "нет связи"}").show()
                    else -> activity.snackbar("У вас последняя версия DAK (${Updater.current})").show()
                }
            }
        }
    }

    private fun offer(activity: MainActivity, r: Updater.Release) {
        val notes = r.notes.lines().filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") }
            .joinToString("\n").trim().take(1500)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Доступна DAK ${r.version}")
            .setMessage((if (notes.isNotBlank()) "$notes\n\n" else "") + "Сейчас установлена ${Updater.current}. " +
                    "Серверы и настройки сохранятся." + if (r.size > 0) " Размер: ${r.size / 1_048_576} МБ." else "")
            .setPositiveButton("Обновить") { _, _ -> download(activity, r) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun download(activity: MainActivity, r: Updater.Release) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp2px(24), dp2px(20), dp2px(24), dp2px(8))
            addView(ProgressBar(activity))
            addView(TextView(activity).apply {
                text = "Скачиваю DAK ${r.version}…"
                textSize = 16f
                setPadding(dp2px(16), dp2px(10), 0, 0)
            })
        }
        val progress = MaterialAlertDialogBuilder(activity).setView(box).setCancelable(false).show()
        runOnDefaultDispatcher {
            val file = runCatching { Updater.download(r) }
            onMainDispatcher {
                runCatching { progress.dismiss() }
                if (activity.isFinishing) return@onMainDispatcher
                file.onSuccess { f ->
                    if (!Updater.install(activity, f)) {
                        // after the permission screen the user taps «Обновить» once more
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("Установка DAK ${r.version}")
                            .setMessage("Когда разрешите установку, нажмите «Обновить».")
                            .setPositiveButton("Обновить") { _, _ -> Updater.install(activity, f) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }.onFailure {
                    activity.snackbar("Не удалось скачать обновление: ${it.message}").show()
                }
            }
        }
    }
}
