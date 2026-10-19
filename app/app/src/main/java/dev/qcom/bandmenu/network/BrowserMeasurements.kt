package dev.qcom.bandmenu.network

import org.json.JSONObject
import org.json.JSONTokener
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Resource callbacks run on WebView worker threads; snapshots run on the UI thread. */
internal class PageLoadTracker(private val expectedBytes: Long, private val startedMs: Long) {
    private var bytes = 0L
    private var active = 0
    private var requests = 0
    private var lastActivityMs = startedMs
    private var documentFinishedMs: Long? = null
    private var failure: String? = null
    private var measurementAvailable = true
    private var status: Int? = null

    @Synchronized fun begin(nowMs: Long) {
        active++
        requests++
        lastActivityMs = nowMs
    }

    @Synchronized fun addBytes(count: Int, nowMs: Long) {
        bytes += count
        lastActivityMs = nowMs
        if (bytes > expectedBytes) fail("Page exceeded the expected $expectedBytes document and resource bytes")
    }

    @Synchronized fun end(nowMs: Long, error: String? = null, instrument: Boolean = false) {
        active = (active - 1).coerceAtLeast(0)
        lastActivityMs = nowMs
        if (error != null) fail(error, instrument)
    }

    @Synchronized fun mainStatus(value: Int) { status = value }
    @Synchronized fun documentFinished(nowMs: Long) { documentFinishedMs = nowMs }
    @Synchronized fun fail(message: String, instrument: Boolean = false) {
        if (failure == null) failure = message.take(300)
        if (instrument) measurementAvailable = false
    }

    @Synchronized fun measurement(nowMs: Long, complete: Boolean = false): AttemptMeasurement = AttemptMeasurement(
        bytes = bytes,
        durationMs = ((if (complete) maxOf(documentFinishedMs ?: nowMs, lastActivityMs) else nowMs) - startedMs).coerceAtLeast(0).toDouble(),
        httpStatus = status,
        contentVerified = complete && failure == null && bytes == expectedBytes,
        error = failure ?: if (complete && bytes != expectedBytes) "Page loaded $bytes bytes; expected $expectedBytes" else null,
        detail = "Rendered document and HTTP(S) GET subresources; cache disabled; redirects, workers and WebSockets unsupported",
        measurementAvailable = measurementAvailable
    )

    @Synchronized fun result(documentReady: Boolean, nowMs: Long): Pair<AttemptMeasurement, AttemptOutcome>? {
        if (failure != null) return measurement(nowMs) to AttemptOutcome.FAILED
        if (!documentReady || active != 0 || requests == 0) return null
        val measured = measurement(nowMs, complete = true)
        return measured to if (measured.contentVerified) AttemptOutcome.SUCCESS else AttemptOutcome.FAILED
    }
}

/** Tracks what WebView actually consumes; an abandoned response is a failed resource. */
internal class PageResourceStream(
    input: InputStream,
    private val length: Long?,
    private val tracker: PageLoadTracker,
    private val clockMs: () -> Long,
    private val closeConnection: () -> Unit
) : FilterInputStream(input) {
    private val ended = AtomicBoolean(false)
    private var bytes = 0L
    private var eof = false

    override fun read(): Int = measuredRead { `in`.read().also { if (it >= 0) record(1) } }
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = measuredRead {
        `in`.read(buffer, offset, count).also { if (it > 0) record(it) }
    }

    private fun record(count: Int) {
        bytes += count
        tracker.addBytes(count, clockMs())
        if (length != null && bytes > length) throw IOException("Page resource exceeded Content-Length")
    }

    private fun measuredRead(read: () -> Int): Int = try {
        read().also {
            if (it < 0) {
                eof = true
                finish(if (length != null && bytes != length) "Incomplete page resource: $bytes of $length bytes" else null)
            }
        }
    } catch (e: Exception) {
        finish("Page resource read failed: ${e.message ?: e.javaClass.simpleName}")
        throw e
    }

    private fun finish(error: String?) {
        if (ended.compareAndSet(false, true)) {
            tracker.end(clockMs(), error)
            closeConnection()
        }
    }

    override fun close() {
        try { super.close() } finally {
            finish(if (eof || (length != null && bytes == length)) null else "Page resource closed before completion")
        }
    }
}

internal data class YoutubeSnapshot(
    val state: Int,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val loadedFraction: Double,
    val playbackRate: Double = 1.0,
    val error: Int? = null,
    val autoplayBlocked: Boolean = false
) {
    companion object {
        fun parseJavascript(value: String): YoutubeSnapshot? = runCatching {
            val decoded = JSONTokener(value).nextValue()
            val json = when (decoded) { is String -> JSONObject(decoded); is JSONObject -> decoded; else -> return null }
            if (!json.optBoolean("ready")) return null
            YoutubeSnapshot(json.getInt("state"), json.getDouble("position"), json.getDouble("duration"),
                json.optDouble("loaded", 0.0), json.optDouble("rate", 1.0),
                json.optInt("error").takeIf { it != 0 }, json.optBoolean("autoplayBlocked"))
        }.getOrNull()
    }
}

