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

    /**
     * Когда опубликовано: «3 дня назад». YouTube для страницы видео и RSS отдаёт дату как «2025-11-10T08:00:00-08:00» —
     * такую считаем сами по [VideoItem.uploadedAt]; относительную подпись из выдачи («3 дня назад») оставляем как есть.
     */
    fun uploaded(item: com.tube.player.youtube.VideoItem, lang: AppLanguage, now: Long = System.currentTimeMillis()): String? {
        val text = item.uploaded?.trim()?.takeIf { it.isNotEmpty() }
        val iso = text != null && IsoDate.containsMatchIn(text)
        if (text != null && !iso) return text
        val at = item.uploadedAt.takeIf { it > 0 } ?: text?.let(::parseIso) ?: return null
        return ago(at, lang, now)
    }

    private val IsoDate = Regex("""^\d{4}-\d{2}-\d{2}""")

    private fun parseIso(text: String): Long? = runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(text.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

    /** Дата комментария: относительная подпись YouTube как есть, ISO — пересчитываем. */
    fun relative(text: String, at: Long, lang: AppLanguage, now: Long = System.currentTimeMillis()): String {
        if (!IsoDate.containsMatchIn(text.trim())) return text
        val millis = at.takeIf { it > 0 } ?: parseIso(text.trim()) ?: return text
        return ago(millis, lang, now)
    }

    /** «5 минут назад», «2 недели назад», «1 год назад» / «5 minutes ago». */
    fun ago(millis: Long, lang: AppLanguage, now: Long = System.currentTimeMillis()): String {
        val seconds = ((now - millis) / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        fun unit(n: Long, one: String, few: String, many: String, enOne: String, enMany: String): String {
            val word = lang.plural(n.toInt(), one, few, many, enOne, enMany)
            return if (lang == AppLanguage.English) "$n $word ago" else "$n $word назад"
        }
        return when {
            minutes < 1 -> lang.pick("только что", "just now")
            hours < 1 -> unit(minutes, "минуту", "минуты", "минут", "minute", "minutes")
            days < 1 -> unit(hours, "час", "часа", "часов", "hour", "hours")
            days < 7 -> unit(days, "день", "дня", "дней", "day", "days")
            days < 30 -> unit(days / 7, "неделю", "недели", "недель", "week", "weeks")
            days < 365 -> unit((days / 30).coerceAtLeast(1), "месяц", "месяца", "месяцев", "month", "months")
            else -> unit(days / 365, "год", "года", "лет", "year", "years")
        }
    }

    /** «Канал · 1,2 млн просмотров · 3 дня назад» — без пустых частей. */
    fun meta(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")
}
