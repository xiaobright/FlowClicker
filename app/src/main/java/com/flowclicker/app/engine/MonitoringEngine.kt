package com.flowclicker.app.engine

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.flowclicker.app.core.GestureDispatcher
import com.flowclicker.app.core.ScreenControl
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

sealed interface EngineEvent {
    val generation: Long
    data class TaskFired(val taskId: Long, val name: String, override val generation: Long) : EngineEvent
    data class TaskFinished(
        val taskId: Long, val name: String, val stepsRan: Int,
        val completed: Boolean, val manual: Boolean, val debugMode: Boolean,
        val result: RunResult,
        override val generation: Long,
    ) : EngineEvent
}

@Serializable
data class RunResult(
    val taskId: Long, val revision: Long, val runId: String,
    val stepsRan: Int, val completed: Boolean, val verified: Boolean,
    val reason: String? = null,
)

internal class FrameGapWatch(private val maxGapMs: Long = 5000) {
    private var missingSince: Long? = null

    fun expired(hasFrame: Boolean, now: Long): Boolean {
        if (hasFrame) {
            missingSince = null
            return false
        }
        val since = missingSince
        if (since == null) {
            missingSince = now
            return false
        }
        return now - since >= maxGapMs
    }
}

/** One serialized state writer and one cancellable local runner. */
object MonitoringEngine {
    private val stateLock = Any()
    private val lifecycleLock = Any()
    private var tasks: List<Task> = emptyList()
    private var scope: CoroutineScope? = null
    @Volatile private var generation = 0L
    private val manualFire = AtomicReference<Long?>(null)
    private val reviewPending = mutableSetOf<Long>()
    private val results = mutableMapOf<Long, RunResult>()
    @Volatile var loadError: String? = null
    @Volatile var isMonitoring = false
        private set
    @Volatile var currentTaskName: String? = null
        private set
    @Volatile var frameProvider: (() -> Bitmap?)? = null
    @Volatile var textRecognizer: (suspend (Bitmap?, Region?) -> String?)? = null
    @Volatile var textLocator: (suspend (Bitmap?, Region?) -> List<OcrBox>)? = null
    var onChanged: ((List<Task>) -> Unit)? = null
    @Volatile var eventListener: ((EngineEvent) -> Unit)? = null
    /** stepIndex=-1 means the final, post-action frame. */
    @Volatile var stepCapture: ((Long, Int) -> Unit)? = null

    /** Initialization only. All subsequent changes use updateTasks. */
    fun setTasks(list: List<Task>) = synchronized(stateLock) { tasks = list.toList() }
    fun tasksSnapshot(): List<Task> = synchronized(stateLock) { tasks.toList() }
    fun lastResult(id: Long): RunResult? = synchronized(stateLock) { results[id] }
    fun pendingReviews(): List<Long> = synchronized(stateLock) { reviewPending.toList() }
    fun isCurrentGeneration(token: Long): Boolean = generation == token
    fun holdForReview(id: Long) = synchronized(stateLock) { reviewPending.add(id); Unit }

    fun updateTasks(transform: (MutableList<Task>) -> Unit) = synchronized(stateLock) {
        check(loadError == null) { "任务文件加载失败，请先恢复备份，禁止覆盖：$loadError" }
        val list = tasks.toMutableList()
        transform(list)
        require(list.map { it.id }.distinct().size == list.size) { "任务 ID 重复" }
        val next = list.map { t ->
            val old = tasks.firstOrNull { it.id == t.id }
            if (old != t) TaskValidation.validate(t)
            val changed = old == null || old.copy(enabled = t.enabled, mode = t.mode, revision = t.revision) != t
            t.copy(revision = if (changed) (old?.revision ?: 0) + 1 else old!!.revision)
        }
        // Commit bytes before publishing memory. Write failure is surfaced to caller.
        onChanged?.invoke(next)
        for (t in next) {
            val old = tasks.firstOrNull { it.id == t.id }
            if (old == null || old.revision != t.revision || old.mode != t.mode || old.enabled != t.enabled) {
                reviewPending.remove(t.id)
            }
        }
        val ids = next.map { it.id }.toSet()
        reviewPending.retainAll(ids)
        results.keys.retainAll(ids)
        tasks = next
    }

