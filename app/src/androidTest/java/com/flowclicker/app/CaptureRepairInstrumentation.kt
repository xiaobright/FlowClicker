package com.flowclicker.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import com.flowclicker.app.service.CaptureWorker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.content.Intent
import android.content.Context
import android.widget.Button
import com.flowclicker.app.service.ScreenCaptureService
import kotlinx.coroutines.*

/** Real ImageReader/HandlerThread tests; no user tasks, AI settings, or projection needed. */
class CaptureRepairInstrumentation : Instrumentation() {
    private var suite = "capture"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        suite = arguments?.getString("suite") ?: "capture"
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            // onCreate starts this thread before Application.onCreate has necessarily finished.
            // Take baselines only after its main-thread initialization has completed.
            waitForIdleSync()
            if (suite == "permission") {
                val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                runOnMainSync {
                    targetContext.getSharedPreferences("permission_ui", Context.MODE_PRIVATE)
                        .edit().putBoolean("notifications_asked", false).commit()
                    activity.findViewById<Button>(R.id.btnCaptureStart).performClick()
                }
                sendStatus(0, Bundle().apply { putString("stream", "PERMISSION_WAIT: allow notifications, then Share screen\n") })
                runBlocking {
                    withTimeout(180000) {
                        while (!ScreenCaptureService.isRunning) delay(100)
                    }
                }
                ScreenCaptureService.stop(targetContext)
                results.putString("stream", "NOTIFICATION ALLOW CALLBACK PASS\n")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "real" || suite == "workflow") {
                RealModelRepairChecks(this).run(useModel = suite == "real")
                results.putString("stream", if (suite == "real") "REAL MODEL TOOL CHAIN PASS\n" else "DETERMINISTIC WORKFLOW PASS\n")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "mock") {
                AutomationRepairChecks(this).run()
                results.putString("stream", "S01-S04 MOCK AUTOMATION PASS\n")
                finish(Activity.RESULT_OK, results)
                return
            }
            closeDuringCopy()
            results.putString("closeDuringCopy", "PASS")
            conversionFailureRecovers()
            results.putString("conversionFailureRecovers", "PASS")
            queuedPreGestureFrameIsDrained()
            results.putString("queuedPreGestureFrameIsDrained", "PASS")
            results.putString("stream", "3 capture instrumentation tests passed\n")
            finish(Activity.RESULT_OK, results)
        } catch (t: Throwable) {
            results.putString("stream", t.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun closeDuringCopy() {
        repeat(10) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val copied = CountDownLatch(1)
            val capture = CaptureWorker(32, 32) { image ->
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                val plane = image.planes[0]
                Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    .apply { copyPixelsFromBuffer(plane.buffer); copied.countDown() }
            }
            try {
                draw(capture, Color.RED)
                check(entered.await(5, TimeUnit.SECONDS)) { "no ImageReader callback" }
                runOnMainSync { capture.close { closed.countDown() } }
                check(closed.count == 1L) { "reader closed during in-flight copy" }
                check(capture.currentFrame() == null)
                release.countDown()
                check(closed.await(5, TimeUnit.SECONDS)) { "capture cleanup hung" }
                check(copied.count == 0L) { "in-flight buffer was not readable" }
            } finally {
                release.countDown()
                capture.close()
            }
        }
    }

    private fun conversionFailureRecovers() {
        val count = AtomicInteger()
        val failed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val capture = CaptureWorker(32, 32) {
            if (count.incrementAndGet() == 2) {
                failed.countDown()
                error("injected conversion failure")
            }
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        }
        try {
            draw(capture, Color.RED)
            await { capture.currentFrame()?.let { it.recycle(); true } ?: false }
            draw(capture, Color.GREEN)
            check(failed.await(5, TimeUnit.SECONDS))
            await { capture.currentFrame()?.let { it.recycle(); false } ?: true }
            draw(capture, Color.BLUE)
            await { capture.currentFrame()?.let { it.recycle(); true } ?: false }
        } finally {
            capture.close { closed.countDown() }
            check(closed.await(5, TimeUnit.SECONDS))
        }
    }

    private fun draw(capture: CaptureWorker, color: Int) {
        val surface = capture.surface
        val canvas = surface.lockCanvas(null)
        try { canvas.drawColor(color) } finally { surface.unlockCanvasAndPost(canvas) }
    }

    private fun queuedPreGestureFrameIsDrained() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val copies = AtomicInteger()
        val capture = CaptureWorker(32, 32) {
            if (copies.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        }
        try {
            draw(capture, Color.RED)
            check(entered.await(5, TimeUnit.SECONDS))
            draw(capture, Color.GREEN) // Queued while the older conversion is blocked.
            capture.invalidateFrame { drained.countDown() }
            release.countDown()
            check(drained.await(5, TimeUnit.SECONDS))
            check(capture.currentFrame() == null)
            check(copies.get() == 1) { "queued pre-gesture buffer was converted" }
            draw(capture, Color.BLUE)
            await { capture.currentFrame()?.let { it.recycle(); true } ?: false }
            check(copies.get() == 2)
        } finally {
            release.countDown()
            capture.close { closed.countDown() }
            check(closed.await(5, TimeUnit.SECONDS))
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "capture condition timed out" }
            Thread.sleep(10)
        }
    }
}
