package com.tube.byedpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDnsParseTest {

    @Test
    fun parsesARecordsAfterCname() {
        val query = LocalDns.aQuery("www.youtube.com")
        val header = byteArrayOf(0, 0, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 3, 0, 0, 0, 0)
        val question = query.copyOfRange(12, query.size)
        // CNAME на youtube-ui.l.google.com (указатель на имя вопроса — для простоты), затем две записи A.
        val cname = byteArrayOf(0xC0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xC0.toByte(), 12)
        val a1 = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 142.toByte(), 250.toByte(), 74, 46)
        val a2 = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 142.toByte(), 250.toByte(), 74, 78)
        val response = header.copyOf().also { it[0] = query[0]; it[1] = query[1] } + question + cname + a1 + a2
        val addresses = LocalDns.parseA(response).map { it.hostAddress }
        assertEquals(listOf("142.250.74.46", "142.250.74.78"), addresses)
    }

    /** Запрос с другими флагами и лишними записями даёт тот же ключ кэша, что обычный; битые — отбрасываются. */
    @Test
    fun canonicalQueryIgnoresFlagsAndExtraRecords() {
        val plain = LocalDns.aQuery("www.youtube.com")
        val odd = plain.copyOf().also {
            it[0] = 0x12; it[1] = 0x34
            it[2] = 0 // RD = 0
            it[11] = 1 // ARCOUNT = 1 (EDNS)
        } + byteArrayOf(0, 0, 41, 4, 208.toByte(), 0, 0, 0, 0, 0, 0)
        val a = LocalDns.canonical(plain)!!
        val b = LocalDns.canonical(odd)!!
        assertTrue(a.contentEquals(b))
        assertTrue(a.contentEquals(plain))
        assertEquals(null, LocalDns.canonical(plain.copyOf(plain.size - 2)))
        assertEquals(null, LocalDns.canonical(plain.copyOf().also { it[5] = 2 }))
        assertEquals(null, LocalDns.canonical(plain.copyOf().also { it[2] = 0x81.toByte() }))
        assertEquals(null, LocalDns.canonical(plain.copyOf().also { it[12] = 0xC0.toByte() }))
    }

    @Test
    fun errorResponseGivesNothing() {
        val servFail = LocalDns.servFail(LocalDns.aQuery("example.com"))!!
        assertTrue(LocalDns.parseA(servFail).isEmpty())
    }
}