    fun promote(id: Long) = synchronized(stateLock) {
        val t = tasks.firstOrNull { it.id == id } ?: error("任务不存在")
        val r = results[id]
        check(r != null && r.revision == t.revision && r.completed && r.verified) {
            "不能转正：当前版本须成功试跑，且最后一步 wait_text 验证通过"
        }
        updateTasks { list -> val i = list.indexOfFirst { it.id == id }; list[i] = list[i].copy(mode = Task.MODE_NORMAL) }
    }

    fun requestRunNow(taskId: Long): Boolean {
        if (!isMonitoring || tasksSnapshot().none { it.id == taskId }) return false
        return manualFire.compareAndSet(null, taskId)
    }

    fun start() = synchronized(lifecycleLock) {
        check(loadError == null) { "任务文件异常：$loadError" }
        check(GestureDispatcher.isReady) { "请先开启无障碍服务" }
        val frame = frameProvider?.invoke() ?: error("请先开启屏幕采集并等待画面")
        frame.recycle()
        if (isMonitoring) return@synchronized
        isMonitoring = true
        val token = ++generation
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            s.launch {
                val frameGap = FrameGapWatch()
                try {
                    while (currentCoroutineContext().isActive) {
                        ScreenControl.withOwner("engine") {
                            val id = manualFire.getAndSet(null)
                            if (id != null) {
                                frameGap.expired(true, SystemClock.elapsedRealtime())
                                tasksSnapshot().firstOrNull { it.id == id }?.let { runTask(it, true) }
                            } else if (frameGap.expired(evaluateOnce(), SystemClock.elapsedRealtime())) {
                                error("连续 5 秒未获取到屏幕画面，监测已停止；请检查采集权限")
                            }
                        }
                        delay(500)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MonitoringEngine", "runner stopped", e)
                } finally {
                    synchronized(lifecycleLock) {
                        if (generation == token) { isMonitoring = false; currentTaskName = null; scope = null }
                    }
                }
            }
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        ++generation
        isMonitoring = false
        manualFire.set(null)
        scope?.cancel()
        scope = null
        currentTaskName = null
    }

    /** Does not mutate the library or start monitoring other tasks. */
    suspend fun preview(task: Task): RunResult = ScreenControl.withOwner("preview") {
        check(!isMonitoring) { "请先停止引擎再进行临时测试" }
        runTask(task, manual = true, transient = true)
    }

    private suspend fun evaluateOnce(): Boolean {
        val active = synchronized(stateLock) {
            tasks.filter { it.enabled && it.id !in reviewPending }.sortedBy { it.priority }
        }
        if (active.isEmpty()) return true
        val frame = frameProvider?.invoke() ?: return false
        try {
            val cache = mutableMapOf<Region?, String?>()
            for (task in active) {
                currentCoroutineContext().ensureActive()
                val text = if (cache.containsKey(task.trigger.region)) cache[task.trigger.region]
                    else textRecognizer?.invoke(frame, task.trigger.region).also { cache[task.trigger.region] = it }
                if (text != null && task.trigger.keywords.any { it.isNotBlank() && text.contains(it, task.trigger.ignoreCase) }) {
                    val latest = tasksSnapshot().firstOrNull { it.id == task.id }
                    if (latest?.enabled == true && latest.revision == task.revision) runTask(task, false)
                    return true
                }
            }
        } finally { frame.recycle() }
        return true
    }

    private suspend fun runTask(task: Task, manual: Boolean, transient: Boolean = false): RunResult {
        val runGeneration = generation
        var ran = 0
        var completed = false
        var reason: String? = null
        val runId = "${System.currentTimeMillis()}-${task.id}"
        currentTaskName = task.name
        if (!transient) emit(EngineEvent.TaskFired(task.id, task.name, runGeneration))
        try {
            TaskValidation.validate(task)
            for ((index, step) in task.steps.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!transient) {
                    val latest = tasksSnapshot().firstOrNull { it.id == task.id }
                    check(latest != null && latest.revision == task.revision && (manual || latest.enabled)) {
                        "任务已修改、删除或停用"
                    }
                }
                if (task.mode == Task.MODE_DEBUG && (step is Step.Click || step is Step.Swipe)) {
                    runCatching { stepCapture?.invoke(task.id, index) }
                }
                runStep(step)
                ran++
            }
            if (!transient && !task.loop && !manual) updateTasks { list ->
                val i = list.indexOfFirst { it.id == task.id }
                if (i >= 0) list[i] = list[i].copy(enabled = false)
            }
            currentCoroutineContext().ensureActive()
            completed = true
        } catch (e: CancellationException) {
            reason = "已取消"
            throw e
        } catch (e: Exception) {
            reason = e.message ?: e.javaClass.simpleName
            Log.w("MonitoringEngine", "task failed: ${task.id}: $reason")
        } finally {
            currentTaskName = null
            if (!transient && task.mode == Task.MODE_DEBUG) runCatching { stepCapture?.invoke(task.id, -1) }
            val result = RunResult(task.id, task.revision, runId, ran, completed,
                completed && task.steps.lastOrNull() is Step.WaitText, reason)
            if (!transient) {
                synchronized(stateLock) {
                    results[task.id] = result
                    if (!completed) reviewPending.add(task.id)
                }
                if (generation == runGeneration) emit(EngineEvent.TaskFinished(task.id, task.name, ran, completed, manual,
                    task.mode == Task.MODE_DEBUG, result, runGeneration))
            }
        }
        return RunResult(task.id, task.revision, runId, ran, completed,
            completed && task.steps.lastOrNull() is Step.WaitText, reason)
    }

