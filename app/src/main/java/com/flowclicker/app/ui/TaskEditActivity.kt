package com.flowclicker.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.flowclicker.app.R
import com.flowclicker.app.engine.Region
import com.flowclicker.app.engine.Step
import com.flowclicker.app.engine.Task
import com.flowclicker.app.engine.TaskStore
import com.flowclicker.app.engine.Trigger
import com.flowclicker.app.service.RecordingService

/** 任务编辑器：触发条件 + 动作序列（点击/滑动/等待/标签控制），步骤内支持坐标拾取 */
class TaskEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private var taskId: Long = -1L
    private val steps = mutableListOf<Step>()

    private lateinit var etName: EditText
    private lateinit var etTag: EditText
    private lateinit var etPriority: EditText
    private lateinit var cbLoop: CheckBox
    private lateinit var etKeywords: EditText
    private lateinit var etL: EditText
    private lateinit var etT: EditText
    private lateinit var etR: EditText
    private lateinit var etB: EditText
    private lateinit var llSteps: LinearLayout

    /** 当前打开的拾取回调：拾取页返回坐标后回填到弹窗输入框 */
    private var activePick: ((Float, Float) -> Unit)? = null

    private val pointPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val d = res.data
        val cb = activePick
        if (res.resultCode == RESULT_OK && d != null && cb != null) {
            cb(d.getFloatExtra("x", 0f), d.getFloatExtra("y", 0f))
        }
        activePick = null
    }

    private val regionPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val d = res.data
        if (res.resultCode == RESULT_OK && d != null) {
            etL.setText(d.getIntExtra("l", 0).toString())
            etT.setText(d.getIntExtra("t", 0).toString())
            etR.setText(d.getIntExtra("r", 0).toString())
            etB.setText(d.getIntExtra("b", 0).toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_edit)
        etName = findViewById(R.id.etName)
        etTag = findViewById(R.id.etTag)
        etPriority = findViewById(R.id.etPriority)
        cbLoop = findViewById(R.id.cbLoop)
        etKeywords = findViewById(R.id.etKeywords)
        etL = findViewById(R.id.etRegionL)
        etT = findViewById(R.id.etRegionT)
        etR = findViewById(R.id.etRegionR)
        etB = findViewById(R.id.etRegionB)
        llSteps = findViewById(R.id.llSteps)

        taskId = intent.getLongExtra(EXTRA_ID, -1L)
        if (taskId >= 0) {
            TaskStore.loadAll().firstOrNull { it.id == taskId }?.let { t ->
                etName.setText(t.name)
                etTag.setText(t.tag ?: "")
                etPriority.setText(t.priority.toString())
                cbLoop.isChecked = t.loop
                etKeywords.setText(t.trigger.keywords.joinToString(","))
                t.trigger.region?.let {
                    etL.setText(it.left.toString())
                    etT.setText(it.top.toString())
                    etR.setText(it.right.toString())
                    etB.setText(it.bottom.toString())
                }
                steps.addAll(t.steps)
            }
        }

        findViewById<Button>(R.id.btnAddClick).setOnClickListener { clickDialog(-1) }
        findViewById<Button>(R.id.btnAddSwipe).setOnClickListener { swipeDialog(-1) }
        findViewById<Button>(R.id.btnAddWait).setOnClickListener { waitDialog(-1) }
        findViewById<Button>(R.id.btnAddEnable).setOnClickListener { tagDialog(true, -1) }
        findViewById<Button>(R.id.btnAddDisable).setOnClickListener { tagDialog(false, -1) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        findViewById<Button>(R.id.btnPickRegion).setOnClickListener {
            regionPicker.launch(Intent(this, PickRegionActivity::class.java))
        }
        findViewById<Button>(R.id.btnClearRegion).setOnClickListener {
            listOf(etL, etT, etR, etB).forEach { it.text.clear() }
        }
        findViewById<Button>(R.id.btnRecord).setOnClickListener { startRecording() }

        renderSteps()
    }

    override fun onResume() {
        super.onResume()
        RecordingService.takeResult()?.let { recorded ->
            if (recorded.isNotEmpty()) {
                steps.addAll(recorded)
                renderSteps()
                Toast.makeText(this, "已追加 ${recorded.size} 步录制操作", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startRecording() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, RecordingService::class.java))
        Toast.makeText(
            this, "录制条已出现：切换到目标应用操作，完成后点「完成」", Toast.LENGTH_LONG
        ).show()
        moveTaskToBack(true)
    }

    private fun renderSteps() {
        llSteps.removeAllViews()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        steps.forEachIndexed { index, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val tv = TextView(this).apply {
                text = "${index + 1}. ${describe(s)}"
                textSize = 13f
                setOnClickListener { editStep(index) }
            }
            val del = Button(this).apply {
                text = "删"
                textSize = 12f
                setOnClickListener {
                    steps.removeAt(index)
                    renderSteps()
                }
            }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(del)
            llSteps.addView(row)
        }
    }

    private fun editStep(index: Int) {
        when (val s = steps[index]) {
            is Step.Click -> clickDialog(index)
            is Step.Swipe -> swipeDialog(index)
            is Step.Wait -> waitDialog(index)
            is Step.WaitText -> Toast.makeText(
                this, "等待文字步骤暂不支持界面编辑（可由 AI 调整或删除后重加）", Toast.LENGTH_SHORT
            ).show()
            is Step.EnableTagged -> tagDialog(true, index)
            is Step.DisableTagged -> tagDialog(false, index)
        }
    }

    private fun field(container: LinearLayout, label: String, value: String): EditText {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val tv = TextView(this).apply {
            text = label
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        val et = EditText(this).apply { setText(value) }
        container.addView(tv)
        container.addView(et)
        return et
    }

    private fun dialogContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 0)
    }

    private fun pickButton(container: LinearLayout, onPicked: (Float, Float) -> Unit): Button =
        Button(this).apply {
            text = "拾取坐标（去屏幕上点一下）"
            setOnClickListener {
                activePick = onPicked
                pointPicker.launch(Intent(this@TaskEditActivity, PickPointActivity::class.java))
            }
            container.addView(this)
        }

    private fun Float.str(): String = if (this == toInt().toFloat()) toInt().toString() else toString()

    private fun EditText.str(): String = text.toString()

    private fun clickDialog(index: Int) {
        val s = steps.getOrNull(index) as? Step.Click
        val c = dialogContainer()
        val eX = field(c, "X (px)", s?.x?.str() ?: "")
        val eY = field(c, "Y (px)", s?.y?.str() ?: "")
        val eAnchor = field(
            c,
            "OCR 锚点文字（可选，执行时按屏幕文字实时定位，未命中回退坐标）",
            s?.anchor ?: ""
        )
        val eOff = field(c, "随机偏移 ±px", s?.maxOffsetPx?.str() ?: "8")
        val ePress = field(c, "按压时长 ms", s?.pressMs?.toString() ?: "60")
        val ePressJ = field(c, "按压时长抖动 ±ms", s?.pressJitterMs?.toString() ?: "20")
        val eDelay = field(c, "点击后延时 ms", s?.delayAfterMs?.toString() ?: "500")
        val eDelayJ = field(c, "延时抖动 ±ms", s?.delayJitterMs?.toString() ?: "200")
        pickButton(c) { x, y ->
            eX.setText(x.toInt().toString())
            eY.setText(y.toInt().toString())
        }
        AlertDialog.Builder(this)
            .setTitle(if (s == null) "新增点击" else "编辑点击")
            .setView(c)
            .setPositiveButton("确定") { _, _ ->
                val step = Step.Click(
                    x = eX.str().toFloatOrNull() ?: 0f,
                    y = eY.str().toFloatOrNull() ?: 0f,
                    anchor = eAnchor.str().trim().ifEmpty { null },
                    maxOffsetPx = eOff.str().toFloatOrNull() ?: 0f,
                    pressMs = ePress.str().toLongOrNull() ?: 60L,
                    pressJitterMs = ePressJ.str().toLongOrNull() ?: 0L,
                    delayAfterMs = eDelay.str().toLongOrNull() ?: 500L,
                    delayJitterMs = eDelayJ.str().toLongOrNull() ?: 0L,
                )
                commitStep(step, index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun swipeDialog(index: Int) {
        val s = steps.getOrNull(index) as? Step.Swipe
        val c = dialogContainer()
        val eX1 = field(c, "起点 X (px)", s?.x1?.str() ?: "")
        val eY1 = field(c, "起点 Y (px)", s?.y1?.str() ?: "")
        val eX2 = field(c, "终点 X (px)", s?.x2?.str() ?: "")
        val eY2 = field(c, "终点 Y (px)", s?.y2?.str() ?: "")
        val eDur = field(c, "滑动时长 ms", s?.durationMs?.toString() ?: "300")
        val eDurJ = field(c, "滑动时长抖动 ±ms", s?.durationJitterMs?.toString() ?: "80")
        val eDelay = field(c, "滑动后延时 ms", s?.delayAfterMs?.toString() ?: "500")
        val eDelayJ = field(c, "延时抖动 ±ms", s?.delayJitterMs?.toString() ?: "200")
        AlertDialog.Builder(this)
            .setTitle(if (s == null) "新增滑动" else "编辑滑动")
            .setView(c)
            .setPositiveButton("确定") { _, _ ->
                val step = Step.Swipe(
                    x1 = eX1.str().toFloatOrNull() ?: 0f,
                    y1 = eY1.str().toFloatOrNull() ?: 0f,
                    x2 = eX2.str().toFloatOrNull() ?: 0f,
                    y2 = eY2.str().toFloatOrNull() ?: 0f,
                    durationMs = eDur.str().toLongOrNull() ?: 300L,
                    durationJitterMs = eDurJ.str().toLongOrNull() ?: 0L,
                    delayAfterMs = eDelay.str().toLongOrNull() ?: 500L,
                    delayJitterMs = eDelayJ.str().toLongOrNull() ?: 0L,
                )
                commitStep(step, index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun waitDialog(index: Int) {
        val s = steps.getOrNull(index) as? Step.Wait
        val c = dialogContainer()
        val eMs = field(c, "等待时长 ms", s?.ms?.toString() ?: "1000")
        AlertDialog.Builder(this)
            .setTitle(if (s == null) "新增等待" else "编辑等待")
            .setView(c)
            .setPositiveButton("确定") { _, _ ->
                commitStep(Step.Wait(eMs.str().toLongOrNull() ?: 1000L), index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun tagDialog(enable: Boolean, index: Int) {
        val existing: String? = when {
            enable -> (steps.getOrNull(index) as? Step.EnableTagged)?.tag
            else -> (steps.getOrNull(index) as? Step.DisableTagged)?.tag
        }
        val c = dialogContainer()
        val eTag = field(c, "标签名（要启用/停用的任务标签组）", existing ?: "")
        AlertDialog.Builder(this)
            .setTitle(if (enable) "启用标签组" else "停用标签组")
            .setView(c)
            .setPositiveButton("确定") { _, _ ->
                val tag = eTag.text.toString().trim()
                if (tag.isEmpty()) return@setPositiveButton
                val step = if (enable) Step.EnableTagged(tag) else Step.DisableTagged(tag)
                commitStep(step, index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun commitStep(step: Step, index: Int) {
        if (index in steps.indices) steps[index] = step else steps.add(step)
        renderSteps()
    }

    private fun save() {
        val all = TaskStore.loadAll()
        val keywords = etKeywords.text.toString()
            .split(',', '，', ';', '；', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val region = listOf(etL, etT, etR, etB).map { it.text.toString().trim().toIntOrNull() }
        val regionObj = if (region.all { it != null }) {
            Region(region[0]!!, region[1]!!, region[2]!!, region[3]!!)
        } else null
        val tag = etTag.text.toString().trim().ifEmpty { null }
        val task = Task(
            id = if (taskId >= 0) taskId else TaskStore.nextId(all),
            name = etName.text.toString().trim().ifEmpty { "未命名任务" },
            priority = etPriority.text.toString().toIntOrNull() ?: 0,
            tag = tag,
            trigger = Trigger(region = regionObj, keywords = keywords),
            steps = steps.toList(),
            enabled = all.firstOrNull { it.id == taskId }?.enabled ?: true,
            loop = cbLoop.isChecked,
        )
        val idx = all.indexOfFirst { it.id == task.id }
        if (idx >= 0) all[idx] = task else all.add(task)
        TaskStore.saveAll(all)
        finish()
    }

    private fun describe(s: Step): String = when (s) {
        is Step.Click -> buildString {
            append("点击(${s.x.toInt()},${s.y.toInt()})")
            if (!s.anchor.isNullOrBlank()) append(" 锚「${s.anchor}」")
            if (s.maxOffsetPx > 0) append(" 偏移±${s.maxOffsetPx.toInt()}")
            append(" 压${s.pressMs}±${s.pressJitterMs}ms 延${s.delayAfterMs}±${s.delayJitterMs}ms")
        }

        is Step.Swipe -> "滑动(${s.x1.toInt()},${s.y1.toInt()}→${s.x2.toInt()},${s.y2.toInt()})" +
                " ${s.durationMs}±${s.durationJitterMs}ms 延${s.delayAfterMs}±${s.delayJitterMs}ms"

        is Step.Wait -> "等待 ${s.ms}ms"
        is Step.WaitText ->
            "等待「${s.text}」${if (s.present) "出现" else "消失"} · 超时${s.timeoutMs}ms"
        is Step.EnableTagged -> "启用标签组「${s.tag}」"
        is Step.DisableTagged -> "停用标签组「${s.tag}」"
    }
}
