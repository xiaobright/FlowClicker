package com.flowclicker.app.engine

import android.graphics.Bitmap
import android.util.Log
import com.flowclicker.app.core.GestureDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** 引擎事件：供 AI 调度员（WakeDispatcher）等外部观察者消费 */
sealed interface EngineEvent {
    data class TaskFired(val taskId: Long, val name: String) : EngineEvent

    data class TaskFinished(
        val taskId: Long,
        val name: String,
        val stepsRan: Int,
        val completed: Boolean,
        val manual: Boolean,
        val debugMode: Boolean,
    ) : EngineEvent
}

/**
 * 监测引擎：实现"多任务并行监测、互斥执行"语义。
 *
 * - 单线程监测循环内按 priority（小者优先）逐帧评估所有启用任务；
 * - 任一任务条件满足 → 依次执行其动作序列，期间循环阻塞，其他任务不会触发；
 * - 序列执行完毕后回到监测循环，触发重新武装；
 * - 所有任务的文字识别共享同一帧截图，避免跨任务读到不同时刻的画面。
 */
object MonitoringEngine {

    private const val TAG = "MonitoringEngine"
    private const val POLL_INTERVAL_MS = 500L

    private var scope: CoroutineScope? = null
    private val tasks = CopyOnWriteArrayList<Task>()

    @Volatile
    var isMonitoring = false
        private set

    @Volatile
    var currentTaskName: String? = null
        private set

    /** 供各任务共享的最新屏幕帧，由录屏服务注入 */
    @Volatile
    var frameProvider: (() -> Bitmap?)? = null

    /**
     * OCR 钩子：由宿主注入（FlowClickerApp 接 ML Kit）。
     * 参数为共享帧与任务检测区域，返回区域内的识别文本；为 null 表示本帧不可用。
     */
    @Volatile
    var textRecognizer: (suspend (frame: Bitmap?, region: Region?) -> String?)? = null

    fun setTasks(list: List<Task>) {
        tasks.clear()
        tasks.addAll(list)
        notifyChanged()
    }

    /** 任务集合发生任何变化（用户开关、执行后停用、控制步骤）时回调，用于持久化与界面刷新 */
    var onChanged: ((List<Task>) -> Unit)? = null

    /** 引擎事件外发（触发/结束），WakeDispatcher 在此挂接唤醒规则 */
    @Volatile
    var eventListener: ((EngineEvent) -> Unit)? = null

    /** debug 模式：每个点击/滑动步骤执行前回调（内部读取共享帧留存截图） */
    @Volatile
    var stepCapture: ((taskId: Long, stepIndex: Int) -> Unit)? = null

    private val manualFire = AtomicReference<Task?>(null)

    /** 试运行：把任务插入下一次引擎循环（互斥语义不变）；引擎未运行时返回 false */
    fun requestRunNow(taskId: Long): Boolean {
        if (!isMonitoring) return false
        val t = tasks.firstOrNull { it.id == taskId } ?: return false
        return manualFire.compareAndSet(null, t)
    }

    fun tasksSnapshot(): List<Task> = tasks.toList()

    fun updateTasks(transform: (MutableList<Task>) -> Unit) {
        val list = tasks.toMutableList()
        transform(list)
        tasks.clear()
        tasks.addAll(list)
        notifyChanged()
    }

    private fun notifyChanged() {
        onChanged?.invoke(tasks.toList())
    }

