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
import com.flowclicker.app.ai.WakeDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)
        cbEnabled = findViewById(R.id.cbEnabled)
        cbVlm = findViewById(R.id.cbVlm)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        etMaxRounds = findViewById(R.id.etMaxRounds)
        etDebugRounds = findViewById(R.id.etDebugRounds)
        etManual = findViewById(R.id.etManual)
        tvLog = findViewById(R.id.tvLog)

        fillSettings(AiStores.loadSettings())

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
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
                Toast.makeText(this, "AI 未启用：请先保存配置并勾选启用", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderLog()
    }

    private fun fillSettings(s: AiSettings) {
        cbEnabled.isChecked = s.enabled
        cbVlm.isChecked = s.vlm
        etBaseUrl.setText(s.baseUrl)
        etApiKey.setText(s.apiKey)
        etModel.setText(s.model)
        etMaxRounds.setText(s.maxToolRounds.toString())
        etDebugRounds.setText(s.debugRounds.toString())
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
        )
        AiStores.saveSettings(s)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun renderLog() {
        val log = AiStores.loadLog()
        tvLog.text = if (log.isEmpty()) {
            "暂无调度记录"
        } else {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
            log.reversed().joinToString("\n\n") { e: AiLogEntry ->
                "[${fmt.format(Date(e.time))}] ${e.eventType}（工具${e.toolCalls}次）\n${e.detail}\n→ ${e.reply}"
            }
        }
    }
}
