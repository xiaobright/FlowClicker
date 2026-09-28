package com.flowclicker.app.ai

import android.os.SystemClock
import android.util.Log
import com.flowclicker.app.core.ScreenControl
import com.flowclicker.app.engine.EngineEvent
import com.flowclicker.app.engine.MonitoringEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One consumer, bounded/coalesced events, and explicit cancellation of an AI generation. */
object WakeDispatcher {
    data class WakeEvent(
        val type: String, val detail: String, val taskId: Long? = null,
        val createdAt: Long = SystemClock.elapsedRealtime(), val epoch: Long = 0,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guard = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val pending = WakeQueue<WakeEvent>(5) { "${it.epoch}:${it.type}:${it.taskId}:${if (it.type == "manual") it.detail else ""}" }
    private var active: Job? = null
    private val gate = AutomationGate()
    private val epoch get() = gate.epoch
    @Volatile var status = "空闲"
        private set
    private var lastActivity = SystemClock.elapsedRealtime()
    private val taskActivity = mutableMapOf<Long, Long>()
    private val rounds = mutableMapOf<Long, Pair<Long, Int>>()
    private val stalls = StallWatch()

    fun init() {
        MonitoringEngine.eventListener = ::onEngineEvent
        scope.launch {
            for (ignored in signal) {
                while (true) {
                    val ev = pending.poll() ?: break
                    try {
                        val job = synchronized(guard) {
                            if (!allowed(ev.epoch) || SystemClock.elapsedRealtime() - ev.createdAt > 120000) null
                            else scope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    status = "等待屏幕控制权"
                                    val result = ScreenControl.withOwner("ai") {
                                        checkAllowed(ev.epoch)
                                        if (SystemClock.elapsedRealtime() - ev.createdAt > 120000) throw CancellationException("事件已过期")
                                        status = "处理 ${ev.type}"
                                        withTimeout(300000) {
                                            AiSession.run(ev.type, ev.detail, AiStores.loadSettings(), ev.taskId) { checkAllowed(ev.epoch) }
                                        }
                                    }
                                    AiStores.appendLog(result)
                                } catch (e: CancellationException) {
                                    Log.i("WakeDispatcher", "session cancelled")
                                    throw e
                                } catch (e: Exception) {
                                    AiStores.appendLog(AiLogEntry(System.currentTimeMillis(), ev.type, ev.detail.take(200), 0,
                                        "会话失败，未重放工具：${e.message}"))
                                } finally { status = "空闲" }
                            }.also { active = it; it.start() }
                        }
                        job?.join()
                    } finally { pending.finish(ev) }
                }
            }
        }
        scope.launch { watchLoop() }
    }

    private fun allowed(token: Long): Boolean = gate.permits(token, AiStores.loadSettings().enabled)
    fun checkAllowed(token: Long) { if (!allowed(token)) throw CancellationException("AI 已停用或本轮已取消") }

    fun cancelAi() = synchronized(guard) {
        gate.stop()
        pending.clear()
        active?.cancel()
        active = null
        stalls.clear()
        rounds.clear()
        status = "已停止"
    }

    fun stopAll() {
        cancelAi()
        MonitoringEngine.stop()
        ScreenControl.cancelActive()
    }

    fun onSettingsChanged() {
        if (!AiStores.loadSettings().enabled) cancelAi() else resumeAutomation()
    }

    fun resumeAutomation() = synchronized(guard) {
        gate.resume()
        lastActivity = SystemClock.elapsedRealtime()
        taskActivity.clear()
    }

    fun manual(text: String): Boolean {
        resumeAutomation()
        return enqueue("manual", "用户指令：$text")
    }

    fun currentToken(): Long = epoch
    fun onRecordingFinished(stepCount: Int, token: Long) {
        enqueue("recording_finished", "完成 $stepCount 步录制。请 get_last_recording，补触发条件与 wait_text 结果验证后保存 debug 任务", sourceToken = token)
    }

    fun onEngineEvent(e: EngineEvent) {
        val now = SystemClock.elapsedRealtime()
        synchronized(guard) {
            if (gate.stopped || !MonitoringEngine.isCurrentGeneration(e.generation)) return
            when (e) {
                is EngineEvent.TaskFired -> {
                    lastActivity = now
                    taskActivity[e.taskId] = now
                    stalls.fired(e.taskId)
                }
                is EngineEvent.TaskFinished -> {
                    lastActivity = now
                    taskActivity[e.taskId] = now
                    if (e.result.reason == "已取消") return
                    if (e.manual || !e.completed) {
                        if (e.debugMode || !e.completed) MonitoringEngine.holdForReview(e.taskId)
                        enqueue(if (e.manual) "test_run_finished" else "task_failed",
                            "任务「${e.name}」执行 ${e.stepsRan} 步，完成=${e.completed}，结果验证=${e.result.verified}，原因=${e.result.reason}。请 get_run_result 和 get_debug_captures 复核", e.taskId)
                        return
                    }
                    AiStores.loadRules().firstOrNull { it.type == "stall" && it.taskId == e.taskId }?.let {
                        stalls.finished(e.taskId, now + it.timeoutMs.coerceIn(10000, 3600000))
                    }
                    if (e.debugMode) {
                        val prev = rounds[e.taskId]
                        val count = if (prev?.first == e.result.revision) prev.second + 1 else 1
                        rounds[e.taskId] = e.result.revision to count
                        if (count >= AiStores.loadSettings().debugRounds.coerceIn(1, 10)) {
                            MonitoringEngine.holdForReview(e.taskId)
                            rounds.remove(e.taskId)
                            enqueue("debug_review", "任务「${e.name}」已跑满 $count 轮，暂停自动重复，等待复盘。请读取执行结果和前后截图；必须有末步 wait_text 验证才可转正", e.taskId)
                        }
                    }
                }
            }
        }
    }

    private fun enqueue(type: String, detail: String, taskId: Long? = null, sourceToken: Long = epoch): Boolean = synchronized(guard) {
        val s = AiStores.loadSettings()
        if (!gate.permits(sourceToken, s.enabled) || s.baseUrl.isBlank() || s.model.isBlank()) return@synchronized false
        val ok = pending.offer(WakeEvent(type, detail, taskId, epoch = epoch))
        if (ok) signal.trySend(Unit) else Log.i("WakeDispatcher", "coalesced/full: $type/$taskId")
        ok
    }

    private suspend fun watchLoop() {
        var wasRunning = false
        while (true) {
            delay(1000)
            try {
                synchronized(guard) {
                    val now = SystemClock.elapsedRealtime()
                    val running = MonitoringEngine.isMonitoring
                    if (!running || !wasRunning) {
                        lastActivity = now; taskActivity.clear(); stalls.clear()
                    }
                    wasRunning = running
                    if (!running || !allowed(epoch)) return@synchronized
                    val enabled = MonitoringEngine.tasksSnapshot().filter { it.enabled }
                    if (ScreenControl.owner in setOf("ai", "recording", "preview")) {
                        lastActivity = now; taskActivity.clear(); stalls.clear()
                        return@synchronized
                    }
                    if (MonitoringEngine.currentTaskName != null) return@synchronized
                    for (rule in AiStores.loadRules().filter { it.type == "idle" }) {
                        if (enabled.none { rule.taskId == null || it.id == rule.taskId }) continue
                        val last = rule.taskId?.let { taskActivity.getOrPut(it) { now } } ?: lastActivity
                        if (now - last >= rule.timeoutMs.coerceIn(10000, 3600000)) {
                            enqueue("idle", "指定范围超过 ${rule.timeoutMs / 1000} 秒无活动，请检查画面", rule.taskId)
                            if (rule.taskId == null) lastActivity = now else taskActivity[rule.taskId] = now
                        }
                    }
                    stalls.due(now).filter { id -> enabled.any { it.id == id } }.forEach { id ->
                        enqueue("stall", "任务#$id 执行后没有其他任务接续；同任务重复不算接续", id)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("WakeDispatcher", "watch failed", e) }
        }
    }
}
