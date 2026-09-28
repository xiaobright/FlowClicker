package com.flowclicker.app

import com.flowclicker.app.ai.*
import com.flowclicker.app.core.ScreenControl
import com.flowclicker.app.engine.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SafetyRegressionTest {
    @Test fun temporaryMissingFrameDoesNotStopMonitoring() {
        val watch = FrameGapWatch()
        assertFalse(watch.expired(false, 100))
        assertFalse(watch.expired(false, 4000))
        assertFalse(watch.expired(true, 4100))
        assertFalse(watch.expired(false, 9000))
        assertFalse(watch.expired(false, 13000))
    }

    @Test fun sustainedMissingFramesStopMonitoring() {
        val watch = FrameGapWatch()
        assertFalse(watch.expired(false, 100))
        assertFalse(watch.expired(false, 5099))
        assertTrue(watch.expired(false, 5100))
    }

    @Test fun stoppedGenerationCannotBeRevivedByLateCallbacks() {
        val gate = AutomationGate()
        val oldNetworkOrTask = gate.epoch
        assertTrue(gate.permits(oldNetworkOrTask, true))
        gate.stop()
        assertFalse(gate.permits(oldNetworkOrTask, true))
        gate.resume()
        assertFalse(gate.permits(oldNetworkOrTask, true))
        assertTrue(gate.permits(gate.epoch, true))
        assertFalse(gate.permits(gate.epoch, false))
    }
    private fun task(id: Long = 1) = Task(id, "任务$id", steps = listOf(Step.Wait(0)))
    @Before fun setup() {
        MonitoringEngine.onChanged = null
        MonitoringEngine.loadError = null
        MonitoringEngine.updateTasks { it.clear() }
        MonitoringEngine.setTasks(listOf(task()))
    }
    @After fun cleanup() {
        MonitoringEngine.onChanged = null
        MonitoringEngine.eventListener = null
        MonitoringEngine.updateTasks { it.clear() }
    }

    @Test fun previewDoesNotReplaceOrPersistLibrary() = runBlocking {
        var writes = 0
        MonitoringEngine.onChanged = { writes++ }
        val before = MonitoringEngine.tasksSnapshot()
        val result = MonitoringEngine.preview(task(99))
        assertTrue(result.completed)
        assertFalse(result.verified)
        assertEquals(before, MonitoringEngine.tasksSnapshot())
        assertEquals(0, writes)
    }

    @Test fun missingAccessibilityIsNotSuccess() = runBlocking {
        val result = MonitoringEngine.preview(task(99).copy(steps = listOf(Step.Click(1f, 1f))))
        assertFalse(result.completed)
        assertFalse(result.verified)
        assertEquals(0, result.stepsRan)
        assertNotNull(result.reason)
    }

    @Test fun cancelledPreviewReleasesScreenWithoutSaving() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            ScreenControl.withOwner("test") { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        ScreenControl.cancelActive()
        job.join()
        assertTrue(job.isCancelled)
        assertNull(ScreenControl.owner)
    }

    @Test fun screenOwnerCoversWholeSequence() = runBlocking {
        val sequence = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = launch {
            ScreenControl.withOwner("engine") {
                sequence.add("A1"); firstStarted.complete(Unit)
                release.await()
                sequence.add("A2")
            }
        }
        firstStarted.await()
        val b = launch { ScreenControl.withOwner("ai") { sequence.add("B") } }
        yield()
        assertEquals(listOf("A1"), sequence)
        release.complete(Unit)
        joinAll(a, b)
        assertEquals(listOf("A1", "A2", "B"), sequence)
    }

    @Test fun failedCommitDoesNotPublishMemory() {
        MonitoringEngine.onChanged = { throw IOException("disk full") }
        assertThrows(IOException::class.java) {
            MonitoringEngine.updateTasks { it.add(task(2)) }
        }
        assertEquals(listOf(1L), MonitoringEngine.tasksSnapshot().map { it.id })
    }

    @Test fun unrelatedTaskMutationPreservesLatestAiDefinition() {
        MonitoringEngine.updateTasks { it.add(task(2)) }
        MonitoringEngine.updateTasks { it[0] = it[0].copy(name = "AI修改") }
        MonitoringEngine.updateTasks { list ->
            val i = list.indexOfFirst { it.id == 2L }; list[i] = list[i].copy(enabled = false)
        }
        assertEquals("AI修改", MonitoringEngine.tasksSnapshot()[0].name)
        assertFalse(MonitoringEngine.tasksSnapshot()[1].enabled)
    }

    @Test fun onlyDefinitionChangesInvalidateRevision() {
        MonitoringEngine.updateTasks { it[0] = it[0].copy(name = "改名") }
        val revision = MonitoringEngine.tasksSnapshot()[0].revision
        assertTrue(revision > 0)
        MonitoringEngine.updateTasks { it[0] = it[0].copy(enabled = false, mode = Task.MODE_DEBUG) }
        assertEquals(revision, MonitoringEngine.tasksSnapshot()[0].revision)
    }

    @Test fun concurrentWritersDoNotLoseAdds() {
        val gate = CountDownLatch(1)
        val threads = (2L..15L).map { id -> Thread {
            gate.await()
            MonitoringEngine.updateTasks { it.add(task(id)) }
        }.apply { start() } }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(15, MonitoringEngine.tasksSnapshot().size)
    }

    @Test fun cannotPromoteWithoutSuccessfulVerifiedRun() {
        assertThrows(IllegalStateException::class.java) { MonitoringEngine.promote(1) }
    }

    @Test fun recordedGapBelongsToPreviousStep() {
        val times = listOf(0L to 100L, 5100L to 5200L, 5400L to 5500L)
        assertEquals(5000, RecordingTiming.delayAfter(times, 0))
        assertEquals(200, RecordingTiming.delayAfter(times, 1))
        assertEquals(500, RecordingTiming.delayAfter(times, 2))
    }

    @Test fun queueIsBoundedAndDeduplicatesInflight() {
        val queue = WakeQueue<String>(2) { it }
        assertTrue(queue.offer("a"))
        assertEquals("a", queue.poll())
        assertFalse(queue.offer("a"))
        assertTrue(queue.offer("b")); assertTrue(queue.offer("c"))
        assertFalse(queue.offer("d"))
        queue.finish("a")
        assertEquals(2, queue.size())
        queue.clear()
        assertTrue(queue.offer("a"))
    }

    @Test fun sameTaskDoesNotClearStallOrExtendDeadline() {
        val watch = StallWatch()
        watch.finished(1, 100)
        watch.fired(1)
        watch.finished(1, 200)
        assertEquals(listOf(1L), watch.due(100))
    }

    @Test fun otherTaskSatisfiesStall() {
        val watch = StallWatch()
        watch.finished(1, 100); watch.fired(2)
        assertTrue(watch.due(100).isEmpty())
    }

    @Test fun retryPolicyRejectsAuthAndProtocolErrors() {
        assertFalse(RetryPolicy.retryable(AiHttpException(401, "bad key")))
        assertFalse(RetryPolicy.retryable(AiHttpException(400, "bad request")))
        assertFalse(RetryPolicy.retryable(IllegalStateException("bad protocol")))
        assertFalse(RetryPolicy.retryable(CancellationException()))
        assertTrue(RetryPolicy.retryable(AiHttpException(503, "busy")))
        assertTrue(RetryPolicy.retryable(IOException("timeout")))
    }

    @Test fun promptTaskExampleIsRealJsonAndDecodable() {
        val example = AiSession.systemPrompt(AiSettings()).lineSequence().first { it.startsWith("{\"id\":") }
        val task = Json.decodeFromString<Task>(example)
        TaskValidation.validate(task)
        assertTrue(task.steps.last() is Step.WaitText)
    }

    @Test fun unknownToolAndVlmDisabledReturnValidJson() = runBlocking {
        for (name in listOf("unknown \"quoted\"", "get_screenshot", "get_debug_captures")) {
            val result = AiTools.execute(name, JsonObject(emptyMap()), AiSettings(vlm = false))
            assertNotNull(Json.parseToJsonElement(result.text).jsonObject["error"])
        }
    }

    @Test fun taskValidationRejectsUnsafeInputs() {
        val invalid = listOf(
            task().copy(steps = listOf(Step.Click(-1f, 0f))),
            task().copy(steps = listOf(Step.Click(Float.NaN, 0f))),
            task().copy(steps = listOf(Step.Click(1f, 1f, pressMs = 5000, pressJitterMs = 1))),
            task().copy(steps = listOf(Step.Wait(-1))),
            task().copy(steps = listOf(Step.WaitText("", timeoutMs = 0))),
            task().copy(trigger = Trigger(keywords = listOf(""))),
            task().copy(trigger = Trigger(region = Region(0, 0, -1, -1))),
            task().copy(mode = "typo")
        )
        invalid.forEach { t -> assertThrows(IllegalArgumentException::class.java) { TaskValidation.validate(t) } }
    }

    @Test fun legacyClickDefaultsToFailClosedAnchor() {
        val step = Json.decodeFromString<Step>("""{"type":"click","x":10,"y":20,"anchor":"确定"}""") as Step.Click
        assertFalse(step.anchorFallback)
    }
}
