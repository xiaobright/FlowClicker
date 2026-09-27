package com.flowclicker.app.ai

import android.graphics.Bitmap
import com.flowclicker.app.core.GestureDispatcher
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.OcrRecognizer
import com.flowclicker.app.engine.Region
import com.flowclicker.app.engine.Step
import com.flowclicker.app.engine.Task
import com.flowclicker.app.engine.TaskStore
import com.flowclicker.app.service.RecordingService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import com.flowclicker.app.engine.TaskValidation
import com.flowclicker.app.core.ScreenControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * AI 调度员的内部工具集：观察引擎与任务、修改编排、试运行、看图诊断、精确定位校准、直接操作屏幕。
 * 每个工具入参为模型给出的 JsonObject，出参为回填会话的文本（可附带截图）。
 */
object AiTools {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    data class ToolResult(val text: String, val images: List<String> = emptyList())

    /** confirm_target 的上一次裁剪上下文（会话单飞串行，全局暂存一份即可） */
    private data class CropContext(val left: Int, val top: Int, val zoom: Float, val at: Long)

    @Volatile
    private var lastCrop: CropContext? = null
    fun clearSession() { lastCrop = null }

    /** set_task_mode 转正时通知会话（AiSession 据此生成给下次自己的交接笔记） */
    @Volatile
    var onTaskPromoted: ((Long) -> Unit)? = null

