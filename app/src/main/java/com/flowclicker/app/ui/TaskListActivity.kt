package com.flowclicker.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.flowclicker.app.R
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.Task
import com.flowclicker.app.engine.TaskStore
import com.flowclicker.app.ai.AiStores
import com.flowclicker.app.ai.WakeDispatcher
import android.os.Handler
import android.os.Looper
import com.flowclicker.app.ui.Ui.add
import com.flowclicker.app.ui.Ui.dp
import com.google.android.material.switchmaterial.SwitchMaterial

/** 任务列表：启停任务、启动/停止引擎、进入编辑器 */
class TaskListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEngineStatus: TextView
    private var tasks: MutableList<Task> = mutableListOf()
    private var renderedTasks: List<Task>? = null
    private var renderedReviews: Set<Long> = emptySet()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            reload(); render(); updateEngineStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        reload()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Screens.tasks(this)
        container = findViewById(R.id.llTasks)
        tvEngineStatus = findViewById(R.id.tvEngineStatus)

        findViewById<Button>(R.id.btnNew).setOnClickListener {
            editorLauncher.launch(Intent(this, TaskEditActivity::class.java))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (tasks.none { it.enabled }) {
                toast("没有启用的任务")
                return@setOnClickListener
            }
            try {
                MonitoringEngine.start()
                WakeDispatcher.resumeAutomation()
            } catch (e: Exception) { toast(e.message ?: "启动失败") }
            updateEngineStatus()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            WakeDispatcher.stopAll()
            stopService(Intent(this, com.flowclicker.app.service.RecordingService::class.java))
            updateEngineStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun reload() {
        tasks = MonitoringEngine.tasksSnapshot().toMutableList()
    }

    private fun render() {
        val reviews = MonitoringEngine.pendingReviews().toSet()
        if (renderedTasks == tasks && renderedReviews == reviews) return
        renderedTasks = tasks.toList()
        renderedReviews = reviews
        container.removeAllViews()
        if (tasks.isEmpty()) {
            Ui.card(container, 16) {
                add(Ui.text(this@TaskListActivity, "从第一个小流程开始", 19, bold = true))
                add(Ui.text(this@TaskListActivity, "比如：识别一个关键词、点击按钮，再验证结果。点击上方「新建任务」开始。", 14, com.flowclicker.app.R.color.fc_muted), 10)
            }
        } else tasks.sortedBy { it.priority }.forEach { makeRow(it) }
    }

    private fun makeRow(t: Task) {
        val a = this
        Ui.card(container, 12) {
            val header = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(Ui.text(a, t.name, 19, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(SwitchMaterial(a).apply {
                contentDescription = "启用任务 ${t.name}"
                minHeight = a.dp(48)
                isChecked = t.enabled
                setOnCheckedChangeListener { _, checked ->
                    try {
                        MonitoringEngine.updateTasks { list ->
                            val idx = list.indexOfFirst { it.id == t.id }
                            check(idx >= 0) { "任务已删除" }
                            list[idx] = list[idx].copy(enabled = checked)
                        }
                    } catch (e: Exception) { toast(e.message ?: "保存失败") }
                    reload(); render(); updateEngineStatus()
                }
            })
            add(header)
            add(Ui.text(a, "触发  /  " + t.trigger.keywords.joinToString(" · ").ifEmpty { "未设置关键词" }, 14, R.color.fc_muted), 4)
            add(Ui.text(a, "${t.steps.size} 个步骤  ·  ${if (t.loop) "循环" else "单次"}  ·  ${if (t.mode == Task.MODE_DEBUG) "调试模式" else "标准模式"}  ·  v${t.revision}", 12, R.color.fc_muted), 10)
            t.tag?.let { add(Ui.badge(a, "标签 · $it"), 10) }
            if (t.id in renderedReviews) add(Ui.badge(a, "待验收 · 已暂停"), 10)
            add(Ui.row(a,
                Ui.button(a, "编辑流程", tone = "soft") {
                    editorLauncher.launch(Intent(a, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_ID, t.id))
                },
                Ui.button(a, "删除", tone = "quiet") { confirmDelete(t.id) }), 14)
        }
    }

    private fun confirmDelete(id: Long) {
        val t = tasks.firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(this)
            .setTitle("删除任务")
            .setMessage("确定删除「${t.name}」？")
            .setPositiveButton("删除") { _, _ ->
                try {
                    MonitoringEngine.updateTasks { it.removeAll { t -> t.id == id } }
                    AiStores.deleteTaskData(id)
                } catch (e: Exception) { toast(e.message ?: "删除失败") }
                reload()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateEngineStatus() {
        val status = if (MonitoringEngine.isMonitoring) {
            MonitoringEngine.currentTaskName?.let { "执行中 · $it" } ?: "监测中 · 等待触发"
        } else {
            "监测已停止"
        }
        if (tvEngineStatus.text.toString() != status) tvEngineStatus.text = status
        val count = "${tasks.size} 个任务 · ${tasks.count { it.enabled }} 个已启用"
        findViewById<TextView>(R.id.tvTaskCount).let { if (it.text.toString() != count) it.text = count }
        findViewById<Button>(R.id.btnStart).isEnabled = !MonitoringEngine.isMonitoring
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
