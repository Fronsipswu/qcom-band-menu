package dev.qcom.bandmenu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubSliderTest {
    @Test
    fun fastMovesBeforeRecompositionDoNotResetAccumulator() {
        val acc = ScrubAccumulator(initial = 10)
        // Step 1 crosses 10.5 -> emits 11, but Compose state (current) is still 10 on the next pointer event.
        assertEquals(11, acc.step(current = 10, max = 100, deltaSamples = 0.6f))
        assertEquals(10.6f, acc.acc, 0.001f)

        // Step 2 arrives before recomposition (current is still 10); accumulator must advance to 10.9, not reset to 10.
        assertNull(acc.step(current = 10, max = 100, deltaSamples = 0.3f))
        assertEquals(10.9f, acc.acc, 0.001f)

        // Recomposition catches up (current becomes 11); sub-sample fraction (10.9 + 0.7 = 11.6) must be preserved.
        assertEquals(12, acc.step(current = 11, max = 100, deltaSamples = 0.7f))
        assertEquals(11.6f, acc.acc, 0.001f)
    }

    @Test
    fun externalTimelineExtensionResetsAccumulatorToNewPosition() {
        val acc = ScrubAccumulator(initial = 10)
        assertEquals(11, acc.step(current = 10, max = 100, deltaSamples = 0.6f))

        // Timeline extension moves current externally to 40.
        assertEquals(41, acc.step(current = 40, max = 100, deltaSamples = 0.8f))
        assertEquals(40.8f, acc.acc, 0.001f)
    }
}
