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

/** 任务列表：启停任务、启动/停止引擎、进入编辑器 */
class TaskListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEngineStatus: TextView
    private var tasks: MutableList<Task> = mutableListOf()
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
        setContentView(R.layout.activity_task_list)
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
        container.removeAllViews()
        tasks.sortedBy { it.priority }.forEach { container.addView(makeRow(it)) }
    }

    private fun makeRow(t: Task): LinearLayout {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val sw = Switch(this).apply {
            isChecked = t.enabled
            setOnCheckedChangeListener { _, checked ->
                try {
                    MonitoringEngine.updateTasks { list ->
                        val idx = list.indexOfFirst { it.id == t.id }
                        check(idx >= 0) { "任务已删除" }
                        list[idx] = list[idx].copy(enabled = checked)
                    }
                } catch (e: Exception) { toast(e.message ?: "保存失败") }
                reload(); updateEngineStatus()
            }
        }
        val tv = TextView(this).apply {
            text = buildString {
                append(t.name)
                t.tag?.let { append("\n标签：$it") }
                append("\n触发：")
                append(t.trigger.keywords.joinToString(" / ").ifEmpty { "（未设置）" })
                append(" · ${t.steps.size}步 · ")
                append(if (t.loop) "循环" else "单次")
                append(" · ${t.mode} · v${t.revision}")
                if (t.id in MonitoringEngine.pendingReviews()) append(" · 待验收/已暂停")
            }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                editorLauncher.launch(
                    Intent(this@TaskListActivity, TaskEditActivity::class.java)
                        .putExtra(TaskEditActivity.EXTRA_ID, t.id)
                )
            }
            setOnLongClickListener {
                confirmDelete(t.id)
                true
            }
        }
        row.addView(sw)
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
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
        tvEngineStatus.text = if (MonitoringEngine.isMonitoring) {
            "引擎：运行中${MonitoringEngine.currentTaskName?.let { " · 执行：$it" } ?: " · 监测/等待中"} · ${com.flowclicker.app.core.ScreenControl.owner ?: "空闲"}"
        } else {
            "引擎：已停止"
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
