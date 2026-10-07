package com.flowclicker.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import com.flowclicker.app.ai.*
import com.flowclicker.app.core.ScreenControl
import com.flowclicker.app.engine.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Production HTTP, router, dispatcher and task runner; synthetic OCR, no physical gestures. */
internal class JudgeChecks(private val instrumentation: Instrumentation) {
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    fun run() = runBlocking {
        val settings = AiStores.loadSettings()
        val rules = AiStores.loadRules()
        val tasks = MonitoringEngine.tasksSnapshot()
        val frame = MonitoringEngine.frameProvider
        val recognizer = MonitoringEngine.textRecognizer
        val writer = MonitoringEngine.onChanged
        val text = AtomicReference("Network connection lost. Retry. ready")
        var id = 9000L
        val good = "Network connection lost. Retry. ready"
        MonitoringEngine.onChanged = null
        try {
            WakeDispatcher.stopAll()
            withTimeout(10000) { while (ScreenControl.owner != null) delay(20) }
            reconnectAccessibility(instrumentation)
            MonitoringEngine.onChanged = null
            MonitoringEngine.frameProvider = { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888) }
            MonitoringEngine.textRecognizer = { _, _ -> text.get() }

            suspend fun prepare(server: JudgeFakeServer, observe: Boolean = false): Task {
                WakeDispatcher.stopAll()
                withTimeout(10000) { while (ScreenControl.owner != null) delay(20) }
                text.set(good)
                AiStores.saveRules(emptyList())
                AiStores.saveSettings(settings.copy(enabled = false, judge = JudgeSettings()))
                MonitoringEngine.updateTasks { list -> list.clear(); list.add(Task(++id, "回归·恢复$id",
                    trigger = Trigger(keywords = listOf("NEVER_AUTO_TRIGGER")),
                    steps = listOf(Step.WaitText("ready", timeoutMs = 500)),
                    recoveryHint = "Network connection lost and Retry is visible")) }
                val t = MonitoringEngine.tasksSnapshot().single()
                MonitoringEngine.start()
                check(MonitoringEngine.requestRunNow(t.id))
                withTimeout(10000) { while (MonitoringEngine.lastResult(t.id)?.verified != true) delay(20) }
                AiStores.saveSettings(settings.copy(enabled = true, vlm = false, apiKey = "", model = "fake",
                    baseUrl = server.baseUrl, judge = JudgeSettings(enabled = true, observeOnly = observe,
                        baseUrl = server.baseUrl, apiKey = "", model = "any-open-model")))
                WakeDispatcher.onSettingsChanged()
                check(MonitoringEngine.recoveryTasks().single() == t)
                return t
            }
            suspend fun route(type: String = "idle"): AiLogEntry? {
                val token = WakeDispatcher.currentToken()
                return ScreenControl.withOwner("ai") {
                    JudgeRouter.route(WakeDispatcher.WakeEvent(type, "test", epoch = token), AiStores.loadSettings()) {
                        WakeDispatcher.checkAllowed(token)
                    }
                }
            }

            JudgeFakeServer().use { server ->
                prepare(server)
                val s = AiStores.loadSettings()
                AiStores.saveSettings(s.copy(judge = s.judge.copy(enabled = false)))
                check(route() == null && server.judgeRequests.get() == 0)
                AiStores.saveSettings(s)
                for (event in listOf("manual", "task_failed", "debug_review", "recording_finished", "test_run_finished")) check(route(event) == null)
                check(server.judgeRequests.get() == 0)
                report("J01 PASS: disabled and critical events bypass without HTTP")
            }
            JudgeFakeServer().use { server ->
                val t = prepare(server, observe = true)
                val before = MonitoringEngine.lastResult(t.id)
                check(route() == null && before == MonitoringEngine.lastResult(t.id))
                check(server.judgeRequests.get() == 1 && server.lastJudgePath == "/custom/v1/systemone")
                report("J02 PASS: shadow is read-only; custom base path supported")
            }
            JudgeFakeServer().use { server ->
                val t = prepare(server)
                val before = MonitoringEngine.lastResult(t.id)
                server.confidence = 0.6
                check(route() == null && MonitoringEngine.lastResult(t.id) == before)
                server.confidence = 0.99; server.malformed = true
                check(route() == null && MonitoringEngine.lastResult(t.id) == before)
                server.malformed = false; server.status = 503
                check(route() == null && server.judgeRequests.get() == 3)
                report("J03 PASS: low confidence, malformed and HTTP 503 fall back once, without execution/retry")
            }
            JudgeFakeServer().use { server ->
                val t = prepare(server)
                val before = MonitoringEngine.lastResult(t.id)
                server.onJudge = { text.set("Different screen ready") }
                check(route() == null && MonitoringEngine.lastResult(t.id) == before)
                report("J04 PASS: stale screen invalidates the decision")
            }
            for (edit in listOf("disable", "revision", "review")) JudgeFakeServer().use { server ->
                val t = prepare(server)
                val before = MonitoringEngine.lastResult(t.id)
                server.onJudge = {
                    when (edit) {
                        "disable" -> MonitoringEngine.updateTasks { it[0] = it[0].copy(enabled = false) }
                        "revision" -> MonitoringEngine.updateTasks { it[0] = it[0].copy(name = "changed") }
                        else -> MonitoringEngine.holdForReview(t.id)
                    }
                }
                check(route() == null && MonitoringEngine.lastResult(t.id) == before)
                report("J05 PASS: $edit during HTTP prevents execution")
            }
            JudgeFakeServer().use { server ->
                val t = prepare(server)
                val s = AiStores.loadSettings()
                AiStores.saveSettings(s.copy(judge = s.judge.copy(baseUrl = server.baseUrl + "/systemone")))
                val before = MonitoringEngine.lastResult(t.id)
                val result = route()
                check(result != null && MonitoringEngine.lastResult(t.id) != before && MonitoringEngine.lastResult(t.id)!!.verified)
                val after = MonitoringEngine.lastResult(t.id)
                check(route() == null && MonitoringEngine.lastResult(t.id) == after)
                check(server.lastJudgePath == "/custom/v1/systemone")
                report("J06 PASS: verified recovery executes once; full endpoint supported; cooldown prevents repetition")
            }
            JudgeFakeServer().use { server ->
                val t = prepare(server)
                val reads = java.util.concurrent.atomic.AtomicInteger()
                server.onJudge = {
                    MonitoringEngine.textRecognizer = { _, _ -> if (reads.incrementAndGet() <= 1) good else "missing result" }
                }
                check(route() == null && MonitoringEngine.lastResult(t.id)?.verified == false)
                check(t.id in MonitoringEngine.pendingReviews())
                MonitoringEngine.textRecognizer = { _, _ -> text.get() }
                report("J07 PASS: failed result verification falls back and holds the task")
            }
            JudgeFakeServer().use { server ->
                val t = prepare(server)
                val before = MonitoringEngine.lastResult(t.id)
                server.onJudge = { Thread.sleep(15000) }
                val start = android.os.SystemClock.elapsedRealtime()
                check(route() == null && MonitoringEngine.lastResult(t.id) == before)
                check(android.os.SystemClock.elapsedRealtime() - start < 14000)
                check(server.judgeRequests.get() == 1)
                report("J08 PASS: slow endpoint has a bounded timeout; no retry or action")
            }
            JudgeFakeServer().use { server ->
                val t = prepare(server)
                val changed = t.copy(name = "AI 编排的恢复", recoveryHint = "连接失败且出现重试")
                server.chatPlan = listOf(
                    """{"tool":"upsert_task","args":{"task":${Json.encodeToString(changed)}}}""",
                    """{"tool":"get_judge_status","args":{}}""",
                    """{"tool":"test_judge","args":{}}""",
                    """{"tool":"judge_screen","args":{}}""",
                    """{"tool":"test_judge","args":{}}""",
                )
                ScreenControl.withOwner("ai") { AiSession.run("manual", "测试编排与自检工具", AiStores.loadSettings()) }
                check(server.judgeRequests.get() == 2 && server.chatRequests.get() == 6)
                val saved = MonitoringEngine.tasksSnapshot().single()
                check(saved.mode == Task.MODE_DEBUG && saved.recoveryHint == changed.recoveryHint)
                check(MonitoringEngine.recoveryTasks().isEmpty())
                report("J09 PASS: actual AiSession uses planning/status/test/preview tools; 2-call budget; edits require new verification")
            }
            for (observe in listOf(false, true)) JudgeFakeServer().use { server ->
                val t = prepare(server, observe)
                AiStores.saveRules(listOf(WakeRule("idle", taskId = t.id, timeoutMs = 10000)))
                withTimeout(30000) { while (server.judgeRequests.get() == 0 || ScreenControl.owner == "ai") delay(50) }
                AiStores.saveRules(emptyList())
                check(server.chatRequests.get() == if (observe) 1 else 0)
                report("J10 PASS: real idle dispatcher -> ${if (observe) "shadow + original AI" else "verified recovery without AI"}")
            }
            JudgeFakeServer().use { server ->
                val t = prepare(server)
                val before = MonitoringEngine.lastResult(t.id)
                val release = CountDownLatch(1)
                server.onJudge = { check(release.await(30, TimeUnit.SECONDS)) }
                AiStores.saveRules(listOf(WakeRule("idle", taskId = t.id, timeoutMs = 10000)))
                withTimeout(30000) { while (server.judgeRequests.get() == 0) delay(50) }
                WakeDispatcher.stopAll()
                release.countDown()
                delay(60000)
                check(!MonitoringEngine.isMonitoring && ScreenControl.owner == null)
                check(before == MonitoringEngine.lastResult(t.id) && server.judgeRequests.get() == 1 && server.chatRequests.get() == 0)
                report("J11 PASS: stop during in-flight judgment; 60s no late action, fallback or restart")
            }
            File(instrumentation.targetContext.cacheDir, "judge-mock-passed").writeText(repairApkHash(instrumentation))
            report("JUDGE MOCK PASS: exact main APK marked for real-provider experiment")
        } finally {
            WakeDispatcher.stopAll()
            withTimeout(10000) { while (ScreenControl.owner != null) delay(20) }
            MonitoringEngine.onChanged = null
            MonitoringEngine.frameProvider = frame; MonitoringEngine.textRecognizer = recognizer
            MonitoringEngine.updateTasks { it.clear() }
            MonitoringEngine.setTasks(tasks); MonitoringEngine.onChanged = writer
            AiStores.saveRules(rules); AiStores.saveSettings(settings)
        }
    }
}
