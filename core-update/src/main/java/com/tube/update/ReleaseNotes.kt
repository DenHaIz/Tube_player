package com.tube.update

/** Раздел описания релиза: заголовок категории («Новое», «Исправлено») и пункты. */
data class NotesSection(val title: String, val items: List<String>)

/**
 * Разбор описания релиза для окна обновления: блок нужного языка и категории внутри него.
 *
 * Формат описания (Markdown):
 * ```
 * **RU**
 * ### Новое
 * - пункт
 * ### Исправлено
 * - пункт
 * **EN**
 * ### New
 * - item
 * ```
 * Метки языка — строки `RU`/`EN`/`Русский`/`English` (можно в `**…**` или после `#`). Без меток берётся всё описание.
 * Заголовок категории — строка с `#` или целиком в `**…**`; текст до первой категории — раздел без заголовка.
 */
object ReleaseNotes {
    private val LanguageMark = Regex("""^[#\s*]*(RU|EN|Русский|English|Russian)[*:\s]*$""", RegexOption.IGNORE_CASE)
    private val Heading = Regex("""^(#{1,6})\s*(.+?)\s*#*$""")
    private val BoldLine = Regex("""^\*\*(.+?)\*\*:?$""")
    private val Bullet = Regex("""^([-*•]|\d+[.)])\s+""")

    fun sections(body: String, english: Boolean): List<NotesSection> {
        val lines = body.replace("\r", "").lines().map { it.trim() }
        val block = languageBlock(lines, english)
        val out = ArrayList<NotesSection>()
        var title = ""
        var items = ArrayList<String>()
        fun flush() {
            if (items.isNotEmpty() || title.isNotEmpty()) out += NotesSection(title, items)
            items = ArrayList()
        }
        for (line in block) {
            if (line.isEmpty()) continue
            val heading = Heading.matchEntire(line)?.groupValues?.get(2) ?: BoldLine.matchEntire(line)?.groupValues?.get(1)
            if (heading != null) {
                flush()
                title = heading.trim().trimEnd(':')
            } else {
                items += line.replace(Bullet, "").replace("**", "").trim()
            }
        }
        flush()
        return out.filter { it.items.isNotEmpty() }
    }

    private fun languageBlock(lines: List<String>, english: Boolean): List<String> {
        val marks = lines.indices.filter { LanguageMark.matches(lines[it]) }
        if (marks.isEmpty()) return lines
        val wanted = marks.firstOrNull { isEnglish(lines[it]) == english } ?: return lines
        val end = marks.firstOrNull { it > wanted } ?: lines.size
        return lines.subList(wanted + 1, end)
    }

    private fun isEnglish(mark: String): Boolean {
        val m = mark.lowercase()
        return "en" in m.replace("russian", "").replace("ru", "") && !m.contains("рус")
    }
}