/** Uses monotonic wall time and excludes startup buffering from freeze accounting. */
internal class YoutubePlaybackTracker(
    private val minimumSeconds: Int,
    private val requiredHeight: Int,
    private val startedMs: Long
) {
    private var lastMs = startedMs
    private var previous: YoutubeSnapshot? = null
    private var playbackStarted = false
    private var serviceAccessed = false
    private var startupDelayMs: Double? = null
    private var playbackCompleted = false
    private var freezeMs = 0L
    private var playingMs = 0L
    private var error: String? = null
    private var measurementAvailable = true
    private var duration: Double? = null
    private var loadedFraction = 0.0

    @Synchronized fun update(nowMs: Long, snapshot: YoutubeSnapshot, qualityConfirmed: Boolean): AttemptMeasurement {
        val delta = (nowMs - lastMs).coerceAtLeast(0)
        val old = previous
        if (playbackStarted && !playbackCompleted) {
            when (old?.state) { 1 -> playingMs += delta; 3 -> freezeMs += delta }
        }
        if (snapshot.durationSeconds.isFinite() && snapshot.durationSeconds > 0) duration = snapshot.durationSeconds
        loadedFraction = snapshot.loadedFraction
        if (snapshot.loadedFraction > 0 || snapshot.state == 1) serviceAccessed = true
        if (snapshot.error != null) {
            error = "YouTube player error ${snapshot.error}"
            if (snapshot.error in listOf(2, 100, 101, 150, 153)) measurementAvailable = false
        }
        if (!snapshot.positionSeconds.isFinite() || !snapshot.durationSeconds.isFinite() || snapshot.positionSeconds < 0)
            instrumentFailure("Invalid YouTube timing information")
        if (snapshot.playbackRate != 1.0) instrumentFailure("Playback speed changed; the test requires normal speed")
        if (snapshot.state == 1) {
            if (!playbackStarted && snapshot.positionSeconds > 2.0) instrumentFailure("Playback did not start at the beginning")
            if (!playbackStarted) startupDelayMs = (nowMs - startedMs).coerceAtLeast(0).toDouble()
            playbackStarted = true
        }
        if (playbackStarted && old != null && old.state in listOf(1, 3)) {
            val advance = snapshot.positionSeconds - old.positionSeconds
            if (advance < -1.0 || advance > delta / 1000.0 + 2.0) instrumentFailure("Playback was seeked; repeat the test without seeking")
        }
        if (playbackStarted && snapshot.state == 2) instrumentFailure("Playback was paused; repeat the test without pausing")
        if (snapshot.state == 0) {
            when {
                !playbackStarted -> instrumentFailure("Player ended without measured playback")
                duration == null || duration!! < minimumSeconds -> instrumentFailure("Video must be at least $minimumSeconds seconds long")
                snapshot.positionSeconds < duration!! - 2.0 -> error = "Video ended before its full duration"
                else -> playbackCompleted = error == null
            }
        }
        previous = snapshot
        lastMs = nowMs
        return measurement(nowMs, qualityConfirmed)
    }

    @Synchronized fun measurement(nowMs: Long, qualityConfirmed: Boolean): AttemptMeasurement = AttemptMeasurement(
        durationMs = (nowMs - startedMs).coerceAtLeast(0).toDouble(), playbackStarted = playbackStarted,
        playbackCompleted = playbackCompleted, mediaDurationSeconds = duration,
        observedQuality = if (qualityConfirmed) "${requiredHeight}p (operator confirmed)" else "Not verified",
        freezeDurationMs = freezeMs + pendingInterval(nowMs, 3),
        playbackDurationMs = playingMs + pendingInterval(nowMs, 1), contentVerified = qualityConfirmed,
        error = error, detail = "YouTube iframe; resolution manually confirmed per attempt; startup excluded from freezing; buffered fraction $loadedFraction",
        serviceAccessed = serviceAccessed, startupDelayMs = startupDelayMs, measurementAvailable = measurementAvailable
    )

    private fun pendingInterval(nowMs: Long, state: Int): Long =
        if (playbackStarted && !playbackCompleted && previous?.state == state) (nowMs - lastMs).coerceAtLeast(0) else 0

    private fun instrumentFailure(message: String) { error = message; measurementAvailable = false }
}
