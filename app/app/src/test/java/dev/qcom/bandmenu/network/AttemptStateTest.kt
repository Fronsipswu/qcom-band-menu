package dev.qcom.bandmenu.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext
import kotlin.concurrent.thread

class AttemptStateTest {
    @Test fun completedAttemptSurvivesLaterWatchdogAndStop() {
        val state = AttemptState(1000)
        assertEquals(AttemptOutcome.SUCCESS, state.complete(AttemptOutcome.SUCCESS, 999).outcome)
        assertFalse(state.expire(1001))
        assertEquals(AttemptOutcome.SUCCESS, state.interrupt("Stopped by user").outcome)
        assertNull(state.completion!!.applyTo(AttemptMeasurement(bytes = 500)).error)
    }

    @Test fun overdueResultIsTimeoutEvenBeforeWatchdogRuns() {
        val state = AttemptState(1000)
        val completed = state.complete(AttemptOutcome.SUCCESS, 1000)
        assertEquals(AttemptOutcome.TIMED_OUT, completed.outcome)
        assertEquals("Attempt timed out", completed.applyTo(AttemptMeasurement(bytes = 10)).error)
        assertFalse(state.expire(1100))
    }

    @Test fun cancelledBlockingIoFailureKeepsCancellationAndPartialBytes() {
        val state = AttemptState(1000)
        state.interrupt("Stopped by user")
        val completed = state.complete(AttemptOutcome.FAILED, 300)
        val measurement = completed.applyTo(AttemptMeasurement(bytes = 123, error = "Socket closed"))
        assertEquals(AttemptOutcome.CANCELLED, completed.outcome)
        assertEquals(123L, measurement.bytes)
        assertEquals("Stopped by user", measurement.error)
    }

    @Test fun networkChangeIsUnverifiedFailureAndCannotBecomeTimeoutLater() {
        val state = AttemptState(1000)
        val completed = state.interrupt("Mobile network changed", networkChanged = true)
        assertEquals(AttemptOutcome.FAILED, completed.outcome)
        assertFalse(completed.applyTo(AttemptMeasurement()).contentVerified)
        assertFalse(completed.applyTo(AttemptMeasurement()).measurementAvailable)
        assertFalse(state.expire(1100))
        assertEquals(completed, state.complete(AttemptOutcome.SUCCESS, 1100))
    }

    @Test fun racingResultAndStopProduceOneStableDecision() {
        repeat(100) {
            val state = AttemptState(1000)
            val start = CountDownLatch(1)
            val result = thread { start.await(); state.complete(AttemptOutcome.SUCCESS, 500) }
            val cancel = thread { start.await(); state.interrupt("Stopped") }
            start.countDown()
            result.join(2000)
            cancel.join(2000)
            assertFalse(result.isAlive)
            assertFalse(cancel.isAlive)
            val decided = state.completion!!
            assertTrue(decided.outcome in listOf(AttemptOutcome.SUCCESS, AttemptOutcome.CANCELLED))
            assertFalse(state.expire(2000))
            assertEquals(decided, state.complete(AttemptOutcome.FAILED, 2000))
        }
    }

    @Test fun stoppingBeforeDispatchStillFinalizesSessionExactlyOnce() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var entered = 0
        var finalized = 0
        val job = scope.sessionJob(onNeverStarted = { finalized++ }) {
            entered++
            try { suspendCancellableCoroutine<Unit> {} } finally { finalized++ }
        }
        job.start()
        job.cancel()
        dispatcher.drain()
        assertEquals(0, entered)
        assertEquals(1, finalized)
        assertTrue(job.isCompleted)
    }

    @Test fun stoppingBeforeStartAlsoFinalizesSessionExactlyOnce() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var finalized = 0
        val job = scope.sessionJob(onNeverStarted = { finalized++ }) { fail("Cancelled job must not start") }
        job.cancel()
        job.start()
        dispatcher.drain()
        assertEquals(1, finalized)
    }

    @Test fun stoppingAfterEntryUsesBodyCleanupOnly() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var entered = 0
        var fallback = 0
        var finalized = 0
        val job = scope.sessionJob(onNeverStarted = { fallback++ }) {
            entered++
            try { suspendCancellableCoroutine<Unit> {} } finally { finalized++ }
        }
        job.start()
        dispatcher.drain()
        job.cancel()
        dispatcher.drain()
        assertEquals(1, entered)
        assertEquals(0, fallback)
        assertEquals(1, finalized)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() {
            while (true) (queue.poll() ?: return).run()
        }
    }
}