    suspend fun execute(name: String, args: JsonObject, settings: AiSettings): ToolResult = runCatching {
        currentCoroutineContext().ensureActive()
        when (name) {
            "list_tasks" -> ok(tasksSummary())
            "get_task" -> {
                val t = engine().tasksSnapshot().firstOrNull { it.id == args.long("id") }
                    ?: return ToolResult("""{"error": "任务不存在"}""")
                val note = AiStores.loadTaskNote(t.id)
                ok("""{"task":${json.encodeToString(t)},"note":${json.encodeToString(note?.text)}}""")
            }

            "upsert_task" -> upsertTask(args)
            "delete_task" -> {
                val id = args.long("id")
                require(engine().tasksSnapshot().any { it.id == id }) { "任务不存在" }
                engine().updateTasks { it.removeAll { t -> t.id == id } }
                AiStores.deleteTaskData(id)
                ok("""{"deleted": $id}""")
            }

            "set_task_enabled" -> {
                val id = args.long("id")
                val en = args.bool("enabled")
                engine().updateTasks { list ->
                    val i = list.indexOfFirst { it.id == id }
                    require(i >= 0) { "任务不存在" }
                    list[i] = list[i].copy(enabled = en)
                }
                ok("""{"id": $id, "enabled": $en}""")
            }

            "set_task_mode" -> {
                val id = args.long("id")
                val mode = args.str("mode")
                require(mode == Task.MODE_NORMAL || mode == Task.MODE_DEBUG) { "mode 只能是 normal/debug" }
                if (mode == Task.MODE_NORMAL) engine().promote(id) else engine().updateTasks { list ->
                    val i = list.indexOfFirst { it.id == id }
                    require(i >= 0) { "任务不存在" }
                    list[i] = list[i].copy(mode = mode)
                }
                if (mode == Task.MODE_NORMAL) onTaskPromoted?.invoke(id)
                ok("""{"id": $id, "mode": "$mode"}""")
            }

            "get_wake_rules" -> ok(json.encodeToString(AiStores.loadRules()))
            "set_wake_rules" -> {
                val rules = json.decodeFromString<List<WakeRule>>(args["rules"]!!.toString())
                require(rules.size <= 10) { "唤醒规则最多 10 条" }
                require(rules.distinctBy { it.type to it.taskId }.size == rules.size) { "规则重复" }
                rules.forEach {
                    require(it.type in setOf("idle", "stall") && it.timeoutMs in 10000..3600000) { "规则类型或超时无效" }
                    require(it.type != "stall" || it.taskId != null) { "stall 必须指定 taskId" }
                    require(it.taskId == null || engine().tasksSnapshot().any { t -> t.id == it.taskId }) { "规则任务不存在" }
                }
                AiStores.saveRules(rules)
                ok("""{"saved": ${rules.size}}""")
            }

            "get_engine_status" -> ok(engineStatus())
            "get_run_result" -> ok(json.encodeToString(engine().lastResult(args.long("taskId"))))
            "describe_screen" -> describeScreen()
            "get_screenshot" -> {
                if (!settings.vlm) {
                    ToolResult("""{"error": "当前模型未启用图像输入，请改用 describe_screen"}""")
                } else {
                    val frame = MonitoringEngine.frameProvider?.invoke()
                        ?: return ToolResult("""{"error": "屏幕采集未开启或暂无帧"}""")
                    try {
                        ToolResult(
                            """{"screen":${geometry(frame)}, "note":"附图可能缩放；操作使用原始屏幕坐标，图标请 confirm_target 校准"}""",
                            listOf(AiClient.bitmapToDataUrl(frame))
                        )
                    } finally {
                        frame.recycle()
                    }
                }
            }

            "locate_text" -> locateText(args)
            "confirm_target" -> confirmTarget(args, settings)
            "confirm_region" -> confirmRegion(args, settings)

            "tap_screen" -> {
                val x = args.float("x")
                val y = args.float("y")
                requireScreenPoint(x, y)
                val dur = (args["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 60L)
                    .coerceIn(20L, 2000L)
                check(GestureDispatcher.tap(x, y, dur)) { "点击未派发或已取消" }
                ok("""{"ok": "已在(${x.toInt()}, ${y.toInt()})点击，稍后可用 describe_screen/get_screenshot 验证效果"}""")
            }

            "swipe_screen" -> {
                val dur = (args["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L)
                    .coerceIn(50L, 5000L)
                requireScreenPoint(args.float("x1"), args.float("y1"))
                requireScreenPoint(args.float("x2"), args.float("y2"))
                check(GestureDispatcher.swipe(
                    args.float("x1"), args.float("y1"),
                    args.float("x2"), args.float("y2"), dur
                )) { "滑动未派发或已取消" }
                ok("""{"ok": "已执行滑动"}""")
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
                            ?.let { try { AiClient.bitmapToDataUrl(it) } finally { it.recycle() } }
                    }
                    if (images.isEmpty()) {
                        ToolResult("""{"captures": [], "note": "该任务暂无 debug 截图"}""")
                    } else {
                        ToolResult(
                            """{"captures": ${json.encodeToString(files.map { it.name })}, "note": "旧→新；step-1 为操作后截图。请结合 get_run_result，截图本身不代表成功"}""",
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

            "save_memory" -> {
                val text = args.str("text").take(1000)
                AiStores.addMemory(text)
                ok("""{"ok": "已存入长期记忆（共 ${AiStores.loadMemory().size} 条），下次唤醒自动可见"}""")
            }

            "save_task_note" -> {
                val id = args.long("taskId")
                val text = args.str("text").take(1500)
                AiStores.saveTaskNote(TaskNote(id, text, System.currentTimeMillis()))
                ok("""{"ok": "已保存任务#$id 的绑定笔记，唤醒该任务相关事件时自动带上"}""")
            }

            "log_note" -> {
                val text = args.str("text")
                android.util.Log.i("AiScheduler", "note: $text")
                AiStores.appendLog(AiLogEntry(System.currentTimeMillis(), "note", "", 0, text.take(1500)))
                ok("""{"ok": "已留言"}""")
            }

            else -> ToolResult(json.encodeToString(mapOf("error" to "未知工具 $name")))
        }
    }.getOrElse {
        if (it is CancellationException) throw it
        ToolResult("""{"error": ${json.encodeToString(it.message ?: it.javaClass.simpleName)}}""")
    }

    private fun engine() = MonitoringEngine

    private fun ok(text: String) = ToolResult(text)

    private fun geometry(frame: Bitmap): String {
        val scale = minOf(1f, 1120f / maxOf(frame.width, frame.height))
        return """{"width":${frame.width},"height":${frame.height},"imageWidth":${(frame.width * scale).toInt()},"imageHeight":${(frame.height * scale).toInt()},"scale":$scale}"""
    }

    private fun requireScreenPoint(x: Float, y: Float) {
        check(ScreenControl.owner == "ai") { "AI 尚未取得屏幕控制权" }
        val frame = MonitoringEngine.frameProvider?.invoke() ?: error("屏幕采集不可用")
        try {
            require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < frame.width && y < frame.height) { "坐标超出屏幕" }
        } finally { frame.recycle() }
    }

    /** OCR 找文字并返回精确屏幕坐标框 —— 文字类目标的定位首选（比 VLM 直读坐标准得多） */
    private suspend fun locateText(args: JsonObject): ToolResult {
        val needle = args.str("text").replace(" ", "")
        val region = args["region"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.let { json.decodeFromJsonElement(Region.serializer(), it) }
        val frame = MonitoringEngine.frameProvider?.invoke()
            ?: return ToolResult("""{"error": "屏幕采集未开启或暂无帧"}""")
        return try {
            val hits = OcrRecognizer.recognizeBoxes(frame, region)
                .filter { it.text.replace(" ", "").contains(needle, ignoreCase = true) }
            if (hits.isEmpty()) {
                ToolResult("""{"hits": [], "note": ${json.encodeToString("未找到「$needle」，请重新观察")}}""")
            } else {
                val arr = hits.joinToString(",") { b ->
                    """{"text":${json.encodeToString(b.text)},""" +
                        """"box":{"left":${b.left},"top":${b.top},"right":${b.right},"bottom":${b.bottom}},""" +
                        """"center":{"x":${b.centerX},"y":${b.centerY}}}"""
                }
                ToolResult("""{"hits": [$arr], "note": "坐标为屏幕物理像素，可直接写入 Click 或用 confirm_target 复核"}""")
            }
        } finally {
            frame.recycle()
        }
    }

    /**
     * 两步定位校准（实验结论：裸 VLM 坐标 Y 轴系统性偏差 ~11%，放大+局部坐标+端上换算可到 ~12px 误差）：
     * 第一步 {x,y}：以预估坐标为中心裁剪放大返回，模型看图核对；
     * 第二步 {local_x,local_y}：模型给图内局部坐标，端上换算 原图 = 裁剪原点 + 局部/zoom。
     */
    private suspend fun confirmTarget(args: JsonObject, settings: AiSettings): ToolResult {
        if (!settings.vlm) return ToolResult("""{"error": "当前模型未启用图像输入"}""")
        val frame = MonitoringEngine.frameProvider?.invoke()
            ?: return ToolResult("""{"error": "屏幕采集未开启或暂无帧"}""")
        return try {
            val lx = args["local_x"]?.jsonPrimitive?.content?.toFloatOrNull()
            val ly = args["local_y"]?.jsonPrimitive?.content?.toFloatOrNull()
            if (lx != null && ly != null) {
                val c = lastCrop
                    ?: return ToolResult("""{"error": "没有待换算的裁剪上下文，请先调用 confirm_target {x,y}"}""")
                if (System.currentTimeMillis() - c.at > 120_000L) {
                    return ToolResult("""{"error": "裁剪上下文已过期，请重新 confirm_target {x,y}"}""")
                }
                require(lx.isFinite() && ly.isFinite() && lx in 0f..1120f && ly in 0f..1120f) { "局部坐标无效" }
                val sx = (c.left + lx / c.zoom).toInt().coerceIn(0, frame.width - 1)
                val sy = (c.top + ly / c.zoom).toInt().coerceIn(0, frame.height - 1)
                ok(
                    """{"ok": true, "x": $sx, "y": $sy, """ +
                        """"note": "局部坐标(${"%.0f".format(Locale.US, lx)},${"%.0f".format(Locale.US, ly)})已换算为屏幕坐标($sx,$sy)，""" +
                        """可直接写入 Click 步骤；也可 tap_screen 验证"}"""
                )
            } else {
                val x = args.float("x")
                val y = args.float("y")
                val size = (args["size"]?.jsonPrimitive?.content?.toIntOrNull() ?: 400).coerceIn(160, 1200)
                val half = size / 2f
                val left = (x - half).toInt().coerceIn(0, frame.width - 2)
                val top = (y - half).toInt().coerceIn(0, frame.height - 2)
                val w = minOf(size, frame.width - left).coerceAtLeast(1)
                val h = minOf(size, frame.height - top).coerceAtLeast(1)
                val zoom = 1120f / maxOf(w, h)
                val src = Bitmap.createBitmap(frame, left, top, w, h)
                val big = Bitmap.createScaledBitmap(
                    src, (w * zoom).toInt().coerceAtLeast(1), (h * zoom).toInt().coerceAtLeast(1), true
                )
                if (big !== src) src.recycle()
                lastCrop = CropContext(left, top, zoom, System.currentTimeMillis())
                try {
                    ToolResult(
                        """{"crop_origin": {"left": $left, "top": $top}, "crop_size": {"w": $w, "h": $h}, """ +
                            """"zoom": ${"%.2f".format(Locale.US, zoom)}, """ +
                            """"note": "附图是以(${"%.0f".format(Locale.US, x)},${"%.0f".format(Locale.US, y)})为中心的放大裁剪，""" +
                            """覆盖原图 [left=$left, top=$top, right=${left + w}, bottom=${top + h}]。""" +
                            """请核对目标是否完整可见且预估点是否落在目标上：""" +
                            """目标在图内 → 再次调用 confirm_target 传 {local_x, local_y}（图内像素坐标，原点=图片左上角），我换算回屏幕精确坐标；""" +
                            """目标不在图内 → 换更大的 size 或先 locate_text/get_screenshot 重新估"}""",
                        listOf(AiClient.bitmapToDataUrl(big, maxEdge = 1400))
                    )
                } finally {
                    big.recycle()
                }
            }
        } finally {
            frame.recycle()
        }
    }

    /** 区域框选自查：裁剪该区域（外扩 15% 上下文）返回，模型核对覆盖范围是否合适 */
    private suspend fun confirmRegion(args: JsonObject, settings: AiSettings): ToolResult {
        if (!settings.vlm) return ToolResult("""{"error": "当前模型未启用图像输入"}""")
        val frame = MonitoringEngine.frameProvider?.invoke()
            ?: return ToolResult("""{"error": "屏幕采集未开启或暂无帧"}""")
        return try {
            val l = args.float("l").toInt()
            val t = args.float("t").toInt()
            val r = args.float("r").toInt()
            val b = args.float("b").toInt()
            require(r > l && b > t) { "region 无效：right/bottom 必须大于 left/top" }
            val padX = (((r - l) * 0.15f).toInt()).coerceAtLeast(20)
            val padY = (((b - t) * 0.15f).toInt()).coerceAtLeast(20)
            val left = (l - padX).coerceIn(0, frame.width - 2)
            val top = (t - padY).coerceIn(0, frame.height - 2)
            val right = (r + padX).coerceIn(left + 1, frame.width)
            val bottom = (b + padY).coerceIn(top + 1, frame.height)
            val w = right - left
            val h = bottom - top
            val src = Bitmap.createBitmap(frame, left, top, w, h)
            val scale = minOf(1f, 800f / maxOf(w, h))
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
            } else src
            if (out !== src) src.recycle()
            try {
                ToolResult(
                    """{"requested": {"left": $l, "top": $t, "right": $r, "bottom": $b}, """ +
                        """"crop_with_context": {"left": $left, "top": $top, "right": $right, "bottom": $bottom}, """ +
                        """"note": "附图为该区域外扩15%后的截图。请自查目标是否完整落在 requested 区域内且留白适度""" +
                        """（太大→OCR会命中无关文字，太小→界面微动就漏检）。需调整则再次调用 confirm_region；""" +
                        """满意则直接使用 requested 坐标"}""",
                    listOf(AiClient.bitmapToDataUrl(out, maxEdge = 900))
                )
            } finally {
                out.recycle()
            }
        } finally {
            frame.recycle()
        }
    }

    /** 任务摘要（会话上下文与 list_tasks 工具共用） */
    fun tasksSummary(): String {
        val tasks = engine().tasksSnapshot()
        val arr = tasks.joinToString(",\n") { t ->
            """{"id":${t.id},"name":${json.encodeToString(t.name)},"tag":${t.tag?.let { json.encodeToString(it) } ?: "null"},""" +
                """"enabled":${t.enabled},"mode":"${t.mode}","revision":${t.revision},"priority":${t.priority},"loop":${t.loop},""" +
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
            append(""","pendingReview":${json.encodeToString(engine().pendingReviews())}""")
            append(""","screenOwner":${json.encodeToString(ScreenControl.owner)},"gesturesReady":${GestureDispatcher.isReady}""")
            append(""","enabledTasks":[${enabled.joinToString(",") { json.encodeToString(it.name) }}]}""")
        }
    }

    private suspend fun describeScreen(): ToolResult {
        val frame = MonitoringEngine.frameProvider?.invoke()
            ?: return ToolResult("""{"error": "屏幕采集未开启或暂无帧"}""")
        return try {
            val text = OcrRecognizer.recognize(frame, null)
            if (text == null) ToolResult("""{"error":"OCR失败，不能认为屏幕没有文字"}""")
            else ToolResult("""{"screen_text":${json.encodeToString(text)},"screen":${geometry(frame)}}""")
        } finally {
            frame.recycle()
        }
    }

    /** 校验并落库一个任务。id<=0 或不存在视为新建（自动分配 id 并默认 debug 模式）；已存在则覆盖（保留启用开关） */
    private fun upsertTask(args: JsonObject): ToolResult {
        val taskJson = args["task"]?.jsonObject
            ?: return ToolResult("""{"error": "缺少 task 字段"}""")
        val parsed = json.decodeFromJsonElement(Task.serializer(), taskJson)
        TaskValidation.validate(parsed)
        var saved = parsed
        engine().updateTasks { list ->
            val existing = list.firstOrNull { it.id == parsed.id }
            saved = if (existing == null) {
                parsed.copy(
                    id = TaskStore.nextId(list),
                    mode = Task.MODE_DEBUG,
                )
            } else {
                require(parsed.revision == existing.revision) { "任务版本冲突，请重新 get_task" }
                parsed.copy(id = existing.id, enabled = existing.enabled, mode = Task.MODE_DEBUG)
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

    private fun JsonObject.float(key: String): Float =
        this[key]?.jsonPrimitive?.content?.toFloatOrNull()
            ?: throw IllegalArgumentException("缺少数值参数 $key")
}
