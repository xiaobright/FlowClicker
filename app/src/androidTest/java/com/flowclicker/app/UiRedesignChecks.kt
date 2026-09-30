package com.flowclicker.app

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flowclicker.app.ai.AiStores
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.Step
import com.flowclicker.app.ui.AiActivity
import com.flowclicker.app.ui.TaskEditActivity
import com.flowclicker.app.ui.TaskListActivity
import com.google.android.material.switchmaterial.SwitchMaterial

/** Opt-in, in-process UI acceptance. Refuses non-emulators and configured AI endpoints. */
class UiRedesignChecks(private val i: Instrumentation) {
    @Volatile private var resumed: Activity? = null
    private val app get() = i.targetContext.applicationContext as Application
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { resumed = a }
        override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }
    private fun main(block: () -> Unit) = i.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "UI state did not settle" }
            SystemClock.sleep(50)
        }
        i.waitForIdleSync()
    }
    private fun launch(type: Class<out Activity>, id: Long? = null): Activity {
        return i.startActivitySync(Intent(i.targetContext, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { id?.let { putExtra(TaskEditActivity.EXTRA_ID, it) } })
    }
    private fun tree(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    private fun dialog(): View = WindowInspector.getGlobalWindowViews().last {
        it.findViewById<View>(android.R.id.button1) != null
    }
    private fun confirmDialog() {
        main { dialog().findViewById<Button>(android.R.id.button1).performClick() }
        // AlertController posts the listener to its Handler; performClick alone is not completion.
        i.waitForIdleSync()
    }
    private fun click(a: Activity, id: Int) = main { check(a.findViewById<View>(id).performClick()) }
    private fun set(a: Activity, id: Int, value: String) = main { a.findViewById<EditText>(id).setText(value) }
    private fun text(a: Activity, id: Int): String {
        var value = ""
        main { value = a.findViewById<TextView>(id).text.toString() }
        return value
    }

    fun run() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "emulator only" }
        check(AiStores.loadSettings().let { !it.enabled && it.baseUrl.isBlank() && it.apiKey.isBlank() }) {
            "use a sanitized disposable emulator; never call real AI"
        }
        check(!MonitoringEngine.isMonitoring)
        val before = MonitoringEngine.tasksSnapshot()
        check(before.isEmpty()) { "UI acceptance expects an empty disposable task store" }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            val home = launch(MainActivity::class.java)
            check(text(home, R.id.tvHomeTitle).isNotBlank())
            check(text(home, R.id.tvTaskSummary).contains("0 个任务"))
            click(home, R.id.btnTasks)
            await { resumed is TaskListActivity }
            val list = resumed!!
            click(list, R.id.btnNew)
            await { resumed is TaskEditActivity }
            var editor = resumed!!
            set(editor, R.id.etName, "UI acceptance draft")
            set(editor, R.id.etKeywords, "isolated-trigger")
            click(editor, R.id.btnAddWait)
            main { check(tree(dialog()).any { it is ScrollView }) }
            confirmDialog()
            check(text(editor, R.id.tvStepCount) == "1 个步骤")

            val previous = editor
            main { editor.recreate() }
            await { resumed is TaskEditActivity && resumed !== previous }
            editor = resumed!!
            check(text(editor, R.id.etName) == "UI acceptance draft")
            check(text(editor, R.id.etKeywords) == "isolated-trigger")
            check(text(editor, R.id.tvStepCount) == "1 个步骤")
            click(editor, R.id.btnSave)
            await { resumed is TaskListActivity }
            val task = MonitoringEngine.tasksSnapshot().single()
            check(task.name == "UI acceptance draft" && task.steps == listOf(Step.Wait(1000)))

            // The live refresh must not replace an unchanged card (focus / switches stay stable).
            var first: View? = null
            main { first = resumed!!.findViewById<LinearLayout>(R.id.llTasks).getChildAt(0) }
            SystemClock.sleep(1300)
            main {
                check(first === resumed!!.findViewById<LinearLayout>(R.id.llTasks).getChildAt(0))
                tree(first!!).filterIsInstance<SwitchMaterial>().single().performClick()
            }
            check(!MonitoringEngine.tasksSnapshot().single().enabled)

            editor = launch(TaskEditActivity::class.java, task.id)
            click(editor, R.id.btnAddClick)
            main {
                val root = dialog()
                check(tree(root).filterIsInstance<EditText>().size >= 8)
                check(tree(root).any { it is ScrollView })
                root.findViewById<Button>(android.R.id.button2).performClick()
            }
            // Verify the unsaved-draft guard, without writing that draft.
            set(editor, R.id.etName, "not saved")
            click(editor, R.id.btnBack)
            main { check(tree(dialog()).filterIsInstance<TextView>().any { it.text.contains("放弃") }) }
            confirmDialog()
            check(MonitoringEngine.tasksSnapshot().single().name == task.name)

            val ai = launch(AiActivity::class.java)
            check(text(ai, R.id.tvAiStatus) == "尚未启用")
            click(ai, R.id.btnAiSettings)
            main { check(ai.findViewById<View>(R.id.aiSettingsPanel).visibility == View.GONE) }
            click(ai, R.id.btnAiSettings)
            main { check(ai.findViewById<EditText>(R.id.etApiKey).transformationMethod != null) }
            click(ai, R.id.btnWake) // Empty instruction must not call a model.
            check(AiStores.loadLog().isEmpty())
            check(AiStores.loadSettings().baseUrl.isBlank())

            val tasks = launch(TaskListActivity::class.java)
            main {
                tree(tasks.findViewById(R.id.llTasks)).filterIsInstance<Button>()
                    .single { it.text == "删除" }.performClick()
            }
            confirmDialog()
            await { MonitoringEngine.tasksSnapshot().isEmpty() }
            i.sendStatus(0, Bundle().apply {
                putString("stream", "PASS home/navigation/create-save/recreate/step-dialog/stable-list/toggle/discard/AI-guard/delete\n")
            })
        } finally {
            main { MonitoringEngine.updateTasks { it.clear(); it.addAll(before) } }
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
