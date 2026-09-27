package com.flowclicker.app.ai

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import com.flowclicker.app.core.AtomicTextFile

@Serializable
data class AiSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 模型是否支持图像输入（false 时 get_screenshot 不可用） */
    val vlm: Boolean = true,
    /** 单次会话最大工具调用轮数（confirm_target 两步校准+建任务+启动+试运行 ≈ 7 轮，6 不够用） */
    val maxToolRounds: Int = 8,
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

/** L3 全局长期记忆：跨任务的经验/界面规律/陷阱，每次唤醒注入上下文 */
@Serializable
data class MemoryNote(val text: String, val createdAt: Long)

/** L4 任务绑定笔记：坐标细节/流程状态/给下次自己的提醒，随任务存取 */
@Serializable
data class TaskNote(val taskId: Long, val text: String, val updatedAt: Long)

/** debug 任务的一次会话留档（仅文本，图片省略），供下次唤醒续接上下文 */
@Serializable
data class SessionMessage(val role: String, val text: String)

@Serializable
data class SessionRecord(
    val taskId: Long,
    val updatedAt: Long,
    val messages: List<SessionMessage> = emptyList(),
)

/** AI 配置、唤醒规则、调度日志、记忆/笔记/会话续接等小 JSON 存储 */
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
    private lateinit var memoryFile: File
    private lateinit var taskNotesFile: File
    private lateinit var sessionsDir: File

    fun init(context: Context) {
        val dir = context.filesDir
        settingsFile = File(dir, "ai_settings.json")
        rulesFile = File(dir, "wake_rules.json")
        logFile = File(dir, "ai_log.json")
        memoryFile = File(dir, "ai_memory.json")
        taskNotesFile = File(dir, "ai_task_notes.json")
        sessionsDir = File(dir, "ai_sessions")
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
    }

    private inline fun <reified T> read(file: File, default: T): T {
        if (!file.exists()) return default
        return runCatching { json.decodeFromString<T>(AtomicTextFile.read(file)) }
            .onFailure { Log.w(TAG, "decode failed: ${file.name}", it) }
            .getOrDefault(default)
    }

    private inline fun <reified T> write(file: File, value: T) {
        AtomicTextFile.write(file, json.encodeToString(value))
    }

    fun loadSettings(): AiSettings = read(settingsFile, AiSettings())
    @Synchronized fun saveSettings(s: AiSettings) = write(settingsFile, s)

    fun loadRules(): List<WakeRule> = read(rulesFile, emptyList())
    @Synchronized fun saveRules(r: List<WakeRule>) = write(rulesFile, r)

    fun loadLog(): List<AiLogEntry> = read(logFile, emptyList())

    @Synchronized fun appendLog(e: AiLogEntry) {
        write(logFile, (loadLog() + e).takeLast(30))
    }

    fun loadMemory(): List<MemoryNote> = read(memoryFile, emptyList())

    @Synchronized fun addMemory(text: String) {
        write(memoryFile, (loadMemory() + MemoryNote(text, System.currentTimeMillis())).takeLast(20))
    }

    private fun loadTaskNotes(): List<TaskNote> = read(taskNotesFile, emptyList())

    fun loadTaskNote(taskId: Long): TaskNote? = loadTaskNotes().firstOrNull { it.taskId == taskId }

    @Synchronized fun saveTaskNote(note: TaskNote) {
        write(taskNotesFile, loadTaskNotes().filter { it.taskId != note.taskId } + note)
    }

    fun loadSession(taskId: Long): SessionRecord? = runCatching {
        val f = File(sessionsDir, "task_$taskId.json")
        if (!f.exists()) null else json.decodeFromString<SessionRecord>(AtomicTextFile.read(f))
    }.onFailure { Log.w(TAG, "decode session failed: task_$taskId", it) }.getOrNull()

    @Synchronized fun saveSession(record: SessionRecord) {
        runCatching {
            AtomicTextFile.write(File(sessionsDir, "task_${record.taskId}.json"), json.encodeToString(record))
        }.onFailure { Log.w(TAG, "save session failed: task_${record.taskId}", it) }
    }

    fun deleteSession(taskId: Long) {
        runCatching { File(sessionsDir, "task_$taskId.json").delete() }
    }

    /** 删除任务时连带清理其笔记与续接会话 */
    @Synchronized fun deleteTaskData(taskId: Long) {
        write(taskNotesFile, loadTaskNotes().filter { it.taskId != taskId })
        deleteSession(taskId)
    }
}
