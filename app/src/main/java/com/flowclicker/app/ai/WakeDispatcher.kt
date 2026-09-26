package com.flowclicker.app.ai

import android.os.SystemClock
import android.util.Log
import com.flowclicker.app.engine.EngineEvent.TaskFired
import com.flowclicker.app.engine.EngineEvent.TaskFinished
import com.flowclicker.app.engine.MonitoringEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 唤醒调度器：收集引擎/录制/试运行/debug 事件，按唤醒规则触发 AI 会话。
 * - 单飞：同一时刻最多一个会话（Mutex），事件排队上限 5；
 * - idle 看门狗：引擎运行但持续无任务触发超时 → 唤醒；
 * - stall 看门狗：任务执行完后超时无任何其他任务接续 → 唤醒；
 * - debug 轮次：debug 任务跑满 settings.debugRounds 轮 → 带截图唤醒复盘。
 */
object WakeDispatcher {

    private const val TAG = "WakeDispatcher"

    data class WakeEvent(val type: String, val detail: String, val taskId: Long? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val pending = Channel<WakeEvent>(Channel.UNLIMITED)

    @Volatile
    private var lastActivity = SystemClock.elapsedRealtime()
    private val stallJobs = mutableMapOf<Long, Job>()
    private val debugRounds = mutableMapOf<Long, Int>()

    fun init() {
        MonitoringEngine.eventListener = { onEngineEvent(it) }
        scope.launch { idleWatchLoop() }
    }

    fun onEngineEvent(e: com.flowclicker.app.engine.EngineEvent) {
        when (e) {
            is TaskFired -> {
                lastActivity = SystemClock.elapsedRealtime()
                stallJobs.values.forEach { it.cancel() }
                stallJobs.clear()
            }

            is TaskFinished -> {
                lastActivity = SystemClock.elapsedRealtime()
                if (e.manual) {
                    enqueue(
                        WakeEvent(
                            "test_run_finished",
                            "任务「${e.name}」试运行结束：执行 ${e.stepsRan} 步，正常完成=${e.completed}。" +
                                    "请 get_debug_captures(taskId=${e.taskId}) 复盘截图后再决定转正或修改",
                            e.taskId
                        )
                    )
                    return
                }
                scheduleStallWatch(e)
                handleDebugRounds(e)
            }
        }
    }

    /** 用户/其他模块完成一次录制 */
    fun onRecordingFinished(stepCount: Int) {
        enqueue(
            WakeEvent(
                "recording_finished",
                "完成了一次 $stepCount 步的操作录制。请 get_last_recording 读取手势序列，" +
                        "结合上下文整理为正式任务（补充触发词/标签/兜底规则，默认 debug 模式）"
            )
        )
    }

    /** 用户在 AI 界面手动提问/下达指令；AI 未启用或未配置时返回 false */
    fun manual(text: String): Boolean {
        val s = AiStores.loadSettings()
        if (!s.enabled || s.baseUrl.isBlank() || s.model.isBlank()) return false
        enqueue(WakeEvent("manual", "用户指令：$text"))
        return true
    }

    private suspend fun idleWatchLoop() {
        while (true) {
            delay(5_000)
            runCatching {
                val settings = AiStores.loadSettings()
                if (!settings.enabled) return@runCatching
                val idleRules = AiStores.loadRules().filter { it.type == "idle" }
                if (idleRules.isEmpty() || !MonitoringEngine.isMonitoring) return@runCatching
                if (MonitoringEngine.tasksSnapshot().none { it.enabled }) return@runCatching
                val timeout = idleRules.minOf { it.timeoutMs }.coerceAtLeast(10_000)
                if (SystemClock.elapsedRealtime() - lastActivity > timeout) {
                    lastActivity = SystemClock.elapsedRealtime()
                    enqueue(
                        WakeEvent(
                            "idle",
                            "引擎运行中已超过 ${timeout / 1000} 秒没有任何任务触发（idle 看门狗）。" +
                                    "请 describe_screen 或 get_screenshot 查看当前画面，判断是流程卡住、被踢下线还是任务已失效"
                        )
                    )
                }
            }.onFailure { Log.w(TAG, "idle watch error", it) }
        }
    }

    private fun scheduleStallWatch(e: TaskFinished) {
        val rule = AiStores.loadRules().firstOrNull { it.type == "stall" && it.taskId == e.taskId } ?: return
        stallJobs.remove(e.taskId)?.cancel()
        stallJobs[e.taskId] = scope.launch {
            delay(rule.timeoutMs)
            stallJobs.remove(e.taskId)
            if (MonitoringEngine.isMonitoring) {
                enqueue(
                    WakeEvent(
                        "stall",
                        "任务「${e.name}」执行完后已 ${rule.timeoutMs / 1000} 秒没有任何其他任务接续触发（stall 看门狗）。" +
                                "请检查后续流程是否卡住"
                    ),
                )
            }
        }
    }

    private fun handleDebugRounds(e: TaskFinished) {
        if (!e.debugMode || !e.completed) return
        val rounds = (debugRounds[e.taskId] ?: 0) + 1
        debugRounds[e.taskId] = rounds
        val need = AiStores.loadSettings().debugRounds.coerceAtLeast(1)
        if (rounds >= need) {
            debugRounds.remove(e.taskId)
            enqueue(
                WakeEvent(
                    "debug_review",
                    "debug 任务「${e.name}」已跑满 $need 轮。请 get_debug_captures(taskId=${e.taskId}) " +
                            "复盘每步截图，确认无误后 set_task_mode 转 normal；有问题则 upsert_task 修改后继续试跑"
                )
            )
        }
    }

    private fun enqueue(ev: WakeEvent) {
        val settings = AiStores.loadSettings()
        if (!settings.enabled || settings.baseUrl.isBlank() || settings.model.isBlank()) {
            Log.i(TAG, "AI 未启用，忽略事件: ${ev.type}")
            return
        }
        Log.i(TAG, "wake event: ${ev.type} ${ev.taskId ?: ""}")
        scope.launch {
            pending.send(ev)
            mutex.withLock {
                // drain：把排队事件逐个跑掉
                while (true) {
                    val ev2 = pending.tryReceive().getOrNull() ?: break
                    runCatching { AiSession.run(ev2.type, ev2.detail, AiStores.loadSettings()) }
                        .onSuccess { AiStores.appendLog(it) }
                        .onFailure {
                            Log.w(TAG, "ai session failed", it)
                            AiStores.appendLog(
                                AiLogEntry(
                                    System.currentTimeMillis(), ev2.type, ev2.detail, 0,
                                    "会话失败：${it.message ?: it.javaClass.simpleName}"
                                )
                            )
                        }
                }
            }
        }
    }
}
