package com.flowclicker.app.ai

import com.flowclicker.app.ai.AiLogEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一次 AI 调度会话：唤醒事件 → JSON 工具调用循环 → 最终回复落日志 */
object AiSession {

    private val json = Json { ignoreUnknownKeys = true }

    data class Parsed(val reply: String?, val tool: String?, val args: JsonObject?)

    /** 从模型回复中提取 JSON 协议对象，容忍代码围栏与前后缀文本 */
    fun parseAssistant(text: String): Parsed? {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val body = Regex("\\{[\\s\\S]*\\}").find(cleaned)?.value ?: cleaned
        return runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            val reply = o["reply"]?.jsonPrimitive?.content
            val tool = o["tool"]?.jsonPrimitive?.content
            when {
                reply != null -> Parsed(reply = reply, tool = null, args = null)
                tool != null -> Parsed(
                    reply = null, tool = tool,
                    args = (o["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                )
                else -> null
            }
        }.getOrNull()
    }

    suspend fun run(eventType: String, eventDetail: String, settings: AiSettings): AiLogEntry {
        val client = AiClient(settings)
        val context = buildString {
            appendLine("【唤醒事件】[$eventType] $eventDetail")
            appendLine()
            appendLine("【当前任务列表】")
            appendLine(AiTools.tasksSummary())
            appendLine("【引擎状态】")
            appendLine(AiTools.engineStatus())
        }
        val messages = mutableListOf(
            client.textMessage("system", systemPrompt(settings)),
            client.textMessage("user", context),
        )
        var toolCalls = 0
        var reply = ""
        for (round in 0..settings.maxToolRounds) {
            val resp = client.chat(messages)
            val parsed = parseAssistant(resp)
            if (parsed == null) {
                if (round == settings.maxToolRounds) { reply = resp.take(600); break }
                messages += client.textMessage("assistant", resp)
                messages += client.textMessage(
                    "user",
                    """{"error": "你的回复不符合协议。只输出一个 JSON 对象：{"thought":"...","tool":"...","args":{...}} 或 {"reply":"..."}"}"""
                )
                continue
            }
            if (parsed.reply != null) { reply = parsed.reply; break }
            val tool = parsed.tool!!
            toolCalls++
            messages += client.textMessage("assistant", resp)
            val result = AiTools.execute(tool, parsed.args ?: JsonObject(emptyMap()), settings)
            val resultText = """{"tool_result": ${result.text}}"""
            messages += if (result.images.isEmpty()) {
                client.textMessage("user", resultText)
            } else {
                client.imageMessage("user", resultText, result.images)
            }
            if (round == settings.maxToolRounds) reply = "（达到最大工具轮数 ${settings.maxToolRounds}，会话结束）"
        }
        return AiLogEntry(
            time = System.currentTimeMillis(),
            eventType = eventType,
            detail = eventDetail.take(200),
            toolCalls = toolCalls,
            reply = reply.ifBlank { "（无回复）" },
        )
    }

    private fun systemPrompt(settings: AiSettings): String = buildString {
        appendLine("你是安卓自动挂机应用「流程点击器」内部的 AI 编排调度员。")
        appendLine("用户以模块化任务挂机：引擎循环截屏→OCR识字→关键词命中→执行动作序列；")
        appendLine("多个任务并行监测、互斥执行。你负责：编排任务、设置兜底唤醒规则、看图诊断、试运行验收。")
        appendLine("你平时不被调用；每次被唤醒都对应一个具体事件，请在有限轮次内完成处置并用 reply 收尾。")
        appendLine()
        appendLine("== 响应协议（严格遵守）==")
        appendLine("""每次只输出一个 JSON 对象，不要输出任何其他文字：""")
        appendLine("""调用工具：{"thought":"简短理由","tool":"工具名","args":{...}}""")
        appendLine("""结束会话：{"reply":"给用户的中文总结"}""")
        appendLine()
        appendLine("== 工具清单 ==")
        appendLine("- list_tasks {} 所有任务摘要")
        appendLine("- get_task {id}")
        appendLine("- upsert_task {task} 新建(id<=0或不存在,自动分配id且默认mode=debug)或整体覆盖已有任务(保留enabled)")
        appendLine("- delete_task {id}")
        appendLine("- set_task_enabled {id,enabled}")
        appendLine("- set_task_mode {id,mode} mode=normal|debug")
        appendLine("- get_wake_rules {} / set_wake_rules {rules} 兜底唤醒规则，全量读写")
        appendLine("- get_engine_status {}")
        appendLine("- describe_screen {} OCR 全屏文本（便宜，先用它了解画面）")
        appendLine("- get_screenshot {} 下一轮附当前截图（仅VLM模型可用）")
        appendLine("- get_debug_captures {taskId,count} 附该任务最近的点击前截图")
        appendLine("- run_task_now {taskId} 试运行一次（需引擎运行中），结束后会以 test_run_finished 事件再次唤醒你")
        appendLine("- start_engine {} / stop_engine {}")
        appendLine("- get_last_recording {} 最近一次录制的手势序列")
        appendLine("- log_note {text} 给用户留言")
        appendLine()
        appendLine("== Task JSON Schema ==")
        appendLine(
            """{"id":0,"name":"重新登录","priority":0,"tag":null(""" +
                """"字符串标签,被启用/停用标签组批量控制"),"trigger":{"region":null,"keywords":["重新登录"],"ignoreCase":true},""" +
                """"steps":[{"type":"click","x":540,"y":1200,"maxOffsetPx":8,"pressMs":60,"pressJitterMs":20,"delayAfterMs":800,"delayJitterMs":300},""" +
                """{"type":"swipe","x1":540,"y1":1600,"x2":540,"y2":1000,"durationMs":400,"durationJitterMs":60,"delayAfterMs":1000,"delayJitterMs":200},""" +
                """{"type":"wait","ms":2000},""" +
                """{"type":"enable_tagged","tag":"登录后"},""" +
                """{"type":"disable_tagged","tag":"登录后"}],"enabled":true,"loop":true,"mode":"debug"}"""
        )
        appendLine("坐标为屏幕物理像素(本机1080x2340)。region格式：{\"left\":0,\"top\":0,\"right\":1080,\"bottom\":600}，null=全屏。")
        appendLine()
        appendLine("== 唤醒规则 schema ==")
        appendLine("""[{"type":"idle","timeoutMs":60000,"taskId":null},{"type":"stall","taskId":3,"timeoutMs":30000}]""")
        appendLine("idle=引擎运行但持续无任何任务触发(可省taskId表示全局)；stall=该任务执行完后超时无任何其他任务接续。")
        appendLine()
        appendLine("== 工作守则 ==")
        appendLine("1. 先观察再动手：不确定屏幕状态先 describe_screen，需要视觉确认才用 get_screenshot。")
        appendLine("2. 修改已有任务前必须先 get_task 拿到最新定义，upsert_task 是全量覆盖。")
        appendLine("3. 新建任务/大改动后：确保 mode=debug，并按需补充 idle/stall 兜底规则，然后 run_task_now 试运行；")
        appendLine("   收到 test_run_finished 后用 get_debug_captures 复盘，确认稳定再 set_task_mode normal 降低消耗。")
        appendLine("4. 触发词选屏幕上独有且稳定的短词（避免聊天/弹窗误触发），能确定位置就给 region。")
        appendLine("5. 步骤间保留合理的 delayAfterMs 并带 jitter（拟人化）。")
        appendLine("6. 引擎未运行时 start_engine；全部任务停用后引擎会自动休眠，属正常。")
        appendLine("7. 每次会话轮数上限 ${settings.maxToolRounds}，规划好再调用；处理完务必 reply 总结。")
        appendLine("8. 录制事件：用 get_last_recording 读取手势序列，结合上下文整理为正式任务（补触发词与兜底规则）。")
        appendLine()
        appendLine("当前 debugRounds 设置：${settings.debugRounds}（debug 任务跑满该轮数会自动唤醒你复盘）。")
    }
}
