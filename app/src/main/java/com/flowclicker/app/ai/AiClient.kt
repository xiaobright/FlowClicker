package com.flowclicker.app.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI 兼容 chat/completions 客户端（HttpURLConnection，零额外依赖）。
 * 消息以 JsonArray 构建，content 支持纯文本或多模态部件（text + image_url data URL）。
 */
class AiClient(private val settings: AiSettings) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** 压缩截图供模型查看：限制最长边、JPEG 质量 80 */
        fun bitmapToDataUrl(frame: Bitmap, maxEdge: Int = 1120): String {
            val scale = minOf(1f, maxEdge / (maxOf(frame.width, frame.height).toFloat()))
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else frame
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            if (bmp !== frame) bmp.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            return "data:image/jpeg;base64,$b64"
        }
    }

    fun textMessage(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    /** 多模态消息：文本 + 若干 data URL 图片 */
    fun imageMessage(role: String, text: String, imageDataUrls: List<String>): JsonObject =
        buildJsonObject {
            put("role", role)
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                    imageDataUrls.forEach { url ->
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", url) })
                        })
                    }
                }
            )
        }

    /** 阻塞调用，返回首个 choice 的文本内容（多模态返回时拼接 text 部件） */
    suspend fun chat(messages: List<JsonObject>): String {
        // Retry this request only. The caller retains every already completed tool result.
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try { return request(messages) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == 2 || !RetryPolicy.retryable(e)) throw e
                delay(if (attempt == 0) 5000 else 15000)
            }
        }
        error("unreachable")
    }

    private suspend fun request(messages: List<JsonObject>): String = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val worker = CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = blockingRequest(messages, connection) { continuation.isActive }
                if (continuation.isActive) continuation.resume(response)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
        continuation.invokeOnCancellation {
            connection.get()?.disconnect()
            worker.cancel()
        }
    }

    private fun blockingRequest(
        messages: List<JsonObject>, reference: AtomicReference<HttpURLConnection?>, active: () -> Boolean
    ): String {
        val body = buildJsonObject {
            put("model", settings.model)
            put("temperature", 0.3)
            put("messages", JsonArray(messages))
        }.toString()

        val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (settings.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
        }
        reference.set(conn)
        try {
            if (!active()) throw CancellationException("请求已取消")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                throw AiHttpException(code, "HTTP $code: ${text.take(400)}")
            }
            return parseContent(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseContent(responseText: String): String {
        val root = json.parseToJsonElement(responseText)
        val obj = root as? JsonObject ?: throw IllegalStateException("响应不是 JSON 对象")
        val choices = obj["choices"] as? JsonArray ?: throw IllegalStateException("响应缺 choices")
        val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: throw IllegalStateException("响应缺 message")
        return when (val content = message["content"]) {
            is kotlinx.serialization.json.JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }.joinToString("\n")
            else -> content?.toString() ?: ""
        }
    }
}
