package com.flowclicker.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.flowclicker.app.R
import com.flowclicker.app.ai.WakeDispatcher
import android.content.res.Configuration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 屏幕采集前台服务：一个 MediaProjection 会话产出共享帧，
 * 所有任务的 OCR 都读同一个采集 worker 的独立帧拷贝。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var worker: CaptureWorker? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 契约：必须先调 startForeground 再做任何提前退出，
        // 否则系统立即抛 RemoteServiceException
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultData = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (isRunning) return START_NOT_STICKY
        if (resultData == null) {
            Log.w(TAG, "missing projection result data, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(resultCode, resultData)
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection !== p) return
                Log.i(TAG, "media projection stopped by system")
                worker?.invalidateFrame()
                WakeDispatcher.stopAll()
                stopSelf()
            }
        }
        projectionCallback = callback
        p.registerCallback(callback, Handler(mainLooper))

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        try {
            val capture = CaptureWorker(width, height)
            worker = capture
            virtualDisplay = p.createVirtualDisplay(
                "flowclicker-capture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                capture.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "capture startup failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        Log.i(TAG, "capture started ${width}x${height}")
        // 投屏 token 无法跨进程死亡复用，被杀后不该被系统用空 intent 自动重启
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WakeDispatcher.stopAll()
        isRunning = false
        if (instance === this) instance = null
        val capture = worker
        worker = null
        val display = virtualDisplay
        virtualDisplay = null
        val p = projection
        projection = null
        val callback = projectionCallback
        projectionCallback = null
        val releaseProjection = {
            runCatching { display?.release() }.onFailure { Log.w(TAG, "display release failed", it) }
            runCatching { if (callback != null) p?.unregisterCallback(callback) }
                .onFailure { Log.w(TAG, "callback removal failed", it) }
            runCatching { p?.stop() }.onFailure { Log.w(TAG, "projection stop failed", it) }
            Unit
        }
        if (capture != null) capture.close(releaseProjection) else releaseProjection()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WakeDispatcher.stopAll()
        stopSelf()
    }

    /** 返回独立拷贝，消费方必须 recycle。 */
    fun currentFrame(): Bitmap? = worker?.currentFrame()

    fun invalidateFrame() { worker?.invalidateFrame() }

    suspend fun awaitActionFrame() {
        val capture = worker ?: error("屏幕采集不可用")
        val ready = withTimeoutOrNull(5000) {
            while (worker === capture && !capture.hasRecentFrame()) {
                currentCoroutineContext().ensureActive()
                delay(50)
            }
            worker === capture && capture.hasRecentFrame()
        } ?: false
        check(ready) { "没有近期有效画面，已暂停操作；请确认采集或重新授权" }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "屏幕采集", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("屏幕采集中")
            .setContentText("流程点击器正在监测屏幕内容")
            .addAction(R.drawable.ic_stat_notify, "停止全部操作", android.app.PendingIntent.getBroadcast(
                this, 0, Intent(this, StopAutomationReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            ))
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "ScreenCapture"
        const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1001
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
