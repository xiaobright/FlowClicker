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
import com.flowclicker.app.ai.WakeDispatcher
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import android.os.Build
import android.media.projection.MediaProjectionConfig
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.flowclicker.app.ui.Screens

class MainActivity : AppCompatActivity() {
    private val statusHandler = Handler(Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() {
            updateStatus()
            statusHandler.postDelayed(this, 500)
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // A denial leads to one explicit choice, never a re-request loop.
        if (captureNotificationsEnabled()) launchProjection() else warnNotificationUnavailable()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
        }
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Screens.home(this)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.btnCaptureStart).setOnClickListener {
            requestCapture()
        }
        findViewById<Button>(R.id.btnCaptureStop).setOnClickListener {
            WakeDispatcher.stopAll()
            ScreenCaptureService.stop(this)
            updateStatus()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { startTestTask() }
        findViewById<Button>(R.id.btnEmergencyStop).setOnClickListener {
            WakeDispatcher.stopAll()
            stopService(Intent(this, com.flowclicker.app.service.RecordingService::class.java))
            Toast.makeText(this, "已停止；已派发的短手势可能仍在收尾", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnTasks).setOnClickListener {
            startActivity(Intent(this, com.flowclicker.app.ui.TaskListActivity::class.java))
        }
        findViewById<Button>(R.id.btnAi).setOnClickListener {
            startActivity(Intent(this, com.flowclicker.app.ui.AiActivity::class.java))
        }
    }

    private fun requestCapture() {
        if (ScreenCaptureService.isRunning) {
            Toast.makeText(this, "屏幕采集已开启", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences("permission_ui", MODE_PRIVATE)
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        when (captureNotificationAction(Build.VERSION.SDK_INT, granted,
            prefs.getBoolean("notifications_asked", false), captureNotificationsEnabled())) {
            CaptureNotificationAction.REQUEST -> {
                prefs.edit().putBoolean("notifications_asked", true).apply()
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            CaptureNotificationAction.WARN -> warnNotificationUnavailable()
            CaptureNotificationAction.START -> launchProjection()
        }
    }

    private fun captureNotificationsEnabled(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            manager.getNotificationChannel(ScreenCaptureService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun warnNotificationUnavailable() {
        AlertDialog.Builder(this)
            .setTitle("通知停止入口不可见")
            .setMessage("通知权限或屏幕采集通知渠道已关闭。建议先开启；若仍继续，只能返回应用点击“停止全部自动操作”，或使用系统录屏停止入口。")
            .setPositiveButton("去设置") { _, _ ->
                val manager = getSystemService(NotificationManager::class.java)
                val channelBlocked = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
                    manager.getNotificationChannel(ScreenCaptureService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
                startActivity(Intent(if (channelBlocked) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                    else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, ScreenCaptureService.CHANNEL_ID))
            }
            .setNeutralButton("仍然继续") { _, _ -> launchProjection() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent())
    }

    /**
     * 全链路验证任务：OCR 识别到主界面的"无障碍"字样后，
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
        if (MonitoringEngine.isMonitoring || com.flowclicker.app.core.ScreenControl.owner != null) {
            Toast.makeText(this, "请先停止当前操作，再运行隔离测试", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            // The test action is below the fold. Reveal its target before measuring coordinates.
            target.requestRectangleOnScreen(android.graphics.Rect(0, 0, target.width, target.height), true)
            kotlinx.coroutines.delay(250)
            val loc = IntArray(2)
            target.getLocationOnScreen(loc)
            val testTask = Task(
                id = 1,
                name = "测试任务",
                trigger = Trigger(region = null, keywords = listOf("无障碍")),
                steps = listOf(
                    Step.WaitText(text = "无障碍", timeoutMs = 5000),
                    Step.Click(
                        x = loc[0] + target.width / 2f,
                        y = loc[1] + target.height / 2f,
                        maxOffsetPx = 6f,
                        pressMs = 60,
                        delayAfterMs = 300
                    ),
                    Step.WaitText(text = "无障碍", timeoutMs = 10000)
                ),
                loop = false
            )
            try {
                val result = MonitoringEngine.preview(testTask)
                Toast.makeText(this@MainActivity, "隔离测试：${if (result.completed) "完成" else result.reason}，任务库未修改", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(refreshStatus)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    private fun updateStatus() {
        val a11y = ClickerAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val capture = ScreenCaptureService.isRunning
        fun label(id: Int, value: String) {
            findViewById<TextView>(id).let { if (it.text.toString() != value) it.text = value }
        }
        val tasks = MonitoringEngine.tasksSnapshot()
        label(R.id.tvHomeTitle, when {
            MonitoringEngine.isMonitoring -> "流程正在运行"
            capture && a11y -> "已就绪，随时开始"
            capture -> "屏幕采集中"
            else -> "准备开始"
        })
        label(R.id.tvStatus, when {
            MonitoringEngine.isMonitoring -> MonitoringEngine.currentTaskName ?: "正在观察屏幕，等待触发条件"
            capture -> "屏幕已连接，前往任务页启动监测"
            else -> "当前没有采集屏幕或执行任务"
        })
        label(R.id.tvTaskSummary, "${tasks.size} 个任务   /   ${tasks.count { it.enabled }} 个已启用")
        label(R.id.tvAccessStatus, if (a11y) "已连接 · 可以派发点击和滑动" else "未连接 · 自动操作需要此权限")
        label(R.id.tvOverlayStatus, if (overlay) "已授权 · 可以使用悬浮录制面板" else "未授权 · 用于显示悬浮录制面板")
        label(R.id.tvCaptureStatus, (if (capture) "采集中 · 屏幕内容正在提供给 OCR" else "未开启 · 开启时需确认系统授权") +
            if (!captureNotificationsEnabled()) "\n通知停止入口不可见，请检查通知设置。" else "")
        label(R.id.btnAccessibility, if (a11y) "管理无障碍服务" else "开启无障碍服务")
        label(R.id.btnOverlay, if (overlay) "管理悬浮窗权限" else "授予悬浮窗权限")
        findViewById<Button>(R.id.btnCaptureStart).isEnabled = !capture
        findViewById<Button>(R.id.btnCaptureStop).isEnabled = capture
    }
}
