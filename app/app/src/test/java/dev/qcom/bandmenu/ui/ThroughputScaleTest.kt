package dev.qcom.bandmenu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThroughputScaleTest {
    @Test
    fun idleLinkUsesTheSmallestScale() {
        assertEquals(1, niceStep(0.0))
        assertEquals(1, niceStep(0.04))
        assertEquals(1, niceStep(4.0))      // 4 <= 0.8 * 5
    }

    @Test
    fun stepsFollowOneTwoFive() {
        assertEquals(2, niceStep(4.1))
        assertEquals(5, niceStep(8.1))
        assertEquals(10, niceStep(32.2))    // 0-50 Mbps for a 32 Mbps download
        assertEquals(20, niceStep(40.1))
        assertEquals(50, niceStep(150.0))
        assertEquals(200, niceStep(700.0))
        assertEquals(1000, niceStep(3500.0))
    }

    @Test
    fun cursorAtTheRightEdgeOnTheNewestSample() {
        assertEquals(1_000_000L - 120_000, windowStart(1_000_000, 1_000_000))
        // The newest sample before the cursor (a round without throughput): still the edge.
        assertEquals(1_000_000L - 120_000, windowStart(1_000_000, 995_000))
    }

    @Test
    fun scrubbingBackSlidesTheCursorToTheMiddle() {
        // 10 s back: the window ends at the newest sample, the cursor 10 s from the edge.
        assertEquals(1_010_000L - 120_000, windowStart(1_000_000, 1_010_000))
        // Over half a window back: centred.
        assertEquals(1_000_000L - 60_000, windowStart(1_000_000, 1_060_000))
        assertEquals(1_000_000L - 60_000, windowStart(1_000_000, 1_500_000))
    }

    @Test
    fun peakAtTheHeadroomStaysOnTheLowerStep() {
        assertEquals(10, niceStep(40.0))
        assertEquals(20, niceStep(40.0001))
    }
}
