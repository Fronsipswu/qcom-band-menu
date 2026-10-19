package dev.qcom.bandmenu.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BrowserMeasurementsTest {
    @Test
    fun pageRequiresDocumentCompletionEveryResourceAndExpectedAggregateSize() {
        val tracker = PageLoadTracker(10, 0)
        tracker.begin(0)
        tracker.addBytes(6, 10)
        tracker.end(20)
        tracker.begin(30)
        tracker.addBytes(4, 40)
        assertNull(tracker.result(true, 1000)) // The second resource is still open.
        tracker.end(50)
        assertNull(tracker.result(false, 1000))
        tracker.documentFinished(100)
        val result = tracker.result(true, 350)!!
        assertEquals(AttemptOutcome.SUCCESS, result.second)
        assertEquals(10L, result.first.bytes)
        assertTrue(result.first.contentVerified)
        assertEquals(100.0, result.first.durationMs, 0.0) // Verification polling adds no load-time overhead.
    }

    @Test
    fun aCompleteDocumentAloneCannotPassWhenSubresourcesAreMissing() {
        val tracker = PageLoadTracker(10, 0)
        tracker.begin(0)
        tracker.addBytes(6, 1)
        tracker.end(2)
        val result = tracker.result(true, 500)!!
        assertEquals(AttemptOutcome.FAILED, result.second)
        assertFalse(result.first.contentVerified)
        assertTrue(result.first.error!!.contains("expected 10"))
    }

    @Test
    fun resourceFailurePreventsPassEvenWhenExpectedByteCountWasReached() {
        val tracker = PageLoadTracker(5, 0)
        tracker.begin(0)
        tracker.addBytes(5, 1)
        tracker.end(2, "HTTP 404")
        val result = tracker.result(true, 500)!!
        assertEquals(AttemptOutcome.FAILED, result.second)
        assertEquals("HTTP 404", result.first.error)
        assertTrue(result.first.measurementAvailable)
    }

    @Test
    fun unsupportedInstrumentationIsDistinguishedFromAMeasuredNetworkFailure() {
        val tracker = PageLoadTracker(5, 0)
        tracker.begin(0)
        tracker.end(1, "Redirects are unsupported", instrument = true)
        val result = tracker.result(false, 10)!!
        assertEquals(AttemptOutcome.FAILED, result.second)
        assertFalse(result.first.measurementAvailable)
        assertFalse(result.first.contentVerified)
    }

    @Test
    fun streamCountsSingleAndBulkReadsExactlyOnceAndDisconnectsOnce() {
        val tracker = PageLoadTracker(5, 0)
        tracker.begin(0)
        var closes = 0
        val stream = PageResourceStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), 5,
            tracker, { 100 }) { closes++ }
        assertEquals(1, stream.read())
        val buffer = ByteArray(10)
        assertEquals(4, stream.read(buffer, 0, buffer.size))
        assertEquals(-1, stream.read())
        stream.close()
        stream.close()
        assertEquals(1, closes)
        assertEquals(5L, tracker.measurement(500).bytes)
        assertEquals(AttemptOutcome.SUCCESS, tracker.result(true, 500)!!.second)
    }

    @Test
    fun prematureStreamCloseAndTruncatedContentLengthFail() {
        for (readToEof in listOf(false, true)) {
            val tracker = PageLoadTracker(5, 0)
            tracker.begin(0)
            PageResourceStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 5, tracker, { 100 }) {}.use { stream ->
                if (readToEof) stream.readBytes() else stream.read()
            }
            val result = tracker.result(true, 500)!!
            assertEquals(AttemptOutcome.FAILED, result.second)
            assertNotNull(result.first.error)
            assertTrue(result.first.measurementAvailable)
        }
    }

    @Test
    fun knownLengthMayCloseWithoutAnExtraEofRead() {
        val tracker = PageLoadTracker(2, 0)
        tracker.begin(0)
        PageResourceStream(ByteArrayInputStream(byteArrayOf(1, 2)), 2, tracker, { 100 }) {}.use {
            assertEquals(2, it.read(ByteArray(2)))
        }
        assertEquals(AttemptOutcome.SUCCESS, tracker.result(true, 500)!!.second)
    }

    @Test
    fun youtubeStartupBufferingIsExcludedAndServiceAccessCanPrecedePlayback() {
        val tracker = YoutubePlaybackTracker(180, 720, 0)
        val buffering = tracker.update(1_000, snap(state = 3, loaded = 0.1), false)
        assertTrue(buffering.serviceAccessed)
        assertFalse(buffering.playbackStarted)
        assertEquals(0L, buffering.freezeDurationMs)
        assertNull(buffering.startupDelayMs)
        val started = tracker.update(5_000, snap(state = 1), false)
        assertTrue(started.playbackStarted)
        assertEquals(5_000.0, started.startupDelayMs!!, 0.0)
        assertEquals(0L, started.freezeDurationMs)
        tracker.update(10_000, snap(state = 3, position = 5.0), false)
        val resumed = tracker.update(12_000, snap(state = 1, position = 5.0), true)
        assertEquals(5_000L, resumed.playbackDurationMs)
        assertEquals(2_000L, resumed.freezeDurationMs)
        assertTrue(resumed.contentVerified)
        assertEquals("720p (operator confirmed)", resumed.observedQuality)
    }

    @Test
    fun deadlineSnapshotIncludesCurrentBufferingIntervalWithoutDoubleCounting() {
        val tracker = YoutubePlaybackTracker(180, 720, 0)
        tracker.update(0, snap(state = 1), true)
        tracker.update(1000, snap(state = 3, position = 1.0), true)
        assertEquals(1250L, tracker.measurement(2250, true).freezeDurationMs)
        assertEquals(1250L, tracker.measurement(2250, true).freezeDurationMs)
        val resumed = tracker.update(3000, snap(state = 1, position = 1.0), true)
        assertEquals(2000L, resumed.freezeDurationMs)
        assertEquals(1000L, resumed.playbackDurationMs)
    }

    @Test
    fun threeMinuteMinimumDoesNotEndALongerVideo() {
        val tracker = YoutubePlaybackTracker(180, 1080, 0)
        tracker.update(0, snap(state = 1, duration = 240.0), true)
        val atMinimum = tracker.update(180_000, snap(state = 1, position = 180.0, duration = 240.0), true)
        assertFalse(atMinimum.playbackCompleted)
        assertNull(atMinimum.error)
        val ended = tracker.update(240_000, snap(state = 0, position = 240.0, duration = 240.0), true)
        assertTrue(ended.playbackCompleted)
        assertNull(ended.error)
    }

    @Test
    fun shortVideosAndPrematureEndsCannotPass() {
        val short = YoutubePlaybackTracker(180, 720, 0)
        short.update(0, snap(state = 1, duration = 30.0), true)
        val ended = short.update(30_000, snap(state = 0, position = 30.0, duration = 30.0), true)
        assertFalse(ended.playbackCompleted)
        assertTrue(ended.error!!.contains("at least 180"))
        val premature = YoutubePlaybackTracker(180, 720, 0)
        premature.update(0, snap(state = 1), true)
        assertFalse(premature.update(60_000, snap(state = 0, position = 60.0), true).playbackCompleted)
    }

    @Test
    fun seekingPausingAndSpeedChangesInvalidatePlayback() {
        val invalid = listOf(snap(state = 1, position = 50.0), snap(state = 2, position = 1.0),
            snap(state = 1, position = 1.0).copy(playbackRate = 2.0))
        for (snapshot in invalid) {
            val tracker = YoutubePlaybackTracker(180, 720, 0)
            tracker.update(0, snap(state = 1), true)
            val result = tracker.update(1000, snapshot, true)
            assertNotNull(result.error)
            assertFalse(result.measurementAvailable)
        }
    }

    @Test
    fun unconfirmedQualityStaysUnverifiedAfterSuccessfulFullPlayback() {
        val tracker = YoutubePlaybackTracker(180, 720, 0)
        tracker.update(0, snap(state = 1), false)
        val ended = tracker.update(180_000, snap(state = 0, position = 180.0), false)
        assertTrue(ended.playbackCompleted)
        assertFalse(ended.contentVerified)
        assertEquals("Not verified", ended.observedQuality)
    }

    @Test
    fun javascriptParserAcceptsWebViewQuotedPayloadAndReportsApiErrors() {
        val objectJson = JSONObject().put("ready", true).put("state", -1).put("position", 0)
            .put("duration", 0).put("error", 153).toString()
        val parsed = YoutubeSnapshot.parseJavascript(JSONObject.quote(objectJson))!!
        assertEquals(153, parsed.error)
        val tracker = YoutubePlaybackTracker(180, 720, 0)
        val failed = tracker.update(100, parsed, false)
        assertEquals("YouTube player error 153", failed.error)
        assertFalse(failed.measurementAvailable)
        assertNull(YoutubeSnapshot.parseJavascript("null"))
        assertNull(YoutubeSnapshot.parseJavascript("invalid JSON"))
    }

    private fun snap(state: Int, position: Double = 0.0, duration: Double = 180.0, loaded: Double = 0.0) =
        YoutubeSnapshot(state, position, duration, loaded)
}
