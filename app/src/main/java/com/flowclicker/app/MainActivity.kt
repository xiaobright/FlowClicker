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

class MainActivity : AppCompatActivity() {

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
        if (MonitoringEngine.isMonitoring || com.flowclicker.app.core.ScreenControl.owner != null) {
            Toast.makeText(this, "请先停止当前操作，再运行隔离测试", Toast.LENGTH_LONG).show()
            return
        }
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val testTask = Task(
            id = 1,
            name = "测试任务",
            trigger = Trigger(region = null, keywords = listOf("权限设置")),
            steps = listOf(
                Step.WaitText(text = "权限设置", timeoutMs = 5000),
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
        lifecycleScope.launch {
            try {
                val result = MonitoringEngine.preview(testTask)
                Toast.makeText(this@MainActivity, "隔离测试：${if (result.completed) "完成" else result.reason}，任务库未修改", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show() }
        }
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
            if (!captureNotificationsEnabled()) append(" · 通知停止入口不可见")
        }
    }
}
