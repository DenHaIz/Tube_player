package com.tube.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    private val body = """
        **RU**
        ### Новое
        - Самообновление
        - Зеркала
        ### Исправлено
        - Загрузка через прокси
        **EN**
        ### New
        - Self-update
        ### Fixed
        - Loading through the proxy
    """.trimIndent()

    @Test
    fun picksLanguageAndCategories() {
        val ru = ReleaseNotes.sections(body, english = false)
        assertEquals(listOf("Новое", "Исправлено"), ru.map { it.title })
        assertEquals(listOf("Самообновление", "Зеркала"), ru[0].items)
        val en = ReleaseNotes.sections(body, english = true)
        assertEquals(listOf("New", "Fixed"), en.map { it.title })
        assertEquals(listOf("Loading through the proxy"), en[1].items)
    }

    @Test
    fun plainTextIsOneSection() {
        val s = ReleaseNotes.sections("Клиент YouTube со встроенным обходом.\n\nYouTube client with built-in unblocking.", english = false)
        assertEquals(1, s.size)
        assertEquals("", s[0].title)
        assertEquals(2, s[0].items.size)
    }
}
