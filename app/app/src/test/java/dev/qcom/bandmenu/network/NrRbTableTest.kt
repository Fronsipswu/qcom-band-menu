package dev.qcom.bandmenu.network

import dev.qcom.bandmenu.ui.mcsCell
import dev.qcom.bandmenu.ui.rbCell
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

    /** A carrier, DIAG records optional per argument. */
    private fun barCell(diag: DiagCarrier? = null, nr: DiagNrCarrier? = null) =
        Carrier("P", null, "", null, null, null, diag = diag, nr = nr)

    private fun lte(mcs: Double? = null, rbs: Double? = null) = DiagCarrier(
        0, null, null, null, null, null, null, null, null, 0, 0.0, null, null, mcs, rbs,
        null, null, null, null, null)

    private fun nr(mcs: Double? = null, rbs: Double?,
                  band: Int? = 78, arfcn: Long = 636_576, scsKhz: Int? = 30, bwMhz: Int? = 100) = DiagNrCarrier(
        0, null, 1, arfcn, null, band, bwMhz, scsKhz, null,
        null, null, null, null, null, 0, 0.0, null, null, mcs, rbs,
        null, null, null)

    @Test
    fun mcsCellValueAndFractionOf31() {
        val (text, fraction) = mcsCell(barCell(nr = nr(mcs = 15.5, rbs = 50.0)))
        assertEquals("16", text)                       // Math.round(15.5) rounds half-up
        assertEquals(0.5f, fraction!!, 1e-6f)
        // The value chain stays diag ?: nr, both records span 0..31.
        val (t28, f28) = mcsCell(barCell(diag = lte(mcs = 28.0)))
        assertEquals("28", t28)
        assertEquals(28f / 31f, f28!!, 1e-6f)
        assertEquals("31" to 1.0f, mcsCell(barCell(nr = nr(mcs = 31.0, rbs = 0.0))))
        assertEquals("0" to 0.0f, mcsCell(barCell(nr = nr(mcs = 0.0, rbs = 0.0))))
        // Above the top: the bar clamps full.
        assertEquals("40" to 1.0f, mcsCell(barCell(nr = nr(mcs = 40.0, rbs = 0.0))))
        // No MCS this window: plain "–", no fraction.
        val (tNull, fNull) = mcsCell(barCell(diag = lte(), nr = nr(mcs = null, rbs = null)))
        assertNull(tNull)
        assertNull(fNull)
    }

    @Test
    fun rbCellLteOverTheFixedHundred() {
        val (text, fraction) = rbCell(barCell(diag = lte(rbs = 100.0)))
        assertEquals("100", text)
        assertEquals(1.0f, fraction!!, 1e-6f)
        val (t, f) = rbCell(barCell(diag = lte(rbs = 240.0)))
        assertEquals("240", t)
        assertEquals(1.0f, f!!, 1e-6f)                  // above the maximum: clamped full
        val (t43, f43) = rbCell(barCell(diag = lte(rbs = 42.6)))
        assertEquals("43", t43)
        assertEquals(0.426f, f43!!, 1e-4f)
        val (tNone, fNone) = rbCell(barCell(diag = lte(rbs = null)))
        assertNull(tNone)
        assertNull(fNone)
    }

    @Test
    fun rbCellNrOverTheTables() {
        // FR1 defaults (band n78, 30 kHz, 100 MHz): 273 max.
        val (text, fraction) = rbCell(barCell(nr = nr(rbs = 136.5)))
        assertEquals("137", text)
        assertEquals(0.5f, fraction!!, 1e-6f)
        // FR2 (n257-class band, 120 kHz, 100 MHz): 66 max.
        val (t, f) = rbCell(barCell(nr = nr(rbs = 33.0, band = 257, arfcn = 2_077_152, scsKhz = 120)))
        assertEquals("33", t)
        assertEquals(0.5f, f!!, 1e-6f)
    }

    @Test
    fun rbCellNrUsesTheCellsFrequencyRange() {
        val (_, fr2f) = rbCell(barCell(nr = nr(rbs = 66.0, band = 258, arfcn = 2_077_152, scsKhz = 60)))
        assertEquals(0.5f, fr2f!!, 1e-6f)               // FR2 60 kHz, 100 MHz -> 132
        val (_, fr1f) = rbCell(barCell(nr = nr(rbs = 67.5, scsKhz = 60)))
        assertEquals(0.5f, fr1f!!, 1e-6f)              // FR1 60 kHz, 100 MHz -> 135
    }

    @Test
    fun rbCellNrWithoutCellConfigHasNoBar() {
        // The cell's SCS/bandwidth arrive with RRC reconfiguration or traffic;
        // until then the value shows with no bar.
        assertEquals("100", rbCell(barCell(nr = nr(rbs = 100.0, scsKhz = null))).first)
        assertNull(rbCell(barCell(nr = nr(rbs = 100.0, scsKhz = null))).second)
        assertNull(rbCell(barCell(nr = nr(rbs = 100.0, bwMhz = null))).second)
        // An SCS the band never carries: no table entry either.
        assertNull(rbCell(barCell(nr = nr(rbs = 100.0, scsKhz = 240, bwMhz = 100))).second)
    }
}
