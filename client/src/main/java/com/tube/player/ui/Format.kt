package com.tube.player.ui

import com.tube.ui.i18n.AppLanguage
import java.util.Locale

/** Подписи для карточек: коротко, по-русски и по-английски. */
object Format {
    /** 1:02:03 / 4:05. */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    fun millis(ms: Long): String = duration((ms / 1000).coerceAtLeast(0)).ifEmpty { "0:00" }

    /** 1,2 млн / 350 тыс. / 1.2M / 350K. */
    fun compact(n: Long, lang: AppLanguage): String {
        if (n < 0) return ""
        fun one(v: Double) = if (v < 10 && v != Math.floor(v)) "%.1f".format(Locale.ROOT, v).let { if (lang == AppLanguage.Russian) it.replace('.', ',') else it } else v.toLong().toString()
        return when {
            n >= 1_000_000_000 -> one(n / 1e9) + lang.pick(" млрд", "B")
            n >= 1_000_000 -> one(n / 1e6) + lang.pick(" млн", "M")
            n >= 1_000 -> one(n / 1e3) + lang.pick(" тыс.", "K")
            else -> n.toString()
        }
    }

    fun views(n: Long, lang: AppLanguage): String {
        if (n < 0) return ""
        val word = lang.plural(if (n >= 1000) 5 else n.toInt(), "просмотр", "просмотра", "просмотров", "view", "views")
        return compact(n, lang) + " " + word
    }

    fun subscribers(n: Long, lang: AppLanguage): String {
        if (n < 0) return ""
        val word = lang.plural(if (n >= 1000) 5 else n.toInt(), "подписчик", "подписчика", "подписчиков", "subscriber", "subscribers")
        return compact(n, lang) + " " + word
    }

    /** «Канал · 1,2 млн просмотров · 3 дня назад» — без пустых частей. */
    fun meta(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")
}
