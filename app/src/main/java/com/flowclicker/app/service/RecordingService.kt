package com.flowclicker.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flowclicker.app.R
import com.flowclicker.app.ai.WakeDispatcher
import com.flowclicker.app.core.GestureDispatcher
import com.flowclicker.app.engine.Step
import com.flowclicker.app.engine.RecordingTiming
import com.flowclicker.app.core.ScreenControl
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.ContextThemeWrapper
import com.flowclicker.app.ui.Ui
import com.flowclicker.app.ui.Ui.add

/**
 * 序列录制：悬浮条 + 全屏触摸拦截层。
 *
 * 原理：用户的真实触摸落在拦截层上被记录；抬手后把这段手势经无障碍
 * GestureDispatcher 转发给下层应用，转发期间拦截层临时置 FLAG_NOT_TOUCHABLE，
 * 否则注入的手势会被自己拦下。因此目标应用画面正常推进，可连贯录制跨界面流程。
 *
 * 点击（位移<14px）记录为 Click（按压时长=实际按压时长），位移轨迹记录为
 * Swipe（时长=实际时长），相邻两个动作的间隔记录为 delayAfterMs。
 */
class RecordingService : Service() {

    private data class Sample(val x: Float, val y: Float)

    private class GestureRecord(
        val downT: Long,
        val upT: Long,
        val isTap: Boolean,
        val points: List<Pair<Float, Float>>,   // 绝对屏幕坐标采样
    )

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val records = mutableListOf<GestureRecord>()

    private var params: WindowManager.LayoutParams? = null
    private var overlayRoot: LinearLayout? = null
    private var tvTitle: TextView? = null
    private var touchOffset = IntArray(2)

    /** 待转发的手势数：>0 时拦截层保持不可触摸，全部完成后恢复 */
    private var pendingForwards = 0
    private var starting = false
    private var forwardJob: Job? = null
    private var wakeToken = 0L

