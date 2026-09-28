package com.flowclicker.app

import com.flowclicker.app.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import java.net.Socket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Minimal test HTTP server using only Android/JVM-common java.net APIs. */
private class HttpServer private constructor(address: InetSocketAddress) {
    private val socket = ServerSocket().apply { bind(address) }
    val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress
    private var handler: ((Exchange) -> Unit)? = null
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    fun createContext(path: String, block: (Exchange) -> Unit) { handler = block }
    fun start() {
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                clients.add(client)
                Thread {
                    try { client.use { handler!!(Exchange(it)) } }
                    catch (_: Exception) { }
                    finally { clients.remove(client) }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }
    fun stop(delay: Int) { socket.close(); clients.forEach { runCatching { it.close() } } }
    companion object {
        fun create(address: InetSocketAddress, backlog: Int) = HttpServer(address)
    }
    class Exchange(private val socket: Socket) {
        val requestBody: ByteArrayInputStream
        val responseBody get() = socket.getOutputStream()
        init {
            socket.soTimeout = 10000
            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            var tail = 0
            while (tail != 0x0d0a0d0a) {
                val b = input.read()
                check(b >= 0)
                header.write(b)
                tail = (tail shl 8) or b
            }
            val length = header.toString("UTF-8").lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                check(n > 0); read += n
            }
            requestBody = ByteArrayInputStream(bytes)
        }
        fun sendResponseHeaders(code: Int, length: Long) {
            responseBody.write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${length.coerceAtLeast(0)}\r\nConnection: close\r\n\r\n".toByteArray())
        }
        fun close() { socket.close() }
    }
}

/** Loopback fake provider only: no API keys, external requests, or Android devices. */
class AiClientTest {
    @Test fun badKeyIsNotRetried() = runBlocking {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { e ->
            requests.incrementAndGet()
            val body = "bad key".toByteArray()
            e.sendResponseHeaders(401, body.size.toLong()); e.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = AiClient(AiSettings(baseUrl = "http://127.0.0.1:${server.address.port}", model = "fake"))
            try { client.chat(listOf(client.textMessage("user", "test"))); fail("expected 401") }
            catch (e: AiHttpException) { assertEquals(401, e.status) }
            assertEquals(1, requests.get())
        } finally { server.stop(0) }
    }

    @Test fun transientFailureRetriesIdenticalRequestNotTools() = runBlocking {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { e ->
            bodies.add(e.requestBody.bufferedReader().readText())
            val code = if (bodies.size == 1) 503 else 200
            val response = if (code == 503) "busy" else """{"choices":[{"message":{"content":[{"type":"text","text":"ok"}]}}]}"""
            val bytes = response.toByteArray()
            e.sendResponseHeaders(code, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = AiClient(AiSettings(baseUrl = "http://127.0.0.1:${server.address.port}", model = "fake"))
            val messages = listOf(client.textMessage("user", """{"tool_result":{"alreadyExecuted":true}}"""))
            assertEquals("ok", client.chat(messages))
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
        } finally { server.stop(0) }
    }

    @Test fun cancellationStopsWaitingForDelayedResponse() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { e ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            runCatching { e.sendResponseHeaders(503, -1) }
            e.close()
        }
        server.start()
        try {
            val client = AiClient(AiSettings(baseUrl = "http://127.0.0.1:${server.address.port}", model = "fake"))
            val job = launch(Dispatchers.Default) { client.chat(listOf(client.textMessage("user", "test"))) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }
}
