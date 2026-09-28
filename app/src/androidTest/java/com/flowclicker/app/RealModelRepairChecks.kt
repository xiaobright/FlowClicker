package com.flowclicker.app

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.widget.Button
import com.flowclicker.app.ai.*
import com.flowclicker.app.core.ScreenControl
import com.flowclicker.app.engine.*
import com.flowclicker.app.service.ScreenCaptureService
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

/**
 * Real provider -> production AI tools -> real projection/OCR/gesture.
 * A test-only allowlist prevents model instructions from touching user tasks/settings.
 * This intentionally is NOT the unrestricted background AiSession workflow.
 */
internal class RealModelRepairChecks(private val instrumentation: Instrumentation) {
    private fun report(message: String) = instrumentation.sendStatus(0,
        Bundle().apply { putString("stream", "$message\n") })

    fun run(useModel: Boolean = true) = runBlocking {
        val context = instrumentation.targetContext
        check(!useModel || File(context.cacheDir, "repair-mock-passed").readText() == repairApkHash(instrumentation)) {
            "the mock suite must pass on this exact main APK before real requests"
        }
        val settings = AiStores.loadSettings()
        check(!useModel || (settings.baseUrl.isNotBlank() && settings.model.isNotBlank() &&
            !settings.baseUrl.contains("127.0.0.1") && settings.model != "fake")) { "real settings unavailable" }
        val before = MonitoringEngine.tasksSnapshot()
        WakeDispatcher.stopAll()
        reconnectAccessibility(instrumentation)
        try {
            val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync {
                main.findViewById<Button>(R.id.btnCaptureStart).performClick()
            }
            report("REAL_WAIT_PROJECTION: approve the Android screen sharing dialog within 180s")
            withTimeout(180000) {
                while (ScreenCaptureService.instance?.currentFrame()?.let { it.recycle(); true } != true) delay(100)
            }
            val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            // Use shell's foreground launch, not an unverified background startActivity.
            // Never send a real request while a notification shade/old Activity hides the fixture.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                ui.executeShellCommand("cmd statusbar collapse")).use { it.readBytes() }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(
                "am start -W -f ${Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK} " +
                    "-n com.flowclicker.app.test/com.flowclicker.app.RepairTestActivity"
            )).use { it.readBytes() }
            withTimeout(15000) {
                while (true) {
                    val root = ui.rootInActiveWindow
                    if (root?.packageName?.toString() == instrumentation.context.packageName &&
                        root.findAccessibilityNodeInfosByText("安全点击").any { it.text?.toString() == "安全点击" } &&
                        root.findAccessibilityNodeInfosByText("等待操作").isNotEmpty() &&
                        root.findAccessibilityNodeInfosByText("验证成功").isEmpty()) break
                    delay(100)
                }
            }
            val preflight = MonitoringEngine.preview(Task(Long.MAX_VALUE, "验收页面就绪",
                steps = listOf(Step.WaitText("安全点击", timeoutMs = 10000))))
            check(preflight.verified) { "test fixture is not visible in production capture/OCR: ${preflight.reason}" }
            report("FIXTURE_READY: foreground test Activity + production OCR both confirmed")
            if (!useModel) {
                ScreenControl.withOwner("ai") {
                    val located = AiTools.execute("locate_text", buildJsonObject { put("text", "安全点击") }, settings)
                    val hits = Json.parseToJsonElement(located.text).jsonObject["hits"]!!.jsonArray
                    val point = hits.single().jsonObject["center"]!!.jsonObject
                    val tapped = AiTools.execute("tap_screen", point, settings)
                    check(!Json.parseToJsonElement(tapped.text).jsonObject.containsKey("error")) { tapped.text }
                }
                val verification = MonitoringEngine.preview(Task(Long.MAX_VALUE, "确定性点击验收",
                    steps = listOf(Step.WaitText("验证成功", timeoutMs = 10000))))
                check(verification.verified) { "deterministic click failed: ${verification.reason}" }
                check(before == MonitoringEngine.tasksSnapshot())
                report("WORKFLOW PASS: real projection/OCR -> locate -> gesture -> fresh WaitText; no model; tasks unchanged")
                return@runBlocking
            }
            val client = AiClient(settings)
            val messages = mutableListOf(
                client.textMessage("system", """
                    你正在进行隔离点击验收。每轮只能返回一个 JSON：
                    {"tool":"locate_text","args":{"text":"安全点击"}} 或其他允许工具，
                    最后 {"reply":"结论"}。禁止 Markdown。
                    唯一允许工具：describe_screen({})、get_screenshot({})、
                    locate_text({"text":"文字"})、tap_screen({"x":物理像素,"y":物理像素})。
                    页面已经过前台窗口和真实 OCR 双重检查，“安全点击”按钮可见。
                    必须先观察并用 locate_text 定位，点击一次，再观察验证“验证成功”。
                    禁止 swipe_screen、任务/引擎/记忆/设置操作；若发现其他界面立即回复失败原因，不自行修复测试环境。
                """.trimIndent()),
                client.textMessage("user", "这是隔离验收页面，不要创建、修改、删除任何任务或记忆，不要启动引擎。" +
                    "只准使用 describe_screen/get_screenshot/locate_text/tap_screen。" +
                    "请观察当前页面，用 locate_text 定位文字“安全点击”，只点击该按钮一次，然后重新观察并确认出现“验证成功”，最后回复验收结论。")
            )
            val allowed = setOf("describe_screen", "get_screenshot", "locate_text", "tap_screen")
            var taps = 0
            var requests = 0
            var replied = false
            AiTools.clearSession()
            withTimeout(240000) {
                ScreenControl.withOwner("ai") {
                    for (round in 0..12) {
                        requests++
                        val raw = client.chat(messages)
                        val parsed = AiSession.parseAssistant(raw) ?: error("real model returned invalid protocol")
                        messages += client.textMessage("assistant", raw)
                        if (parsed.reply != null) {
                            replied = true
                            break
                        }
                        val name = parsed.tool ?: error("missing tool")
                        check(name in allowed) { "test policy blocked tool: $name" }
                        val args = parsed.args ?: JsonObject(emptyMap())
                        if (name == "tap_screen") {
                            check(taps == 0) { "test allows only one tap" }
                            val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                            val root = ui.rootInActiveWindow ?: error("test window unavailable")
                            check(root.packageName?.toString() == instrumentation.context.packageName) { "not on disposable test screen" }
                            val nodes = root.findAccessibilityNodeInfosByText("安全点击")
                            val button = nodes.single { it.text?.toString() == "安全点击" }
                            val bounds = Rect().also { button.getBoundsInScreen(it) }
                            val x = args["x"]!!.jsonPrimitive.float.toInt()
                            val y = args["y"]!!.jsonPrimitive.float.toInt()
                            check(bounds.contains(x, y)) { "model point outside safe test button" }
                        }
                        val result = AiTools.execute(name, args, settings)
                        check(!Json.parseToJsonElement(result.text).jsonObject.containsKey("error")) {
                            "production tool failed: $name"
                        }
                        if (name == "tap_screen") taps++
                        report("REAL_TOOL_OK: $name")
                        val text = """{"tool_result":${result.text}}"""
                        messages += if (result.images.isEmpty()) client.textMessage("user", text)
                            else client.imageMessage("user", text, result.images)
                    }
                }
            }
            check(replied && taps == 1) { "real model did not complete the one-tap workflow" }
            val verified = MonitoringEngine.preview(Task(Long.MAX_VALUE, "隔离验收",
                steps = listOf(Step.WaitText("验证成功", timeoutMs = 10000))))
            check(verified.completed && verified.verified) { "post-action OCR verification failed: ${verified.reason}" }
            check(before == MonitoringEngine.tasksSnapshot()) { "user tasks changed" }
            WakeDispatcher.stopAll()
            delay(60000)
            check(!MonitoringEngine.isMonitoring && ScreenControl.owner == null)
            report("REAL PASS: requests=$requests; taps=1; post-action WaitText verified; 60s stopped; user tasks unchanged")
        } finally {
            WakeDispatcher.stopAll()
            ScreenCaptureService.stop(context)
        }
    }
}
