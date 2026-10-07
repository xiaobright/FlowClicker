package com.flowclicker.app

import com.flowclicker.app.ai.JudgeProtocol
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** On-device compatibility server with no external calls or request-body logging. */
internal class JudgeFakeServer : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${server.localPort}/custom/v1"
    val judgeRequests = AtomicInteger()
    val chatRequests = AtomicInteger()
    @Volatile var confidence = 0.99
    @Volatile var status = 200
    @Volatile var malformed = false
    @Volatile var onJudge: () -> Unit = {}
    @Volatile var chatPlan: List<String> = emptyList()
    @Volatile var lastJudgePath = ""
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    init {
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                clients.add(client)
                Thread({
                    try { client.use { handle(client) } } catch (_: Exception) { }
                    finally { clients.remove(client) }
                }, "judge-fake-client").apply { isDaemon = true; start() }
            }
        }, "judge-fake").apply { isDaemon = true; start() }
    }
    private fun handle(client: Socket) {
        client.soTimeout = 20000
        val input = client.getInputStream()
        val header = java.io.ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            val b = input.read(); check(b >= 0); header.write(b); tail = (tail shl 8) or b
            check(header.size() < 16384)
        }
        val headers = header.toString("UTF-8")
        val length = headers.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        check(length in 0..100000)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(bytes, read, length - read); check(n > 0); read += n }
        val path = headers.lineSequence().first().split(' ')[1]
        val body = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        var code = 200
        val output = if (path.endsWith("/systemone")) {
            lastJudgePath = path
            judgeRequests.incrementAndGet(); onJudge(); code = status
            if (malformed) "{}" else answer(body)
        } else if (path.endsWith("/chat/completions")) {
            val n = chatRequests.getAndIncrement()
            val content = chatPlan.getOrNull(n) ?: """{"reply":"模拟调度完成"}"""
            buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                putJsonObject("message") { put("content", content) }
            }) } }.toString()
        } else { code = 404; "{}" }
        val data = output.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(data); flush()
        }
    }
    private fun answer(body: JsonObject): String = buildJsonObject {
        put("model", "compatible-test-model")
        putJsonObject("usage") { put("input_tokens", 100) }
        putJsonObject("answers") {
            val questions = body.getValue("questions").jsonObject
            for ((key, question) in questions) {
                if (key == "sufficient") { putJsonObject(key) { put("type", "noul"); put("noul", 0.99) }; continue }
                val options = question.jsonObject.getValue("criteria").jsonObject.keys
                val selected = if (key == "scene") "connection_problem" else options.first { it != "escalate" }
                putJsonObject(key) {
                    put("type", "choice"); put("choice", selected); put("confidence", confidence)
                    putJsonObject("probabilities") { options.forEach { put(it, if (it == selected) 1.0 else 0.0) } }
                }
            }
        }
    }.toString()
    override fun close() { server.close(); clients.forEach { runCatching { it.close() } } }
}
