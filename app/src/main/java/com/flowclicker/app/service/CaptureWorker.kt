package com.flowclicker.app.service

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** All acquired Image lifetimes and reader.close run on the same worker queue. */
internal class CaptureWorker(
    width: Int,
    height: Int,
    private val convert: (Image) -> Bitmap = ::imageToBitmap,
) {
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val thread = HandlerThread("frame-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val closing = AtomicBoolean(false)
    private val invalidations = AtomicInteger(0)
    private val frames = CaptureFrameStore(
        clock = SystemClock::elapsedRealtime,
        copy = { bitmap: Bitmap -> bitmap.copy(Bitmap.Config.ARGB_8888, false) },
        dispose = Bitmap::recycle,
    )
    val surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            if (!closing.get() && invalidations.get() == 0) {
                val ticket = frames.beginFrame()
                try {
                    // use closes the Image before the next queue item can close the reader.
                    source.acquireLatestImage()?.use { image ->
                        frames.publish(convert(image), ticket)
                    }
                } catch (e: Exception) {
                    frames.invalidate()
                    if (!closing.get()) Log.w("ScreenCapture", "frame unavailable; cache invalidated", e)
                }
            }
        }, handler)
    }

    fun currentFrame(): Bitmap? = frames.read()
    fun hasRecentFrame(): Boolean = frames.isRecent()
    fun invalidateFrame(onDrained: () -> Unit = {}) {
        invalidations.incrementAndGet()
        frames.invalidate()
        // A callback queued before the gesture may not have acquired its Image yet.
        // Block publication until the worker drains those buffers too, not just in-flight copies.
        val drain = Runnable {
            try {
                if (!closing.get()) reader.acquireLatestImage()?.close()
            } catch (e: Exception) {
                Log.w("ScreenCapture", "frame invalidation drain failed", e)
            } finally {
                frames.invalidate()
                invalidations.decrementAndGet()
                onDrained()
            }
        }
        if (!handler.post(drain)) {
            invalidations.decrementAndGet()
            onDrained()
        }
    }

    fun close(afterClose: () -> Unit = {}) {
        if (!closing.compareAndSet(false, true)) return
        frames.close()
        reader.setOnImageAvailableListener(null, null)
        val cleanup = Runnable {
            try {
                reader.close()
            } finally {
                try { afterClose() }
                finally {
                    thread.quitSafely()
                    Log.i("ScreenCapture", "capture resources released")
                }
            }
        }
        // No main-thread join: FIFO waits for an in-flight copy AND Image.close.
        if (!handler.post(cleanup)) {
            // A terminated looper must not tempt us to close a still-in-use buffer.
            Thread({
                thread.join()
                cleanup.run()
            }, "capture-cleanup").start()
        }
    }
}

private fun imageToBitmap(image: Image): Bitmap {
    val plane = image.planes[0]
    val rowPadding = plane.rowStride - plane.pixelStride * image.width
    val padded = Bitmap.createBitmap(
        image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
    )
    try {
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            .also { padded.recycle() }
    } catch (e: Throwable) {
        padded.recycle()
        throw e
    }
}
