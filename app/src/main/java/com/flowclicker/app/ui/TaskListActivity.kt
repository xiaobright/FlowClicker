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

/** 任务列表：启停任务、启动/停止引擎、进入编辑器 */
class TaskListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEngineStatus: TextView
    private var tasks: MutableList<Task> = mutableListOf()

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        reload()
        persistAndSync()
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
            MonitoringEngine.setTasks(tasks)
            MonitoringEngine.start()
            updateEngineStatus()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            MonitoringEngine.stop()
            updateEngineStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        render()
        updateEngineStatus()
    }

    private fun reload() {
        tasks = TaskStore.loadAll()
    }

    private fun persistAndSync() {
        TaskStore.saveAll(tasks)
        MonitoringEngine.setTasks(tasks)
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
                val idx = tasks.indexOfFirst { it.id == t.id }
                if (idx >= 0 && tasks[idx].enabled != checked) {
                    tasks[idx] = tasks[idx].copy(enabled = checked)
                    persistAndSync()
                    updateEngineStatus()
                }
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
                tasks.removeAll { it.id == id }
                persistAndSync()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateEngineStatus() {
        tvEngineStatus.text = if (MonitoringEngine.isMonitoring) {
            "引擎：运行中${MonitoringEngine.currentTaskName?.let { " · 执行：$it" } ?: " · 监测中"}"
        } else {
            "引擎：已停止"
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