    private fun setTagEnabled(tag: String, enabled: Boolean) {
        var changed = false
        for (i in tasks.indices) {
            val t = tasks[i]
            if (t.tag == tag && t.enabled != enabled) {
                tasks[i] = t.copy(enabled = enabled)
                changed = true
            }
        }
        if (changed) {
            Log.i(TAG, "tag '$tag' -> enabled=$enabled")
            notifyChanged()
        }
    }

    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch { monitorLoop() }
        Log.i(TAG, "monitoring started, tasks=${tasks.size}")
    }

    fun stop() {
        isMonitoring = false
        scope?.cancel()
        scope = null
        currentTaskName = null
        Log.i(TAG, "monitoring stopped")
    }

    private suspend fun monitorLoop() {
        while (isMonitoring) {
            val manual = manualFire.getAndSet(null)
            if (manual != null) {
                fireTask(manual, manual = true)
            } else {
                val fired = evaluateOnce()
                if (!fired) delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** @return 本轮是否触发了任务 */
    private suspend fun evaluateOnce(): Boolean {
        val active = tasks.filter { it.enabled }.sortedBy { it.priority }
        if (active.isEmpty()) {
            Log.i(TAG, "no enabled tasks left, engine stops")
            stop()
            return false
        }

        // 一轮评估只取一帧，所有任务基于同一时刻的屏幕状态决策
        val recognize = textRecognizer ?: return false
        val frame = frameProvider?.invoke()

        for (task in active) {
            if (!isMonitoring) return false
            val text = recognize(frame, task.trigger.region) ?: return false
            if (triggerMatched(text, task.trigger)) {
                fireTask(task, manual = false)
                return true
            }
        }
        return false
    }

    private fun triggerMatched(screenText: String, trigger: Trigger): Boolean {
        if (trigger.keywords.isEmpty()) return false
        val hay = if (trigger.ignoreCase) screenText.lowercase() else screenText
        return trigger.keywords.any { kw ->
            val needle = if (trigger.ignoreCase) kw.lowercase() else kw
            hay.contains(needle)
        }
    }

    private suspend fun fireTask(task: Task, manual: Boolean) {
        val debug = task.mode == Task.MODE_DEBUG
        Log.i(TAG, "task fired: ${task.name}${if (manual) " (manual)" else ""}, steps=${task.steps.size}")
        eventListener?.invoke(EngineEvent.TaskFired(task.id, task.name))
        currentTaskName = task.name
        var ran = 0
        try {
            for ((index, step) in task.steps.withIndex()) {
                if (!isMonitoring) break
                if (debug && (step is Step.Click || step is Step.Swipe)) {
                    runCatching { stepCapture?.invoke(task.id, index) }
                }
                runStep(step)
                ran++
            }
            if (!task.loop && !manual) disableTask(task.id)
        } finally {
            currentTaskName = null
            eventListener?.invoke(
                EngineEvent.TaskFinished(task.id, task.name, ran, isMonitoring, manual, debug)
            )
        }
    }

    private fun disableTask(id: Long) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) {
            tasks[index] = tasks[index].copy(enabled = false)
            notifyChanged()
        }
    }

    private suspend fun runStep(step: Step) {
        when (step) {
            is Step.Click -> {
                val (x, y) = applyOffset(step.x, step.y, step.maxOffsetPx)
                GestureDispatcher.tap(x, y, jitter(step.pressMs, step.pressJitterMs))
                delay(jitter(step.delayAfterMs, step.delayJitterMs).coerceAtLeast(0))
            }

            is Step.Swipe -> {
                GestureDispatcher.swipe(
                    step.x1, step.y1, step.x2, step.y2,
                    jitter(step.durationMs, step.durationJitterMs)
                )
                delay(jitter(step.delayAfterMs, step.delayJitterMs).coerceAtLeast(0))
            }

            is Step.Wait -> delay(step.ms.coerceAtLeast(0))

            is Step.EnableTagged -> setTagEnabled(step.tag, true)

            is Step.DisableTagged -> setTagEnabled(step.tag, false)
        }
    }

    /** base ± jitter 内的随机值；jitter<=0 时返回 base */
    private fun jitter(base: Long, jitterMs: Long): Long {
        if (jitterMs <= 0) return base
        return base + Random.nextLong(-jitterMs, jitterMs + 1)
    }

    private fun applyOffset(x: Float, y: Float, maxOffset: Float): Pair<Float, Float> {
        if (maxOffset <= 0f) return x to y
        val dx = (Random.nextFloat() - 0.5f) * 2f * maxOffset
        val dy = (Random.nextFloat() - 0.5f) * 2f * maxOffset
        return x + dx to y + dy
    }
}
