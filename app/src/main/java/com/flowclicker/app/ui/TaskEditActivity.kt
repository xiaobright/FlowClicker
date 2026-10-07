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
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.core.GestureDispatcher
import com.flowclicker.app.ui.Ui.add
import com.flowclicker.app.ui.Ui.dp
import android.widget.ScrollView
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 任务编辑器：触发条件 + 动作序列（点击/滑动/等待/标签控制），步骤内支持坐标拾取 */
class TaskEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private var taskId: Long = -1L
    private var original: Task? = null
    private val steps = mutableListOf<Step>()
    private var initialDraft = ""

    private lateinit var etName: EditText
    private lateinit var etTag: EditText
    private lateinit var etRecoveryHint: EditText
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
        Screens.editor(this, intent.getLongExtra(EXTRA_ID, -1L) >= 0)
        etName = findViewById(R.id.etName)
        etTag = findViewById(R.id.etTag)
        etRecoveryHint = findViewById(R.id.etRecoveryHint)
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
            MonitoringEngine.tasksSnapshot().firstOrNull { it.id == taskId }?.let { t ->
                original = t
                etName.setText(t.name)
                etTag.setText(t.tag ?: "")
                etRecoveryHint.setText(t.recoveryHint)
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
        // Draft steps are not part of Android's automatic EditText state restoration.
        savedInstanceState?.getString("draftSteps")?.let {
            steps.clear()
            steps.addAll(Json.decodeFromString<List<Step>>(it))
        }
        savedInstanceState?.getString("originalTask")?.let { original = Json.decodeFromString<Task>(it) }
        initialDraft = savedInstanceState?.getString("initialDraft") ?: draftSignature()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (draftSignature() == initialDraft) finish()
                else AlertDialog.Builder(this@TaskEditActivity)
                    .setTitle("放弃未保存的修改？")
                    .setMessage("当前草稿还没有写入任务库。")
                    .setPositiveButton("放弃修改") { _, _ -> finish() }
                    .setNegativeButton("继续编辑", null).show()
            }
        })

        findViewById<Button>(R.id.btnAddClick).setOnClickListener { clickDialog(-1) }
        findViewById<Button>(R.id.btnAddSwipe).setOnClickListener { swipeDialog(-1) }
        findViewById<Button>(R.id.btnAddWait).setOnClickListener { waitDialog(-1) }
        findViewById<Button>(R.id.btnAddWaitText).setOnClickListener { waitTextDialog(-1) }
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draftSteps", Json.encodeToString(steps.toList()))
        original?.let { outState.putString("originalTask", Json.encodeToString(it)) }
        outState.putString("initialDraft", initialDraft)
        super.onSaveInstanceState(outState)
    }

    private fun draftSignature() = listOf(etName, etTag, etRecoveryHint, etPriority, etKeywords, etL, etT, etR, etB)
        .joinToString("\u0000") { it.text.toString() } + cbLoop.isChecked + Json.encodeToString(steps.toList())

    private fun startRecording() {
        if (!GestureDispatcher.isReady) {
            Toast.makeText(this, "先开启无障碍服务", Toast.LENGTH_LONG).show()
            return
        }
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
        findViewById<TextView>(R.id.tvStepCount).text = "${steps.size} 个步骤"
        if (steps.isEmpty()) {
            Ui.card(llSteps, 10) {
                add(Ui.text(this@TaskEditActivity, "还没有动作", 16, bold = true))
                add(Ui.text(this@TaskEditActivity, "从下方选择动作，或录制一段操作。", 13, R.color.fc_muted), 6)
            }
        }
        steps.forEachIndexed { index, s ->
            Ui.card(llSteps, 10, padding = 16) {
                add(Ui.text(this@TaskEditActivity, "${(index + 1).toString().padStart(2, '0')}  /  ${stepTitle(s)}", 16, bold = true))
                add(Ui.text(this@TaskEditActivity, describe(s), 13, R.color.fc_muted), 6)
                val more = Ui.button(this@TaskEditActivity, "更多", tone = "quiet")
                more.contentDescription = "步骤 ${index + 1} 更多操作"
                more.setOnClickListener {
                    PopupMenu(this@TaskEditActivity, more).apply {
                        if (index > 0) menu.add("上移")
                        if (index < steps.lastIndex) menu.add("下移")
                        menu.add("删除")
                        setOnMenuItemClickListener { item ->
                            when (item.title.toString()) {
                                "上移" -> { java.util.Collections.swap(steps, index, index - 1); renderSteps() }
                                "下移" -> { java.util.Collections.swap(steps, index, index + 1); renderSteps() }
                                "删除" -> AlertDialog.Builder(this@TaskEditActivity)
                                    .setTitle("删除第 ${index + 1} 步？")
                                    .setPositiveButton("删除") { _, _ -> steps.removeAt(index); renderSteps() }
                                    .setNegativeButton("取消", null).show()
                            }
                            true
                        }
                    }.show()
                }
                add(Ui.row(this@TaskEditActivity,
                    Ui.button(this@TaskEditActivity, "编辑步骤", tone = "soft") { editStep(index) }, more), 12)
            }
        }
    }

    private fun stepTitle(s: Step) = when (s) {
        is Step.Click -> "点击"
        is Step.Swipe -> "滑动"
        is Step.Wait -> "等待"
        is Step.WaitText -> "文字验证"
        is Step.EnableTagged -> "启用标签组"
        is Step.DisableTagged -> "停用标签组"
    }

    private fun editStep(index: Int) {
        when (val s = steps[index]) {
            is Step.Click -> clickDialog(index)
            is Step.Swipe -> swipeDialog(index)
            is Step.Wait -> waitDialog(index)
            is Step.WaitText -> waitTextDialog(index)
            is Step.EnableTagged -> tagDialog(true, index)
            is Step.DisableTagged -> tagDialog(false, index)
        }
    }

    private fun field(container: LinearLayout, label: String, value: String): EditText {
        return Ui.field(container, label, android.view.View.generateViewId(), value)
    }

    private fun dialogContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(16))
    }

    private fun scroll(container: LinearLayout) = ScrollView(this).apply { addView(container) }

    private fun pickButton(container: LinearLayout, onPicked: (Float, Float) -> Unit): Button =
        Ui.button(this, "从屏幕拾取坐标", tone = "soft").apply {
            setOnClickListener {
                activePick = onPicked
                pointPicker.launch(Intent(this@TaskEditActivity, PickPointActivity::class.java))
            }
            container.add(this, 12)
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
            "OCR 锚点文字（可选，默认未命中/不唯一则停止）",
            s?.anchor ?: ""
        )
        val fallback = CheckBox(this).apply {
            text = "锚点缺失/不唯一时仍使用旧坐标（有误点风险）"
            isChecked = s?.anchorFallback ?: false
        }
        c.addView(fallback)
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
            .setView(scroll(c))
            .setPositiveButton("确定") { _, _ ->
                val step = Step.Click(
                    x = eX.str().toFloatOrNull() ?: 0f,
                    y = eY.str().toFloatOrNull() ?: 0f,
                    anchor = eAnchor.str().trim().ifEmpty { null },
                    anchorFallback = fallback.isChecked,
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
            .setView(scroll(c))
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
            .setView(scroll(c))
            .setPositiveButton("确定") { _, _ ->
                commitStep(Step.Wait(eMs.str().toLongOrNull() ?: 1000L), index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun waitTextDialog(index: Int) {
        val s = steps.getOrNull(index) as? Step.WaitText
        val c = dialogContainer()
        val text = field(c, "等待文字（最后一步可作为结果验证）", s?.text ?: "")
        val timeout = field(c, "超时 ms（500..120000）", (s?.timeoutMs ?: 10000).toString())
        val present = CheckBox(this).apply { this.text = "等待出现（不勾选=等待消失）"; isChecked = s?.present ?: true }
        c.addView(present)
        AlertDialog.Builder(this).setTitle("等待/验证文字").setView(scroll(c))
            .setPositiveButton("确定") { _, _ ->
                commitStep(Step.WaitText(text.str().trim(), present.isChecked,
                    timeout.str().toLongOrNull() ?: 10000, s?.region), index)
            }.setNegativeButton("取消", null).show()
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
            .setView(scroll(c))
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
        val keywords = etKeywords.text.toString()
            .split(',', '，', ';', '；', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val region = listOf(etL, etT, etR, etB).map { it.text.toString().trim().toIntOrNull() }
        val regionObj = if (region.all { it != null }) {
            Region(region[0]!!, region[1]!!, region[2]!!, region[3]!!)
        } else null
        val tag = etTag.text.toString().trim().ifEmpty { null }
        val draft = Task(
            id = taskId,
            name = etName.text.toString().trim().ifEmpty { "未命名任务" },
            priority = etPriority.text.toString().toIntOrNull() ?: 0,
            tag = tag,
            recoveryHint = etRecoveryHint.text.toString().trim(),
            trigger = Trigger(region = regionObj, keywords = keywords, ignoreCase = original?.trigger?.ignoreCase ?: true),
            steps = steps.toList(),
            loop = cbLoop.isChecked,
            mode = original?.mode ?: Task.MODE_NORMAL,
        )
        try {
            require(region.all { it == null } || region.all { it != null }) { "检测区域需完整填写四个数值" }
            MonitoringEngine.updateTasks { all ->
                val idx = all.indexOfFirst { it.id == taskId }
                if (taskId >= 0) {
                    check(idx >= 0) { "任务已被删除，请返回列表重新创建" }
                    val current = all[idx]
                    check(current.revision == original?.revision) { "任务已被 AI 修改，请返回重开，避免覆盖" }
                    all[idx] = draft.copy(enabled = current.enabled, mode = current.mode, revision = current.revision)
                } else all.add(draft.copy(id = TaskStore.nextId(all)))
            }
            finish()
        } catch (e: Exception) { Toast.makeText(this, e.message ?: "保存失败", Toast.LENGTH_LONG).show() }
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
        is Step.WaitText -> "等待「${s.text}」${if (s.present) "出现" else "消失"} ≤${s.timeoutMs}ms"
        is Step.EnableTagged -> "启用标签组「${s.tag}」"
        is Step.DisableTagged -> "停用标签组「${s.tag}」"
    }
}
