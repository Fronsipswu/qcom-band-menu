package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PUCCH power held between PUCCH windows: primaries until the next value,
 *  SCells for up to 3 s, never across a cell change. */
class PucchHoldTest {

    private fun lte(slot: Int, pci: Int, pucch: Double?) = DiagCarrier(
        slot = slot, earfcn = 1300L + slot, pci = pci, band = 3, bandwidthMhz = 20.0, state = 2,
        rsrp = null, rsrq = null, sinr = null, transportBlocks = 1, mbps = 1.0, ulMbps = null,
        bler = null, mcs = null, rbs = null, modulation = null, rank = null, transmissionMode = null,
        txAntennas = null, rxAntennas = null, ulPucch = pucch
    )

    private fun snap(t: Long, pcellPci: Int, pcell: Double?, scell: Double?) = NetworkSnapshot(
        sim = 1, takenAtElapsedMs = t, roundMs = 1000, mode = ServiceMode.LTE, modeLabel = "LTE", plmn = null,
        roaming = null, rrc = emptyList(), cells = emptyList(),
        carrierGroups = listOf(CarrierGroup("LTE", "", listOf(
            Carrier("P", "B3", "1300", null, null, pcellPci, diag = lte(0, pcellPci, pcell)),
            Carrier("S1", "B3", "1301", null, null, 20, diag = lte(1, 20, scell))
        ), table = true, diag = true)),
        neighbors = emptyList(), antennas = emptyList(), failedReads = emptyList()
    )

    private fun NetworkSnapshot.pucch(i: Int) = carrierGroups[0].carriers[i].diag?.ulPucch

    @Test
    fun primaryHoldsUntilTheNextValueScellForThreeSeconds() {
        val h = PucchHold()
        h.apply(snap(0, 10, 5.0, 2.0))
        val a = h.apply(snap(2_000, 10, null, null))
        assertEquals(5.0, a.pucch(0)!!, 0.0)
        assertEquals(2.0, a.pucch(1)!!, 0.0)
        val b = h.apply(snap(60_000, 10, null, null))
        assertEquals(5.0, b.pucch(0)!!, 0.0)
        assertNull(b.pucch(1))
        assertEquals(7.0, h.apply(snap(61_000, 10, 7.0, null)).pucch(0)!!, 0.0)
    }

    @Test
    fun aDifferentCellStartsOver() {
        val h = PucchHold()
        h.apply(snap(0, 10, 5.0, null))
        assertNull(h.apply(snap(1_000, 11, null, null)).pucch(0))
    }
}