    private fun emit(e: EngineEvent) {
        runCatching { eventListener?.invoke(e) }.onFailure { Log.w("MonitoringEngine", "event failed", it) }
    }

    private suspend fun runStep(step: Step) {
        when (step) {
            is Step.Click -> {
                val (x, y) = resolveClickTarget(step)
                check(GestureDispatcher.tap(
                    x + Random.nextFloat() * step.maxOffsetPx * 2 - step.maxOffsetPx,
                    y + Random.nextFloat() * step.maxOffsetPx * 2 - step.maxOffsetPx,
                    jitter(step.pressMs, step.pressJitterMs).coerceAtLeast(1)
                )) { "点击未派发或被取消" }
                currentCoroutineContext().ensureActive()
                delay(jitter(step.delayAfterMs, step.delayJitterMs).coerceAtLeast(0))
            }
            is Step.Swipe -> {
                check(GestureDispatcher.swipe(step.x1, step.y1, step.x2, step.y2,
                    jitter(step.durationMs, step.durationJitterMs).coerceAtLeast(1))) { "滑动未派发或被取消" }
                currentCoroutineContext().ensureActive()
                delay(jitter(step.delayAfterMs, step.delayJitterMs).coerceAtLeast(0))
            }
            is Step.Wait -> delay(step.ms)
            is Step.WaitText -> waitForText(step) {
                val frame = frameProvider?.invoke()
                if (frame == null) null
                else try { textRecognizer?.invoke(frame, step.region) } finally { frame.recycle() }
            }
            is Step.EnableTagged -> setTag(step.tag, true)
            is Step.DisableTagged -> setTag(step.tag, false)
        }
    }

    private fun setTag(tag: String, enabled: Boolean) = updateTasks { list ->
        list.indices.forEach { i -> if (list[i].tag == tag) list[i] = list[i].copy(enabled = enabled) }
    }

    private suspend fun resolveClickTarget(step: Step.Click): Pair<Float, Float> {
        val anchor = step.anchor?.trim().orEmpty()
        if (anchor.isEmpty()) return step.x to step.y
        val frame = frameProvider?.invoke() ?: error("锚点定位时无可用画面")
        val hits = try {
            textLocator?.invoke(frame, null).orEmpty().filter {
                it.text.replace(" ", "").contains(anchor.replace(" ", ""), true)
            }
        } finally { frame.recycle() }
        if (hits.size == 1) return hits[0].centerX.toFloat() to hits[0].centerY.toFloat()
        check(step.anchorFallback) { "锚点「$anchor」未找到或有多个匹配，已停止而非盲点旧坐标" }
        return step.x to step.y
    }

    private fun jitter(base: Long, amount: Long): Long =
        if (amount <= 0) base else base + Random.nextLong(-amount, amount + 1)
}
