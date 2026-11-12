package com.tube.player

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Простой SOCKS5-ретранслятор вместо byedpi (на сервере сборки нет Android-бинарника):
 * принимает CONNECT по имени, как byedpi, и считает соединения — тест видит, что трафик шёл через прокси.
 */
class SocksRelay {
    val connections = AtomicInteger()
    val hosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        runCatching {
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()
            check(input.readByte().toInt() == 5)
            val n = input.readUnsignedByte(); input.skipBytes(n)
            out.write(byteArrayOf(5, 0))
            input.readByte(); input.readByte(); input.readByte()
            val host = when (input.readUnsignedByte()) {
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
                else -> error("atyp")
            }
            val port = input.readUnsignedShort()
            connections.incrementAndGet()
            hosts += host
            val upstream = Socket().apply { connect(InetSocketAddress(host, port), 8000) }
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            thread(isDaemon = true) { runCatching { upstream.getInputStream().copyTo(out) }; runCatching { client.close() } }
            runCatching { input.copyTo(upstream.getOutputStream()) }
            upstream.close()
        }
        runCatching { client.close() }
    }

    fun close() = server.close()
}
