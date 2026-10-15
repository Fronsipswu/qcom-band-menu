package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hand-checked values from TS 38.104: the max-RB tables and FR derivation are
 * pure table code, so these run everywhere, including CI. */
class NrRbTableTest {

    /** (scs kHz, bw MHz) -> max NRB, FR1. */
    private val fr1 = listOf(
        Pair(15, 5) to 25, Pair(15, 10) to 52, Pair(15, 15) to 79, Pair(15, 20) to 106,
        Pair(15, 25) to 133, Pair(15, 30) to 160, Pair(15, 40) to 216, Pair(15, 50) to 270,
        Pair(30, 5) to 11, Pair(30, 10) to 24, Pair(30, 15) to 38, Pair(30, 20) to 51,
        Pair(30, 25) to 65, Pair(30, 30) to 78, Pair(30, 40) to 106, Pair(30, 50) to 133,
        Pair(30, 60) to 162, Pair(30, 70) to 189, Pair(30, 80) to 217, Pair(30, 90) to 245,
        Pair(30, 100) to 273,
        Pair(60, 10) to 11, Pair(60, 15) to 18, Pair(60, 20) to 24, Pair(60, 25) to 31,
        Pair(60, 30) to 38, Pair(60, 40) to 51, Pair(60, 50) to 65, Pair(60, 60) to 79,
        Pair(60, 70) to 93, Pair(60, 80) to 107, Pair(60, 90) to 121, Pair(60, 100) to 135
    )

    /** (scs kHz, bw MHz) -> max NRB, FR2. */
    private val fr2 = listOf(
        Pair(60, 50) to 66, Pair(60, 100) to 132, Pair(60, 200) to 264,
        Pair(120, 50) to 32, Pair(120, 100) to 66, Pair(120, 200) to 132, Pair(120, 400) to 264
    )

    @Test
    fun maxResourceBlocksTable() {
        fr1.forEach { (scsBw, nrb) -> assertEquals(nrb, nrMaxResourceBlocks(1, scsBw.first, scsBw.second)) }
        fr2.forEach { (scsBw, nrb) -> assertEquals(nrb, nrMaxResourceBlocks(2, scsBw.first, scsBw.second)) }
    }

    @Test
    fun sixtyKhzDiffersBetweenFR1AndFR2() {
        assertEquals(65, nrMaxResourceBlocks(1, 60, 50))
        assertEquals(66, nrMaxResourceBlocks(2, 60, 50))
        assertEquals(135, nrMaxResourceBlocks(1, 60, 100))
        assertEquals(132, nrMaxResourceBlocks(2, 60, 100))
    }

    @Test
    fun unknownCombinationsHaveNoMaximum() {
        assertNull(nrMaxResourceBlocks(1, 240, 100))
        assertNull(nrMaxResourceBlocks(2, 15, 50))
        assertNull(nrMaxResourceBlocks(1, 30, 200))
        assertNull(nrMaxResourceBlocks(2, 120, 250))
    }

    @Test
    fun frequencyRangeFromBandThenArfcn() {
        // The configured band decides when known: n257-n261 are FR2...
        assertEquals(2, nrFrequencyRange(257, 500_000))
        assertEquals(2, nrFrequencyRange(261, 0))
        // ...otherwise the SSB ARFCN's raster (2_016_667 is its first).
        assertEquals(2, nrFrequencyRange(null, 2_016_667))
        assertEquals(2, nrFrequencyRange(41, 2_016_668))
        assertEquals(1, nrFrequencyRange(null, 2_016_666))
        assertEquals(1, nrFrequencyRange(41, 501_000))
        assertEquals(1, nrFrequencyRange(null, 600_000))
    }
}
