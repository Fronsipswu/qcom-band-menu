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
    fun peakAtTheHeadroomStaysOnTheLowerStep() {
        assertEquals(10, niceStep(40.0))
        assertEquals(20, niceStep(40.0001))
    }
}
