package com.flowclicker.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.Step
import com.flowclicker.app.engine.Task
import com.flowclicker.app.engine.Trigger
import com.flowclicker.app.service.ClickerAccessibilityService
import com.flowclicker.app.service.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
            // 服务真正跑起来晚于 onResume，延迟刷新一次状态显示
            findViewById<TextView>(R.id.tvStatus).postDelayed({ updateStatus() }, 1500)
        }
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        findViewById<Button>(R.id.btnCaptureStart).setOnClickListener {
            val mpm =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.btnCaptureStop).setOnClickListener {
            ScreenCaptureService.stop(this)
            updateStatus()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { startTestTask() }
        findViewById<Button>(R.id.btnTasks).setOnClickListener {
            startActivity(Intent(this, com.flowclicker.app.ui.TaskListActivity::class.java))
        }
    }

    /**
     * 全链路验证任务：OCR 识别到主界面的"权限设置"字样后，
     * 点击"开启无障碍服务"按钮（坐标在测试启动时按当前屏幕位置计算）。
     * 预期效果：设置应用被自动打开。单次执行后任务自动停用。
     */
    private fun startTestTask() {
        if (ClickerAccessibilityService.instance == null) {
            Toast.makeText(this, "先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ScreenCaptureService.isRunning) {
            Toast.makeText(this, "先开启屏幕采集", Toast.LENGTH_SHORT).show()
            return
        }
        val target = findViewById<Button>(R.id.btnAccessibility)
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val testTask = Task(
            id = 1,
            name = "测试任务",
            trigger = Trigger(region = null, keywords = listOf("权限设置")),
            steps = listOf(
                Step.Click(
                    x = loc[0] + target.width / 2f,
                    y = loc[1] + target.height / 2f,
                    maxOffsetPx = 6f,
                    pressMs = 60,
                    delayAfterMs = 300
                )
            ),
            loop = false
        )
        MonitoringEngine.setTasks(listOf(testTask))
        MonitoringEngine.start()
        Toast.makeText(this, "测试任务运行中：识别到\"权限设置\"将自动点击", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val a11y = ClickerAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val capture = ScreenCaptureService.isRunning
        findViewById<TextView>(R.id.tvStatus).text = buildString {
            append("状态：")
            append(if (a11y) "无障碍✓ " else "无障碍✗ ")
            append(if (overlay) "悬浮窗✓ " else "悬浮窗✗ ")
            append(if (capture) "采集✓" else "采集✗")
        }
    }
}