    // 录制中的手势状态（主线程访问）
    private var downT = 0L
    private var startX = 0f
    private var startY = 0f
    private var maxDist = 0f
    private val samples = mutableListOf<Pair<Float, Float>>()
    private var lastSampleX = 0f
    private var lastSampleY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!starting) { starting = true; startNow() }
        return START_NOT_STICKY
    }

    private fun startNow() {
        wakeToken = WakeDispatcher.currentToken()
        createChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        scope.launch {
            try {
                ScreenControl.withOwner("recording") {
                    try {
                        check(GestureDispatcher.isReady) { "先开启无障碍服务" }
                        buildOverlay()
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { forwardJob?.cancelAndJoin() }
                        overlayRoot?.let { runCatching { wm.removeView(it) } }
                        overlayRoot = null
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@RecordingService, e.message, Toast.LENGTH_LONG).show() }
            finally { stopSelf() }
        }
    }

    private fun buildOverlay() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val themed = ContextThemeWrapper(this, R.style.Theme_FlowClicker)
        val bar = Ui.column(themed, 16).apply {
            id = R.id.recordPanel
            background = Ui.background(themed, R.color.fc_surface, 24, R.color.fc_outline)
            elevation = dp(8).toFloat()
        }
        tvTitle = Ui.text(themed, "●  录制中 · 0 步", 16, R.color.fc_primary, true)
        bar.add(tvTitle!!)
        bar.add(Ui.text(themed, "操作会实时转发到下层应用", 12, R.color.fc_muted), 2)
        bar.add(Ui.row(themed,
            Ui.button(themed, "主屏", R.id.recordHome, "quiet") { goHome() },
            Ui.button(themed, "完成", R.id.recordFinish) { finishRecording() },
            Ui.button(themed, "取消", R.id.recordCancel, "danger") { stopSelf() }), 10)

        val touch = View(this).apply {
            id = R.id.recordTouch
            contentDescription = "录制触摸区域"
            setBackgroundColor(0x08000000)
        }
        touch.setOnTouchListener { v, e -> onTouch(e, v) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels - dp(24), dp(380)), -2
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })
            addView(touch, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        params = p
        overlayRoot = root
        wm.addView(root, p)
        // Includes card height / margins and remains correct after overlay relayout.
        touch.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> touch.getLocationOnScreen(touchOffset) }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun onTouch(e: MotionEvent, v: View): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.getLocationOnScreen(touchOffset)
                downT = now
                startX = e.x
                startY = e.y
                maxDist = 0f
                samples.clear()
                lastSampleX = e.x
                lastSampleY = e.y
                samples.add((e.x + touchOffset[0]) to (e.y + touchOffset[1]))
            }

            MotionEvent.ACTION_MOVE -> {
                val d = Math.hypot((e.x - startX).toDouble(), (e.y - startY).toDouble())
                if (d > maxDist) maxDist = d.toFloat()
                // 采样去重：距上一个采样点超过 15px 才记录，控制注入路径长度
                if (Math.hypot((e.x - lastSampleX).toDouble(), (e.y - lastSampleY).toDouble()) > 15) {
                    lastSampleX = e.x
                    lastSampleY = e.y
                    samples.add((e.x + touchOffset[0]) to (e.y + touchOffset[1]))
                }
            }

            MotionEvent.ACTION_UP -> {
                // 转发窗口内到达的触摸是刚注入手势的"回声"（标志传播间隙被自己拦下），丢弃
                if (pendingForwards > 0) {
                    Log.i(TAG, "echo gesture dropped (forwarding in progress)")
                    return true
                }
                samples.add((e.x + touchOffset[0]) to (e.y + touchOffset[1]))
                val dur = (now - downT).coerceIn(40, 5000)
                val isTap = maxDist < 14f
                records.add(GestureRecord(downT, now, isTap, samples.toList()))
                tvTitle?.text = "●  录制中 · ${records.size} 步"
                Log.i(TAG, "gesture #${records.size} recorded: tap=$isTap dur=${dur}ms samples=${samples.size}")
                forward(isTap, dur)
            }
        }
        return true
    }

    private fun forward(isTap: Boolean, dur: Long) {
        val rec = records.last()
        pendingForwards++
        setTouchable(false)
        forwardJob = scope.launch {
            try {
                // NOT_TOUCHABLE 标志传播到输入管线需要一两帧，先等待再注入，
                // 否则转发手势会被自己的拦截层拦下
                delay(80)
                if (isTap) {
                    val (x, y) = rec.points.first()
                    check(GestureDispatcher.tap(x, y, dur)) { "转发点击失败" }
                } else {
                    check(GestureDispatcher.strokePath(rec.points, dur.coerceIn(80, 5000))) { "转发滑动失败" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "forward failed", e)
                records.remove(rec)
                tvTitle?.text = "转发失败 · ${records.size} 步"
            } finally {
                pendingForwards--
                if (pendingForwards <= 0) {
                    pendingForwards = 0
                    setTouchable(true)
                }
            }
        }
    }

    private fun setTouchable(touchable: Boolean) {
        val v = overlayRoot ?: return
        val p = params ?: return
        p.flags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun finishRecording() {
        Log.i(TAG, "finish requested, recorded=${records.size}")
        val steps = mutableListOf<Step>()
        val times = records.map { it.downT to it.upT }
        for ((index, r) in records.withIndex()) {
            val gap = RecordingTiming.delayAfter(times, index)
            val dur = r.upT - r.downT
            val first = r.points.first()
            val last = r.points.last()
            steps.add(
                if (r.isTap) {
                    Step.Click(
                        x = first.first, y = first.second,
                        maxOffsetPx = 4f,
                        pressMs = dur.coerceIn(40, 5000), pressJitterMs = 0,
                        delayAfterMs = gap, delayJitterMs = 0,
                    )
                } else {
                    Step.Swipe(
                        x1 = first.first, y1 = first.second,
                        x2 = last.first, y2 = last.second,
                        durationMs = dur.coerceIn(80, 5000), durationJitterMs = 0,
                        delayAfterMs = gap, delayJitterMs = 0,
                    )
                }
            )
        }
        pendingResult = steps
        lastRecording = steps
        if (steps.isNotEmpty()) WakeDispatcher.onRecordingFinished(steps.size, wakeToken)
        stopSelf()
    }

    override fun onDestroy() {
        overlayRoot?.let { runCatching { wm.removeView(it) } }
        overlayRoot = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("正在录制操作序列")
            .setContentText("在目标应用上操作，完成后点悬浮条上的「完成」")
            .setOngoing(true)
            .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "序列录制", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "record"
        private const val NOTIF_ID = 1002

        @Volatile
        private var pendingResult: List<Step>? = null

        fun takeResult(): List<Step>? = pendingResult.also { pendingResult = null }

        /** 最近一次完成录制的步骤（不消费），供 AI 调度员读取 */
        @Volatile
        var lastRecording: List<Step>? = null
            private set
    }
}
