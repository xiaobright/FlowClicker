package com.flowclicker.app.ai

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One bounded request; fallback owns recovery. No retry of a late decision. */
class JudgeClient(private val settings: JudgeSettings) {
    suspend fun evaluate(body: JsonObject, candidates: List<JudgeCandidate>): JudgeDecision =
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val worker = CoroutineScope(Dispatchers.IO).launch {
                try {
                    settings.validate()
                    val conn = (URL(settings.endpoint()).openConnection() as HttpURLConnection)
                    connection.set(conn)
                    try {
                        if (!continuation.isActive) throw CancellationException()
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 4000; conn.readTimeout = 8000
                        conn.instanceFollowRedirects = false
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        if (settings.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                        val status = conn.responseCode
                        if (status != 200) throw AiHttpException(status, "判断服务 HTTP $status")
                        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(65537)
                            var size = 0
                            while (size < buffer.size) {
                                val n = reader.read(buffer, size, buffer.size - size)
                                if (n < 0) break
                                size += n
                            }
                            check(size <= 65536) { "Jev response too large" }
                            String(buffer, 0, size)
                        }
                        val result = JudgeProtocol.parse(text, candidates)
                        if (continuation.isActive) continuation.resume(result)
                    } finally { conn.disconnect() }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
            continuation.invokeOnCancellation {
                CoroutineScope(Dispatchers.IO).launch { connection.get()?.disconnect() }
                worker.cancel()
            }
        }
}
