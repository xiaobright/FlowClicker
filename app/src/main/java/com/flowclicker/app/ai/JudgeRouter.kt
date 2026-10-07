package com.flowclicker.app.ai

import android.os.SystemClock
import com.flowclicker.app.engine.MonitoringEngine
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/** Optional slow-path routing. Null always preserves the original AI session. */
object JudgeRouter {
    private val lastAttempts = mutableMapOf<Long, Long>()

    suspend fun route(event: WakeDispatcher.WakeEvent, settings: AiSettings, authorize: () -> Unit): AiLogEntry? {
        val config = settings.judge
        if (!config.enabled || event.type !in setOf("idle", "stall") || !MonitoringEngine.isMonitoring) return null
        authorize()
        val tasks = MonitoringEngine.recoveryTasks()
        if (tasks.isEmpty() || tasks.size > 12) return null
        val candidates = tasks.map { JudgeCandidate(it.id, it.revision, it.name, it.recoveryHint) }
        val started = SystemClock.elapsedRealtime()
        var decision: JudgeDecision? = null
        fun log(outcome: String, handled: Boolean = false): AiLogEntry {
            val d = decision
            val detail = "${event.type}; model=${d?.model ?: config.model}; scene=${d?.scene ?: "unknown"}; " +
                "action=${d?.action ?: "none"}; confidence=${d?.confidence ?: 0.0}; " +
                "sufficient=${d?.sufficient ?: 0.0}; inputTokens=${d?.inputTokens ?: 0}; " +
                "elapsedMs=${SystemClock.elapsedRealtime() - started}"
            val entry = AiLogEntry(System.currentTimeMillis(), if (config.observeOnly) "judge_shadow" else "judge_route", detail, 0, outcome)
            if (!handled) AiStores.appendLog(entry)
            return entry
        }
        try {
            val observed = withTimeoutOrNull(12000) {
                val screen = readScreen() ?: return@withTimeoutOrNull null
                val result = event.taskId?.let { MonitoringEngine.lastResult(it) }
                val lastRun = result?.let { Json.parseToJsonElement(Json.encodeToString(it)).jsonObject } ?: JsonNull
                val body = JudgeProtocol.request(config, event.type, event.detail, screen, candidates, lastRun)
                screen to JudgeClient(config).evaluate(body, candidates)
            }
            currentCoroutineContext().ensureActive()
            authorize()
            if (AiStores.loadSettings().judge != config) { log("配置已变化，保留原 AI 流程"); return null }
            if (observed == null) { log("无有效文字或判断超时，转交 AI"); return null }
            decision = observed.second
            val selected = JudgeProtocol.selected(observed.second, config, candidates)
            if (config.observeOnly) { log("仅记录建议，不执行恢复；转交 AI"); return null }
            if (selected == null) { log("未明确命中恢复方案，转交 AI"); return null }
            val now = SystemClock.elapsedRealtime()
            lastAttempts.entries.removeAll { now - it.value >= 60000 }
            if (selected.id in lastAttempts) { log("同一恢复任务 60 秒内已尝试，转交 AI"); return null }
            val fresh = withTimeoutOrNull(3000) { readScreen() }
            currentCoroutineContext().ensureActive()
            authorize()
            if (fresh == null || fresh != observed.first) { log("画面已变化或不可用，放弃旧判断并转交 AI"); return null }
            val task = tasks.single { it.id == selected.id }
            // Recheck the settings before every step; even an edit without a full stop invalidates this decision.
            val checkCurrent = {
                authorize()
                check(AiStores.loadSettings().judge == config) { "判断配置已变化" }
            }
            checkCurrent()
            lastAttempts[selected.id] = now
            val result = MonitoringEngine.runRecovery(task, checkCurrent)
            currentCoroutineContext().ensureActive()
            authorize()
            return if (result.completed && result.verified) log("恢复任务 #${task.id} 已通过文字验证；runId=${result.runId}", handled = true)
            else { log("恢复任务 #${task.id} 未通过验证，转交 AI；runId=${result.runId}"); null }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Do not persist provider bodies, credentials, OCR, or arbitrary exception messages.
            currentCoroutineContext().ensureActive()
            authorize()
            val reason = if (e is AiHttpException) "HTTP ${e.status}" else e.javaClass.simpleName
            log("判断请求或恢复校验失败（$reason），转交 AI")
            return null
        }
    }

    internal suspend fun readScreen(): String? {
        val frame = MonitoringEngine.frameProvider?.invoke() ?: return null
        return try {
            MonitoringEngine.textRecognizer?.invoke(frame, null)?.replace(Regex("\\s+"), " ")?.trim()
                ?.takeIf { it.isNotBlank() && it.length <= 6000 }
        } finally { frame.recycle() }
    }
}
