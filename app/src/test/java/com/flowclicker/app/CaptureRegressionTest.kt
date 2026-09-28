package com.flowclicker.app

import com.flowclicker.app.engine.FrameGapWatch
import com.flowclicker.app.engine.Step
import com.flowclicker.app.engine.waitForText
import com.flowclicker.app.service.CaptureFrameStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CaptureRegressionTest {
    private var now = 0L
    private val disposed = mutableListOf<String>()
    private fun store() = CaptureFrameStore({ now }, { value: String -> value }, { disposed.add(it) })

    @Test fun shutdownRejectsInFlightAndAllLaterFrames() {
        val store = store()
        store.publish("old", store.beginFrame())
        val inFlight = store.beginFrame()
        store.close()
        store.publish("late", inFlight)
        store.publish("later", store.beginFrame())
        assertNull(store.read())
        assertFalse(store.isRecent())
        assertEquals(listOf("old", "late", "later"), disposed)
    }

    @Test fun closeIsIdempotentForOwnedBitmap() {
        val store = store()
        store.publish("frame", store.beginFrame())
        store.close()
        store.close()
        assertEquals(listOf("frame"), disposed)
    }

    @Test fun gestureInvalidationRejectsConversionStartedBeforeGestureEnded() {
        val store = store()
        val beforeGesture = store.beginFrame()
        store.invalidate()
        store.publish("pre-action", beforeGesture)
        assertNull(store.read())
        store.publish("post-action", store.beginFrame())
        assertEquals("post-action", store.read())
    }

    @Test fun captureErrorDiscardsCacheAndNextSuccessfulFrameRecovers() {
        val store = store()
        store.publish("old", store.beginFrame())
        store.invalidate()
        assertNull(store.read())
        assertFalse(store.isRecent())
        store.publish("recovered", store.beginFrame())
        assertTrue(store.isRecent())
    }

    @Test fun unchangedPixelsDoNotExpireReadOnlyMonitoring() {
        val store = store()
        store.publish("same pixels", store.beginFrame())
        now = 60000
        assertEquals("same pixels", store.read())
        assertFalse(store.isRecent())
        store.publish("same pixels", store.beginFrame())
        assertTrue(store.isRecent())
    }

    @Test fun slowConversionDoesNotFreshenAnOldAcquisition() {
        val store = store()
        val ticket = store.beginFrame()
        now = 5001
        store.publish("late copy", ticket)
        assertFalse(store.isRecent())
    }

    @Test fun actionAgeBoundaryAndClockRegressionFailClosed() {
        now = 100
        val store = store()
        store.publish("frame", store.beginFrame())
        now = 5100
        assertTrue(store.isRecent())
        now = 5101
        assertFalse(store.isRecent())
        now = 99
        assertFalse(store.isRecent())
    }

    @Test fun oldCaptureClosingCannotDiscardNewCapturesFrame() {
        val old = store()
        val fresh = store()
        fresh.publish("new session", fresh.beginFrame())
        old.close()
        assertEquals("new session", fresh.read())
    }

    @Test fun invalidatedCacheActuallyFeedsMissingFrameWatch() {
        val store = store()
        val watch = FrameGapWatch()
        store.publish("valid", store.beginFrame())
        assertFalse(watch.expired(store.read() != null, 0))
        store.invalidate()
        assertFalse(watch.expired(store.read() != null, 100))
        assertTrue(watch.expired(store.read() != null, 5100))
        store.publish("recovered", store.beginFrame())
        assertFalse(watch.expired(store.read() != null, 5200))
    }

    @Test fun waitTextDoesNotAcceptInvalidatedPreActionFrame() = runTest {
        val store = store()
        store.publish("完成", store.beginFrame())
        store.invalidate()
        var reads = 0
        launch { delay(600); store.publish("完成", store.beginFrame()) }
        waitForText(Step.WaitText("完成", timeoutMs = 2000)) {
            reads++
            store.read()
        }
        assertTrue(reads >= 3)
    }

    @Test fun missingFrameDoesNotProveTextDisappeared() = runTest {
        try {
            waitForText(Step.WaitText("目标", present = false, timeoutMs = 500)) { null }
            fail("missing frame must time out, not verify absence")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("超时"))
        }
    }

    @Test fun validEmptyOcrCanProveTextDisappeared() = runTest {
        waitForText(Step.WaitText("目标", present = false, timeoutMs = 500)) { "" }
    }

    @Test fun cancellingWaitTextPropagatesCancellation() = runTest {
        try {
            withTimeout(100) {
                waitForText(Step.WaitText("目标", timeoutMs = 5000)) { null }
            }
            fail("outer cancellation must propagate")
        } catch (_: TimeoutCancellationException) { }
    }

    @Test fun notificationFirstInstallRequestsPermission() {
        assertEquals(CaptureNotificationAction.REQUEST, captureNotificationAction(33, false, false, false))
    }

    @Test fun deniedPermissionWarnsRatherThanLoopingRequests() {
        assertEquals(CaptureNotificationAction.WARN, captureNotificationAction(36, false, true, false))
    }

    @Test fun grantedNotificationStartsProjection() {
        assertEquals(CaptureNotificationAction.START, captureNotificationAction(36, true, true, true))
    }

    @Test fun disabledChannelWarnsEvenWithRuntimePermission() {
        assertEquals(CaptureNotificationAction.WARN, captureNotificationAction(36, true, true, false))
    }

    @Test fun android12DoesNotRequestRuntimeNotificationPermission() {
        assertEquals(CaptureNotificationAction.START, captureNotificationAction(32, false, false, true))
        assertEquals(CaptureNotificationAction.WARN, captureNotificationAction(32, false, false, false))
    }
}
