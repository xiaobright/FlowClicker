package com.flowclicker.app.service

/**
 * Owns one copied frame. A ticket prevents an in-flight conversion from republishing
 * a frame invalidated by a gesture, capture error, or shutdown.
 * Static frames remain readable; only action admission imposes an age limit.
 */
internal class CaptureFrameStore<T : Any>(
    private val clock: () -> Long,
    private val copy: (T) -> T,
    private val dispose: (T) -> Unit,
    private val maxActionAgeMs: Long = 5000,
) {
    data class Ticket(val epoch: Long, val acquiredAt: Long)

    private var epoch = 0L
    private var closed = false
    private var frame: T? = null
    private var frameAt = 0L

    @Synchronized fun beginFrame() = Ticket(epoch, clock())

    @Synchronized fun publish(value: T, ticket: Ticket) {
        if (closed || ticket.epoch != epoch) {
            dispose(value)
            return
        }
        frame?.let(dispose)
        frame = value
        frameAt = ticket.acquiredAt
    }

    @Synchronized fun read(): T? = if (closed) null else frame?.let(copy)

    @Synchronized fun isRecent(): Boolean =
        !closed && frame != null && clock() - frameAt in 0..maxActionAgeMs

    @Synchronized fun invalidate() {
        epoch++
        frame?.let(dispose)
        frame = null
        frameAt = 0
    }

    @Synchronized fun close() {
        closed = true
        invalidate()
    }
}
