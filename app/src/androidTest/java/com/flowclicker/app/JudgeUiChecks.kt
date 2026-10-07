package com.flowclicker.app

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import com.flowclicker.app.ai.*
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.Task
import com.flowclicker.app.engine.Step
import com.flowclicker.app.ui.AiActivity
import com.flowclicker.app.ui.TaskEditActivity
import java.io.File

internal class JudgeUiChecks(private val i: Instrumentation) {
    fun run() {
        val original = AiStores.loadSettings()
        val tasks = MonitoringEngine.tasksSnapshot()
        val writer = MonitoringEngine.onChanged
        WakeDispatcher.stopAll()
        MonitoringEngine.onChanged = null
        try {
            AiStores.saveSettings(original.copy(enabled = false, judge = JudgeSettings()))
            fun launchAi() = i.startActivitySync(Intent(i.targetContext, AiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val a = launchAi()
            i.runOnMainSync {
                check(!a.findViewById<CheckBox>(R.id.cbJudgeEnabled).isChecked)
                check(a.findViewById<CheckBox>(R.id.cbJudgeObserve).isChecked)
                a.findViewById<CheckBox>(R.id.cbJudgeEnabled).isChecked = true
                a.findViewById<EditText>(R.id.etJudgeBaseUrl).setText("http://192.168.1.8:8080/custom/v1")
                a.findViewById<EditText>(R.id.etJudgeModel).setText("self-hosted-model")
                a.findViewById<EditText>(R.id.etJudgeApiKey).setText("ui-fixture-key")
                a.findViewById<Button>(R.id.btnSave).performClick()
            }
            i.waitForIdleSync()
            val saved = AiStores.loadSettings().judge
            check(saved.enabled && saved.observeOnly && saved.model == "self-hosted-model")
            check(saved.apiKey == "ui-fixture-key" && !JudgeTools.status().toString().contains("ui-fixture-key"))
            i.runOnMainSync { a.finish() }
            val reopened = launchAi()
            i.runOnMainSync {
                check(reopened.findViewById<EditText>(R.id.etJudgeBaseUrl).text.toString() == saved.baseUrl)
                reopened.findViewById<android.view.View>(R.id.aiSettingsPanel).visibility = android.view.View.VISIBLE
            }
            i.waitForIdleSync(); Thread.sleep(500)
            i.runOnMainSync {
                val target = reopened.findViewById<EditText>(R.id.etJudgeBaseUrl)
                target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            }
            i.waitForIdleSync(); Thread.sleep(500)
            i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { image ->
                try { File(i.targetContext.cacheDir, "judge-settings-screen.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { image.recycle() }
            }
            val t = Task((tasks.maxOfOrNull { it.id } ?: 0) + 1000, "回归·判断配置编辑", steps = listOf(Step.Wait(0)))
            MonitoringEngine.setTasks(tasks + t)
            val editor = i.startActivitySync(Intent(i.targetContext, TaskEditActivity::class.java)
                .putExtra(TaskEditActivity.EXTRA_ID, t.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            i.runOnMainSync {
                editor.findViewById<EditText>(R.id.etRecoveryHint).setText("网络连接中断，且有重试按钮")
                editor.findViewById<Button>(R.id.btnSave).performClick()
            }
            i.waitForIdleSync()
            check(MonitoringEngine.tasksSnapshot().single { it.id == t.id }.recoveryHint == "网络连接中断，且有重试按钮")
            i.sendStatus(0, Bundle().apply { putString("stream", "JUDGE UI PASS: default off/observe, custom endpoint, masked key, save/reload, recovery hint editor\n") })
        } finally {
            WakeDispatcher.stopAll()
            MonitoringEngine.setTasks(tasks); MonitoringEngine.onChanged = writer
            AiStores.saveSettings(original)
        }
    }
}
