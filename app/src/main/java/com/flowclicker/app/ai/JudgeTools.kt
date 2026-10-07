package com.flowclicker.app.ai

import com.flowclicker.app.engine.MonitoringEngine
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Read-only diagnostics; configuration and execution mode remain user-controlled. */
object JudgeTools {
    private var calls = 0
    fun resetBudget() { calls = 0 }

    fun status(): JsonObject {
        val s = AiStores.loadSettings().judge
        return buildJsonObject {
            put("enabled", s.enabled); put("observeOnly", s.observeOnly)
            put("baseUrl", s.baseUrl); put("model", s.model); put("hasApiKey", s.apiKey.isNotBlank())
            put("minConfidence", s.minConfidence); put("remainingChecks", (2 - calls).coerceAtLeast(0))
            putJsonArray("eligibleTaskIds") { MonitoringEngine.recoveryTasks().forEach { add(it.id) } }
        }
    }

    suspend fun test(): JsonObject = checkService {
        val candidate = JudgeCandidate(1, 0, "Retry connection", "Network connection is lost and a Retry button is visible.")
        val candidates = listOf(candidate)
        val s = AiStores.loadSettings().judge
        val request = JudgeProtocol.request(s, "self_test", "Read-only fixed protocol check",
            "Network connection lost. Retry", candidates, JsonNull)
        val d = JudgeClient(s).evaluate(request, candidates)
        buildJsonObject {
            put("protocolOk", true)
            put("sampleCorrect", d.scene == "connection_problem" && d.action == "task_1")
            put("model", d.model); put("inputTokens", d.inputTokens)
            put("note", "一次固定样本只验证连通/协议，不证明真实场景准确率；未执行任何动作")
        }
    }

    suspend fun screen(): JsonObject = checkService {
        val tasks = MonitoringEngine.tasksSnapshot().filter { it.enabled && it.recoveryHint.isNotBlank() }
        require(tasks.size in 1..12) { "需要 1–12 个填写恢复场景的已启用任务" }
        val candidates = tasks.map { JudgeCandidate(it.id, it.revision, it.name, it.recoveryHint) }
        val text = JudgeRouter.readScreen() ?: error("没有有效的屏幕文字")
        val s = AiStores.loadSettings().judge
        val d = JudgeClient(s).evaluate(JudgeProtocol.request(s, "preview", "Read-only recovery preview", text, candidates, JsonNull), candidates)
        buildJsonObject {
            put("executed", false); put("scene", d.scene); put("action", d.action)
            put("confidence", d.confidence); put("sufficient", d.sufficient)
            put("model", d.model); put("inputTokens", d.inputTokens)
            putJsonArray("eligibleTaskIds") { MonitoringEngine.recoveryTasks().forEach { add(it.id) } }
            put("note", "这是只读建议，可能包含尚未试跑的 debug 任务。执行需重新检查画面与验证资格。")
        }
    }

    private suspend fun checkService(block: suspend () -> JsonObject): JsonObject {
        if (!AiStores.loadSettings().judge.enabled) return errorResult("判断服务未启用，请由用户在设置中启用")
        if (calls >= 2) return errorResult("本会话判断检查已达 2 次上限")
        calls++
        return try {
            withTimeoutOrNull(12000) { block() } ?: errorResult("判断请求超时；未执行动作")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val reason = if (e is AiHttpException) "HTTP ${e.status}" else e.javaClass.simpleName
            errorResult("判断检查失败（$reason）；未执行动作")
        }
    }

    private fun errorResult(message: String) = buildJsonObject { put("error", message) }
}
