package com.flowclicker.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.flowclicker.app.R

/**
 * 屏幕采集前台服务：一个 MediaProjection 会话产出共享帧，
 * 所有任务的 OCR 都读同一份 [latestFrame]，而不是各自开录屏。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null

    @Volatile
    private var latestFrame: Bitmap? = null
    private val frameLock = Any()

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
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "media projection stopped by system")
                stopSelf()
            }
        }, Handler(mainLooper))

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        captureThread = HandlerThread("frame-capture").also { it.start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bmp = imageToBitmap(image)
                synchronized(frameLock) {
                    latestFrame?.recycle()
                    latestFrame = bmp
                }
            } catch (e: Exception) {
                Log.w(TAG, "frame conversion failed", e)
            } finally {
                image.close()
            }
        }, Handler(captureThread!!.looper))

        virtualDisplay = p.createVirtualDisplay(
            "flowclicker-capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        isRunning = true
        Log.i(TAG, "capture started ${width}x${height}")
        // 投屏 token 无法跨进程死亡复用，被杀后不该被系统用空 intent 自动重启
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
        captureThread?.quitSafely()
        captureThread = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** 返回当前帧的拷贝；原帧由采集线程在下一帧到达时回收，消费方无需管理生命周期 */
    fun currentFrame(): Bitmap? = synchronized(frameLock) {
        latestFrame?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        return if (rowPadding == 0) {
            Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(plane.buffer)
            }
        } else {
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / plane.pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            ).apply { copyPixelsFromBuffer(plane.buffer) }
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        }
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
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "capture"
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
