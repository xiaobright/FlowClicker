package com.flowclicker.app.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * 全局手势派发器。
 *
 * 系统限制：一个无障碍服务同一时刻只能派发一个手势，后派发的会取消前一个，
 * 因此所有点击/滑动必须经过这里的 Mutex 串行执行。多任务"并行监测、互斥执行"
 * 的语义在系统层就是靠这个串行化保证的。
 */
object GestureDispatcher {

    private val mutex = Mutex()

    @Volatile
    private var service: AccessibilityService? = null

    fun attach(service: AccessibilityService) {
        this.service = service
    }

    fun detach(service: AccessibilityService) {
        if (this.service === service) this.service = null
    }

    val isReady: Boolean
        get() = service != null

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean =
        strokePath(listOf(x to y), durationMs)

    suspend fun swipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long
    ): Boolean = strokePath(listOf(x1 to y1, x2 to y2), durationMs)

    /** 按采样点序列派发手势（单点=点击），录制转发与复杂轨迹共用 */
    suspend fun strokePath(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        val svc = service ?: return false
        if (points.isEmpty()) return false
        return mutex.withLock {
            val path = Path().apply {
                points.forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.first, p.second) else lineTo(p.first, p.second)
                }
            }
            suspendCancellableCoroutine { cont ->
                val dispatched = svc.dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path, 0, durationMs.coerceAtLeast(1)
                            )
                        )
                        .build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(g: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        }
    }
}
