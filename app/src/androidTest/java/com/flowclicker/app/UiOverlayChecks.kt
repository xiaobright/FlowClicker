package com.flowclicker.app

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.Button
import com.flowclicker.app.ai.AiStores
import com.flowclicker.app.core.GestureDispatcher
import com.flowclicker.app.core.ScreenControl
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.Step
import com.flowclicker.app.service.RecordingService
import java.io.File
import kotlin.math.abs

/** Real touch -> recording overlay -> accessibility forwarding -> isolated test button. */
class UiOverlayChecks(private val i: Instrumentation) {
    private val context get() = i.targetContext
    private val ui get() = i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8) }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "overlay condition timed out" }
            SystemClock.sleep(50)
        }
    }
    private fun overlay(): View? {
        var result: View? = null
        i.runOnMainSync { result = WindowInspector.getGlobalWindowViews().firstOrNull { it.findViewById<View>(R.id.recordPanel) != null } }
        return result
    }
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 80L)) {
            val event = MotionEvent.obtain(now, now + offset, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { check(ui.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
        }
    }
    fun run() {
        check(android.os.Build.MODEL.contains("sdk")) { "emulator only" }
        check(AiStores.loadSettings().let { !it.enabled && it.baseUrl.isBlank() && it.apiKey.isBlank() })
        check(!MonitoringEngine.isMonitoring)
        val before = MonitoringEngine.tasksSnapshot()
        kotlinx.coroutines.runBlocking { reconnectAccessibility(i) }
        check(GestureDispatcher.isReady)
        try {
            shell("am start -W -f ${Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK} " +
                "-n com.flowclicker.app.test/com.flowclicker.app.RepairTestActivity")
            val bounds = Rect()
            await {
                val root = ui.rootInActiveWindow
                val button = root?.findAccessibilityNodeInfosByText("安全点击")?.singleOrNull { it.text?.toString() == "安全点击" }
                button?.getBoundsInScreen(bounds)
                button != null && !bounds.isEmpty
            }
            i.runOnMainSync { context.startForegroundService(Intent(context, RecordingService::class.java)) }
            await { overlay() != null && ScreenControl.owner == "recording" }
            val root = overlay()!!
            i.runOnMainSync {
                val touch = root.findViewById<View>(R.id.recordTouch)
                val area = Rect()
                touch.getGlobalVisibleRect(area)
                check(area.contains(bounds.centerX(), bounds.centerY())) { "fixture is covered by panel" }
            }
            i.waitForIdleSync()
            SystemClock.sleep(500) // Let WindowManager present the newly attached overlay surface.
            val screenshot = ui.takeScreenshot() ?: error("no screenshot")
            try {
                File(context.cacheDir, "ui-overlay.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { screenshot.recycle() }
            tap(bounds.exactCenterX(), bounds.exactCenterY())
            await { ui.rootInActiveWindow?.findAccessibilityNodeInfosByText("验证成功")?.isNotEmpty() == true }
            // Forwarding is complete before accepting recorded steps.
            i.runOnMainSync { root.findViewById<Button>(R.id.recordFinish).performClick() }
            await { overlay() == null && ScreenControl.owner == null }
            val recorded = RecordingService.takeResult() ?: error("no recorded result")
            val click = recorded.single() as? Step.Click ?: error("expected exactly one click")
            check(abs(click.x - bounds.exactCenterX()) < 2 && abs(click.y - bounds.exactCenterY()) < 2) {
                "overlay offset changed recorded physical coordinates"
            }
            i.runOnMainSync { context.startForegroundService(Intent(context, RecordingService::class.java)) }
            await { overlay() != null }
            val cancelRoot = overlay()!!
            i.runOnMainSync { cancelRoot.findViewById<Button>(R.id.recordCancel).performClick() }
            await { overlay() == null && ScreenControl.owner == null }
            check(RecordingService.takeResult() == null) { "cancel must not produce a recording" }
            check(before == MonitoringEngine.tasksSnapshot())
            i.sendStatus(0, Bundle().apply {
                putString("stream", "PASS overlay/real touch/forwarded click/physical coordinates/finish/cancel/unchanged task store\n")
            })
        } finally {
            i.runOnMainSync { context.stopService(Intent(context, RecordingService::class.java)) }
        }
    }
}
