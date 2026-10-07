package com.flowclicker.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.flowclicker.app.R
import com.flowclicker.app.ai.AiLogEntry
import com.flowclicker.app.ai.AiSettings
import com.flowclicker.app.ai.AiStores
import com.flowclicker.app.ai.JudgeSettings
import com.flowclicker.app.ai.WakeDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Handler
import android.os.Looper
import android.view.View

/** AI 调度员页面：API 配置、手动唤醒、调度日志 */
class AiActivity : AppCompatActivity() {

    private lateinit var cbEnabled: CheckBox
    private lateinit var cbVlm: CheckBox
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etMaxRounds: EditText
    private lateinit var etDebugRounds: EditText
    private lateinit var etManual: EditText
    private lateinit var cbJudgeEnabled: CheckBox
    private lateinit var cbJudgeObserve: CheckBox
    private lateinit var etJudgeBaseUrl: EditText
    private lateinit var etJudgeApiKey: EditText
    private lateinit var etJudgeModel: EditText
    private lateinit var etJudgeConfidence: EditText
    private lateinit var tvLog: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { renderLog(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Screens.ai(this)
        cbEnabled = findViewById(R.id.cbEnabled)
        cbVlm = findViewById(R.id.cbVlm)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        etMaxRounds = findViewById(R.id.etMaxRounds)
        etDebugRounds = findViewById(R.id.etDebugRounds)
        etManual = findViewById(R.id.etManual)
        cbJudgeEnabled = findViewById(R.id.cbJudgeEnabled)
        cbJudgeObserve = findViewById(R.id.cbJudgeObserve)
        etJudgeBaseUrl = findViewById(R.id.etJudgeBaseUrl)
        etJudgeApiKey = findViewById(R.id.etJudgeApiKey)
        etJudgeModel = findViewById(R.id.etJudgeModel)
        etJudgeConfidence = findViewById(R.id.etJudgeConfidence)
        tvLog = findViewById(R.id.tvLog)

        fillSettings(AiStores.loadSettings())
        val panel = findViewById<View>(R.id.aiSettingsPanel)
        val configured = AiStores.loadSettings().let { it.baseUrl.isNotBlank() && it.model.isNotBlank() }
        panel.visibility = if (savedInstanceState?.getBoolean("settingsOpen") ?: !configured) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnAiSettings).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnStopAi).setOnClickListener {
            WakeDispatcher.stopAll()
            stopService(android.content.Intent(this, com.flowclicker.app.service.RecordingService::class.java))
            renderLog()
        }
        findViewById<Button>(R.id.btnWake).setOnClickListener {
            val text = etManual.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "先输入指令", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (WakeDispatcher.manual(text)) {
                etManual.text.clear()
                Toast.makeText(this, "已唤醒（结果稍后出现在日志里）", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "未入队：请检查已保存配置；队列可能已满或指令重复", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("settingsOpen", findViewById<View>(R.id.aiSettingsPanel).visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    private fun fillSettings(s: AiSettings) {
        cbEnabled.isChecked = s.enabled
        cbVlm.isChecked = s.vlm
        etBaseUrl.setText(s.baseUrl)
        etApiKey.setText(s.apiKey)
        etModel.setText(s.model)
        etMaxRounds.setText(s.maxToolRounds.toString())
        etDebugRounds.setText(s.debugRounds.toString())
        cbJudgeEnabled.isChecked = s.judge.enabled
        cbJudgeObserve.isChecked = s.judge.observeOnly
        etJudgeBaseUrl.setText(s.judge.baseUrl)
        etJudgeApiKey.setText(s.judge.apiKey)
        etJudgeModel.setText(s.judge.model)
        etJudgeConfidence.setText(s.judge.minConfidence.toString())
    }

    private fun save() {
        val s = AiSettings(
            enabled = cbEnabled.isChecked,
            vlm = cbVlm.isChecked,
            baseUrl = etBaseUrl.text.toString().trim(),
            apiKey = etApiKey.text.toString().trim(),
            model = etModel.text.toString().trim(),
            maxToolRounds = etMaxRounds.text.toString().toIntOrNull()?.coerceIn(1, 12) ?: 6,
            debugRounds = etDebugRounds.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 2,
            judge = JudgeSettings(
                enabled = cbJudgeEnabled.isChecked,
                observeOnly = cbJudgeObserve.isChecked,
                baseUrl = etJudgeBaseUrl.text.toString().trim(),
                apiKey = etJudgeApiKey.text.toString().trim(),
                model = etJudgeModel.text.toString().trim(),
                minConfidence = etJudgeConfidence.text.toString().toDoubleOrNull() ?: Double.NaN,
            ),
        )
        try {
            AiStores.saveSettings(s)
            WakeDispatcher.onSettingsChanged()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }

    private fun renderLog() {
        val log = AiStores.loadLog()
        val body = if (log.isEmpty()) {
            "暂无调度记录"
        } else {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
            log.reversed().joinToString("\n\n") { e: AiLogEntry ->
                "[${fmt.format(Date(e.time))}] ${e.eventType}（工具${e.toolCalls}次）\n${e.detail}\n→ ${e.reply}"
            }
        }
        val memory = AiStores.loadMemory()
        val memBlock = if (memory.isEmpty()) "" else
            "【AI 长期记忆】\n" + memory.joinToString("\n") { "· ${it.text}" } + "\n\n————————\n\n"
        val rendered = memBlock + body
        if (tvLog.text.toString() != rendered) tvLog.text = rendered
        val status = if (!AiStores.loadSettings().enabled) "尚未启用" else WakeDispatcher.status
        findViewById<TextView>(R.id.tvAiStatus).let { if (it.text.toString() != status) it.text = status }
    }
}
