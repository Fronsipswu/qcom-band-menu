package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Cell-Lock page's NR-ARFCN list: every NR-ARFCN seen, sorted, a known
 *  band or SCS kept and a missing one filled. */
class SeenNrArfcnsTest {

    @Test
    fun mergesSortedAndFillsGaps() {
        val old = listOf(SeenNrArfcn(529950, 41, null), SeenNrArfcn(643334, 78, 30))
        val new = listOf(SeenNrArfcn(529950, null, 30), SeenNrArfcn(152690, 1, 15), SeenNrArfcn(643334, 77, 15))
        assertEquals(
            listOf(SeenNrArfcn(152690, 1, 15), SeenNrArfcn(529950, 41, 30), SeenNrArfcn(643334, 78, 30)),
            mergeSeenNrArfcns(old, new)
        )
    }

    @Test
    fun emptyStartTakesTheReading() {
        val new = listOf(SeenNrArfcn(529950, 41, 30))
        assertEquals(new, mergeSeenNrArfcns(emptyList(), new))
    }
}
