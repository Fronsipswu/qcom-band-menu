package dev.qcom.bandmenu.ui

import androidx.compose.foundation.ScrollState
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollAnchorTest {
    private fun ScrollState.setMax(max: Int) {
        ScrollState::class.java.getDeclaredMethod("setMaxValue\$foundation", Int::class.javaPrimitiveType)
            .invoke(this, max)
    }

    @Test
    fun shrinkAfterMeasurementClampPreservesViewportOffset() {
        val scroll = ScrollState(700)
        scroll.setMax(1000)
        val anchor = ScrollAnchor(scroll)
        anchor.placed("1:thput", 1000f, 150)

        // Measurement clamps scroll.value from 700 to 200 before placement runs.
        scroll.setMax(200)
        anchor.placed("1:thput", 500f, 150)
        scroll.setMax(700)
        anchor.contentResized()

        assertEquals(200, scroll.value)
        assertEquals(0f, anchor.slack, 0.01f)
    }

    @Test
    fun consecutiveShiftsBeforeResizePreserveFinalTarget() {
        val scroll = ScrollState(100)
        scroll.setMax(100)
        val anchor = ScrollAnchor(scroll)
        anchor.placed("1:thput", 100f, 150)
        anchor.placed("1:thput", 150f, 150) // +50 cannot scroll until content grows
        anchor.placed("1:thput", 125f, 150) // -25 adjusts target to 125

        scroll.setMax(200)
        anchor.contentResized()

        assertEquals(125, scroll.value)
    }

    @Test
    fun clampToZeroStillCompensatesAndRestoresAcrossTransientShrink() {
        val scroll = ScrollState(300)
        scroll.setMax(500)
        val anchor = ScrollAnchor(scroll)
        anchor.placed("1:thput", 700f, 150) // viewport Y = 400

        // Cards above shrink by 300 px, bringing scroll legitimately to 0.
        anchor.placed("1:thput", 400f, 150)
        assertEquals(0, scroll.value)

        // Next sample restores the 300 px above; scroll must return to 300, not stay at 0.
        anchor.placed("1:thput", 700f, 150)
        assertEquals(300, scroll.value)

        // Measurement clamping directly to 0 when target is 100 uses slack and restores.
        scroll.setMax(0)
        anchor.placed("1:thput", 500f, 150)
        assertEquals(100f, anchor.slack, 0.01f)
        scroll.setMax(100)
        anchor.contentResized()
        assertEquals(100, scroll.value)
    }

    @Test
    fun initialPassSelectsMiddleCardAndKeepsItAcrossInsertionsAndRemovals() {
        val scroll = ScrollState(600)
        scroll.setMax(1200)
        val anchor = ScrollAnchor(scroll).apply {
            viewTop = 0f
            viewBottom = 800f // mid in content = 400 + 600 = 1000
        }

        // Initial top-to-bottom pass places summary first, then lte, thput, antennas.
        anchor.placed("1:summary", 0f, 300)
        anchor.placed("1:lte", 310f, 300)
        anchor.placed("1:thput", 900f, 250) // covers mid = 1000, viewport Y = 300
        anchor.placed("1:antennas", 1160f, 200)

        // Next pass: a new SCG card is inserted above lte/thput (+250 px).
        anchor.placed("1:summary", 0f, 300)
        anchor.placed("1:cell:NR SCG", 310f, 240)
        anchor.placed("1:lte", 560f, 300)
        anchor.placed("1:thput", 1150f, 250)
        anchor.placed("1:antennas", 1410f, 200)

        assertEquals(850, scroll.value) // 1150 - 300 = 850
    }
}
