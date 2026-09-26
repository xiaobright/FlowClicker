package com.flowclicker.app.ai

import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.OcrRecognizer
import com.flowclicker.app.engine.Step
import com.flowclicker.app.engine.Task
import com.flowclicker.app.engine.TaskStore
import com.flowclicker.app.service.RecordingService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI 调度员的内部工具集：观察引擎与任务、修改编排、试运行、看图诊断。
 * 每个工具入参为模型给出的 JsonObject，出参为回填会话的文本（可附带截图）。
 */
object AiTools {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    data class ToolResult(val text: String, val images: List<String> = emptyList())

    suspend fun execute(name: String, args: JsonObject, settings: AiSettings): ToolResult = runCatching {
        when (name) {
            "list_tasks" -> ok(tasksSummary())
            "get_task" -> {
                val t = engine().tasksSnapshot().firstOrNull { it.id == args.long("id") }
                    ?: return ToolResult("""{"error": "任务不存在"}""")
                ok(json.encodeToString(t))
            }

            "upsert_task" -> upsertTask(args)
            "delete_task" -> {
                val id = args.long("id")
                engine().updateTasks { it.removeAll { t -> t.id == id } }
                ok("""{"deleted": $id}""")
            }

            "set_task_enabled" -> {
                val id = args.long("id")
                val en = args.bool("enabled")
                engine().updateTasks { list ->
                    val i = list.indexOfFirst { it.id == id }
                    if (i >= 0) list[i] = list[i].copy(enabled = en)
                }
                ok("""{"id": $id, "enabled": $en}""")
            }

            "set_task_mode" -> {
                val id = args.long("id")
                val mode = args.str("mode")
                require(mode == Task.MODE_NORMAL || mode == Task.MODE_DEBUG) { "mode 只能是 normal/debug" }
                engine().updateTasks { list ->
                    val i = list.indexOfFirst { it.id == id }
                    if (i >= 0) list[i] = list[i].copy(mode = mode)
                }
                ok("""{"id": $id, "mode": "$mode"}""")
            }

            "get_wake_rules" -> ok(json.encodeToString(AiStores.loadRules()))
            "set_wake_rules" -> {
                val rules = json.decodeFromString<List<WakeRule>>(args["rules"]!!.toString())
                require(rules.size <= 10) { "唤醒规则最多 10 条" }
                AiStores.saveRules(rules)
                ok("""{"saved": ${rules.size}}""")
            }

            "get_engine_status" -> ok(engineStatus())
            "describe_screen" -> describeScreen()
            "get_screenshot" -> {
                if (!settings.vlm) {
                    ToolResult("""{"error": "当前模型未启用图像输入，请改用 describe_screen"}""")
                } else {
                    val frame = MonitoringEngine.frameProvider?.invoke()
                        ?: return ToolResult("""{"error": "屏幕采集未开启或暂无帧"}""")
                    try {
                        ToolResult(
                            """{"ok": "当前屏幕截图已附在本条消息后，坐标为原始屏幕像素(宽x高见图像)"}""",
                            listOf(AiClient.bitmapToDataUrl(frame))
                        )
                    } finally {
                        frame.recycle()
                    }
                }
            }

            "get_debug_captures" -> {
                if (!settings.vlm) {
                    ToolResult("""{"error": "当前模型未启用图像输入"}""")
                } else {
                    val id = args.long("taskId")
                    val n = args["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4
                    val files = DebugStore.latestCaptures(id, n)
                    val images = files.mapNotNull { f ->
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                            ?.let { AiClient.bitmapToDataUrl(it) }
                    }
                    if (images.isEmpty()) {
                        ToolResult("""{"captures": [], "note": "该任务暂无 debug 截图"}""")
                    } else {
                        ToolResult(
                            """{"captures": ${files.map { it.name }.toString()}, "note": "截图按时间从旧到新附在本条消息后"}""",
                            images
                        )
                    }
                }
            }

            "run_task_now" -> {
                val id = args.long("taskId")
                val okRun = engine().requestRunNow(id)
                if (okRun) ok("""{"ok": "已插入试运行队列，执行完成后会以 test_run_finished 事件唤醒你"}""")
                else ToolResult("""{"error": "引擎未运行或任务不存在，请先 start_engine"}""")
            }

            "start_engine" -> {
                engine().start()
                ok("""{"ok": "引擎已启动"}""")
            }

            "stop_engine" -> {
                engine().stop()
                ok("""{"ok": "引擎已停止"}""")
            }

            "get_last_recording" -> {
                val rec = RecordingService.lastRecording
                    ?: return ToolResult("""{"error": "暂无录制记录"}""")
                ok(json.encodeToString(rec))
            }

            "log_note" -> {
                val text = args.str("text")
                android.util.Log.i("AiScheduler", "note: $text")
                ok("""{"ok": "已留言"}""")
            }

            else -> ToolResult("""{"error": "未知工具 $name"}""")
        }
    }.getOrElse {
        ToolResult("""{"error": ${json.encodeToString(it.message ?: it.javaClass.simpleName)}}""")
    }

    private fun engine() = MonitoringEngine

    private fun ok(text: String) = ToolResult(text)

    /** 任务摘要（会话上下文与 list_tasks 工具共用） */
    fun tasksSummary(): String {
        val tasks = engine().tasksSnapshot()
        val arr = tasks.joinToString(",\n") { t ->
            """{"id":${t.id},"name":${json.encodeToString(t.name)},"tag":${t.tag?.let { json.encodeToString(it) } ?: "null"},""" +
                """"enabled":${t.enabled},"mode":"${t.mode}","priority":${t.priority},"loop":${t.loop},""" +
                """"keywords":${json.encodeToString(t.trigger.keywords)},"region":${t.trigger.region != null},"steps":${t.steps.size}}"""
        }
        return "[\n$arr\n]"
    }

    /** 引擎状态（会话上下文与 get_engine_status 工具共用） */
    fun engineStatus(): String {
        val tasks = engine().tasksSnapshot()
        val enabled = tasks.filter { it.enabled }
        return buildString {
            append("""{"running":${engine().isMonitoring}""")
            append(""","currentTask":${engine().currentTaskName?.let { json.encodeToString(it) } ?: "null"}""")
            append(""","taskCount":${tasks.size},"enabledCount":${enabled.size}""")
            append(""","enabledTasks":[${enabled.joinToString(",") { json.encodeToString(it.name) }}]}""")
        }
    }

    private suspend fun describeScreen(): ToolResult {
        val frame = MonitoringEngine.frameProvider?.invoke()
            ?: return ToolResult("""{"error": "屏幕采集未开启或暂无帧"}""")
        return try {
            val text = OcrRecognizer.recognize(frame, null)
            if (text.isNullOrBlank()) ToolResult("""{"screen_text": "", "note": "屏幕上未识别到文字"}""")
            else ToolResult(json.encodeToString(mapOf("screen_text" to text)))
        } finally {
            frame.recycle()
        }
    }

    /** 校验并落库一个任务。id<=0 或不存在视为新建（自动分配 id 并默认 debug 模式）；已存在则覆盖（保留启用开关） */
    private fun upsertTask(args: JsonObject): ToolResult {
        val taskJson = args["task"]?.jsonObject
            ?: return ToolResult("""{"error": "缺少 task 字段"}""")
        val parsed = json.decodeFromJsonElement(Task.serializer(), taskJson)
        require(parsed.name.isNotBlank()) { "任务名不能为空" }
        parsed.steps.forEach { step ->
            when (step) {
                is Step.Click -> require(step.pressMs >= 0 && step.delayAfterMs >= 0)
                is Step.Swipe -> require(step.durationMs > 0)
                is Step.Wait -> require(step.ms >= 0)
                is Step.EnableTagged -> require(step.tag.isNotBlank())
                is Step.DisableTagged -> require(step.tag.isNotBlank())
            }
        }
        var saved = parsed
        engine().updateTasks { list ->
            val existing = list.firstOrNull { it.id == parsed.id }
            saved = if (existing == null) {
                val isNew = parsed.id <= 0
                parsed.copy(
                    id = if (isNew) TaskStore.nextId(list) else parsed.id,
                    mode = if (isNew) Task.MODE_DEBUG else parsed.mode,
                )
            } else {
                parsed.copy(id = existing.id, enabled = existing.enabled)
            }
            list.removeAll { it.id == saved.id }
            list.add(saved)
        }
        return ok(
            """{"ok": true, "id": ${saved.id}, "mode": "${saved.mode}", """ +
                """"note": "新建任务默认 debug 模式，试跑稳定后用 set_task_mode 转正"}"""
        )
    }

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IllegalArgumentException("缺少数值参数 $key")

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 $key")

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("缺少布尔参数 $key")
}
