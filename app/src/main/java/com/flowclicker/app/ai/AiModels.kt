package com.flowclicker.app.ai

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AiSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 模型是否支持图像输入（false 时 get_screenshot 不可用） */
    val vlm: Boolean = true,
    /** 单次会话最大工具调用轮数 */
    val maxToolRounds: Int = 6,
    /** debug 模式任务跑满多少轮后唤醒 AI 复盘 */
    val debugRounds: Int = 2,
)

@Serializable
data class WakeRule(
    /** "idle" | "stall" */
    val type: String,
    /** idle: 省略=全局；stall: 必填，表示该任务执行后等待接续 */
    val taskId: Long? = null,
    val timeoutMs: Long,
)

@Serializable
data class AiLogEntry(
    val time: Long,
    val eventType: String,
    val detail: String,
    val toolCalls: Int,
    val reply: String,
)

/** AI 配置、唤醒规则、调度日志三个小 JSON 存储 */
object AiStores {
    private const val TAG = "AiStores"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private lateinit var settingsFile: File
    private lateinit var rulesFile: File
    private lateinit var logFile: File

    fun init(context: Context) {
        val dir = context.filesDir
        settingsFile = File(dir, "ai_settings.json")
        rulesFile = File(dir, "wake_rules.json")
        logFile = File(dir, "ai_log.json")
    }

    private inline fun <reified T> read(file: File, default: T): T {
        if (!file.exists()) return default
        return runCatching { json.decodeFromString<T>(file.readText()) }
            .onFailure { Log.w(TAG, "decode failed: ${file.name}", it) }
            .getOrDefault(default)
    }

    private inline fun <reified T> write(file: File, value: T) {
        runCatching { file.writeText(json.encodeToString(value)) }
            .onFailure { Log.w(TAG, "save failed: ${file.name}", it) }
    }

    fun loadSettings(): AiSettings = read(settingsFile, AiSettings())
    fun saveSettings(s: AiSettings) = write(settingsFile, s)

    fun loadRules(): List<WakeRule> = read(rulesFile, emptyList())
    fun saveRules(r: List<WakeRule>) = write(rulesFile, r)

    fun loadLog(): List<AiLogEntry> = read(logFile, emptyList())

    fun appendLog(e: AiLogEntry) {
        write(logFile, (loadLog() + e).takeLast(30))
    }
}
