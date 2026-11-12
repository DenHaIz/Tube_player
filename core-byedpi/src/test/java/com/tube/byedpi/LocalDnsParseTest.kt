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

    @Test
    fun errorResponseGivesNothing() {
        val servFail = LocalDns.servFail(LocalDns.aQuery("example.com"))!!
        assertTrue(LocalDns.parseA(servFail).isEmpty())
    }
}
