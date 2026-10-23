package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Cell-Lock table's serving rows: the carrier tables' order (primary
 *  first, then SCells), each with the lock that pins it. */
class ServingLockTargetsTest {

    private fun snapshot(groups: List<CarrierGroup>, endc: CarrierGroup? = null) = NetworkSnapshot(
        sim = 1, takenAtElapsedMs = 0, roundMs = 1000, mode = ServiceMode.LTE, modeLabel = "LTE", plmn = null,
        roaming = null, rrc = emptyList(), cells = emptyList(), carrierGroups = groups, endc = endc,
        neighbors = emptyList(), antennas = emptyList(), failedReads = emptyList()
    )

    @Test
    fun lteCaRowsInTableOrderWithCellLocks() {
        val lte = CarrierGroup("LTE", "", listOf(
            Carrier("P", "B3", "1300", 20.0, "Primary", 10, rsrp = -90.0, rsrq = -10.0),
            Carrier("S1", "B7", "3050", 20.0, "Active", 11, rsrp = -95.0, rsrq = -11.0),
            Carrier("S2", "B20", "6300", 10.0, "Active", null)
        ), table = true)
        val rows = servingLockTargets(snapshot(listOf(lte)))
        assertEquals(listOf(1300L, 3050L, 6300L), rows.map { it.channel })
        assertEquals(LockRole.SERVING, rows[0].role)
        assertEquals(LockRequest.LteCell(1300, 10), rows[0].lock)
        assertEquals(LockRequest.LteCell(3050, 11), rows[1].lock)
        assertEquals("B7", rows[1].band?.label)
        assertEquals(-95.0, rows[1].metrics[0].value!!, 0.0)
        // No PCI: listed, but nothing to lock to.
        assertNull(rows[2].lock)
    }

    @Test
    fun nsaUsesTheCombinedTableNrRowsLockAsCells() {
        val endc = CarrierGroup("ENDC", "", listOf(
            Carrier("MCG", "B3", "1300", 20.0, "Primary", 10),
            Carrier("SCG", "n78", "630000", 100.0, "Primary", 300)
        ), table = true, endc = true)
        val rows = servingLockTargets(snapshot(emptyList(), endc))
        assertEquals(listOf(Rat.LTE, Rat.NR), rows.map { it.rat })
        // n78 without a reported SCS: the band's usual 30 kHz.
        assertEquals(LockRequest.NrCell(630000, 300, 30, 78), rows[1].lock)
    }
}
