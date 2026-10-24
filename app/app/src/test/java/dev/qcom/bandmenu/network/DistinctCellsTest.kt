package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The carrier tables' safeguard: one column per cell (channel + PCI). */
class DistinctCellsTest {

    private fun c(role: String, ch: String, pci: Int?, rsrp: Double? = null) =
        Carrier(role, "B3", ch, 20.0, "Active", pci, rsrp = rsrp)

    private fun group(vararg cs: Carrier) = CarrierGroup("Carrier aggregation", "", cs.toList(), table = true)

    @Test
    fun duplicateKeepsTheOneWithRsrp() {
        // As reported: S1 and S2 both 1850/153, only S2 measured.
        val p = c("P", "1652", 153, -111.0)
        val s1 = c("S1", "1850", 153)
        val s2 = c("S2", "1850", 153, -112.0)
        val s3 = c("S3", "500", 153, -109.9)
        assertEquals(listOf("P", "S2", "S3"), group(p, s1, s2, s3).distinctCells().carriers.map { it.role })
    }

    @Test
    fun samePciOnOtherChannelsStays() {
        val g = group(c("P", "1652", 153, -100.0), c("S1", "1850", 153, -101.0), c("S2", "500", 153, -99.0))
        assertSame(g, g.distinctCells())
    }

    @Test
    fun withoutRsrpTheFirstStays() {
        val g = group(c("P", "1652", 1, -90.0), c("S1", "1850", 2), c("S2", "1850", 2))
        assertEquals(listOf("P", "S1"), g.distinctCells().carriers.map { it.role })
    }

    @Test
    fun columnsWithoutPciAreLeftAlone() {
        // QMI's frequency-only NR SCells.
        val g = group(c("S1", "643334 (3650.0 MHz)", null), c("S2", "643334 (3650.0 MHz)", null))
        assertSame(g, g.distinctCells())
    }
}
