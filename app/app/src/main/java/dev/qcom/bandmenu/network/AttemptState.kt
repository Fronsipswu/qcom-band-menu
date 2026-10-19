package dev.qcom.bandmenu.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** The fallback also runs when cancellation prevents the queued coroutine from entering its body. */
internal fun CoroutineScope.sessionJob(onNeverStarted: () -> Unit, block: suspend () -> Unit): Job {
    val entered = AtomicBoolean(false)
    val job = launch(start = CoroutineStart.LAZY) {
        entered.set(true)
        block()
    }
    job.invokeOnCompletion {
        if (!entered.get()) launch { onNeverStarted() }
    }
    return job
}

/** One terminal decision shared by the transport, browser, stop button and deadline watchdog. */
internal class AttemptState(private val deadlineMs: Long) {
    data class Completion(val outcome: AttemptOutcome, val error: String? = null, val verified: Boolean? = null) {
        fun applyTo(measurement: AttemptMeasurement) = measurement.copy(
            error = error ?: measurement.error,
            contentVerified = verified ?: measurement.contentVerified,
            measurementAvailable = if (verified == false) false else measurement.measurementAvailable)
    }

    private var terminal: Completion? = null
    val active: Boolean @Synchronized get() = terminal == null
    val completion: Completion? @Synchronized get() = terminal

    /** Finishing after the deadline cannot win a race against a delayed watchdog. */
    @Synchronized fun complete(outcome: AttemptOutcome, nowMs: Long): Completion {
        terminal?.let { return it }
        return (if (nowMs >= deadlineMs) timeout() else Completion(outcome)).also { terminal = it }
    }

    @Synchronized fun expire(nowMs: Long): Boolean {
        if (terminal != null || nowMs < deadlineMs) return false
        terminal = timeout()
        return true
    }

    /** An interruption after a completed attempt stops the session without changing that attempt. */
    @Synchronized fun interrupt(reason: String, networkChanged: Boolean = false): Completion {
        terminal?.let { return it }
        return (if (networkChanged) Completion(AttemptOutcome.FAILED, reason, false)
            else Completion(AttemptOutcome.CANCELLED, reason)).also { terminal = it }
    }

    fun remainingSeconds(nowMs: Long): Int = ((deadlineMs - nowMs + 999) / 1000).toInt().coerceAtLeast(0)
    private fun timeout() = Completion(AttemptOutcome.TIMED_OUT, "Attempt timed out")
}
