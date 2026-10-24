package dev.qcom.bandmenu.ui

import dev.qcom.bandmenu.CellLockState
import dev.qcom.bandmenu.LteCellLockEntry
import dev.qcom.bandmenu.LteCellLockState
import dev.qcom.bandmenu.NrArfcnLockEntry
import dev.qcom.bandmenu.NrCellLockState
import dev.qcom.bandmenu.NrPciLock
import dev.qcom.bandmenu.network.Band
import dev.qcom.bandmenu.network.LockRequest
import dev.qcom.bandmenu.network.LockRole
import dev.qcom.bandmenu.network.LockTarget
import dev.qcom.bandmenu.network.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows the Cell-Lock table paints locked. A locked cell keeps its colour
 * when it becomes the serving one: the modem then reports it with PCI and
 * band, so the row's [LockRequest] changes shape while the lock stays.
 */
class CellLockMatchTest {

    private fun nrRow(
        id: String,
        channel: Long,
        lock: LockRequest?,
        role: LockRole = LockRole.NEIGHBOUR
    ) = LockTarget(
        rat = Rat.NR, role = role, idLabel = "PCI", id = id,
        channelLabel = "NARFCN", channel = channel,
        band = Band(Rat.NR, 28, "n28"), scsKhz = 15, lock = lock
    )

    private fun lteRow(id: String, channel: Long, lock: LockRequest?) = LockTarget(
        rat = Rat.LTE, role = LockRole.NEIGHBOUR, idLabel = "PCI", id = id,
        channelLabel = "EARFCN", channel = channel,
        band = Band(Rat.LTE, 3, "B3"), lock = lock
    )

    private val pciState = CellLockState(
        nr = NrCellLockState(
            valid = true, type = "pci",
            pciLock = NrPciLock(pci = 269, scsKhz = 15, arfcn = 156510, bands = setOf(28))
        )
    )

    private val arfcnState = CellLockState(
        nr = NrCellLockState(
            valid = true, type = "arfcn",
            arfcnLock = listOf(NrArfcnLockEntry(arfcn = 156510, scsKhz = 15))
        )
    )

    private val lteState = CellLockState(
        lte = LteCellLockState(valid = true, locks = listOf(LteCellLockEntry(pci = 42, earfcn = 1300)))
    )

    @Test
    fun nrPciLockMarksItsCell() {
        val locked = nrRow("269", 156510, LockRequest.NrCell(156510, 269, 15, 28))
        assertEquals(LockKind.CELL, locked.lockKindIn(pciState))
        assertTrue(locked.isLockedIn(pciState))
    }

    /**
     * The reported bug: a neighbour that gets locked moves to the top of the
     * list as the serving cell, where the modem knows its PCI and band — so its
     * request is an NrCell, not the NrFrequency it had as a neighbour. It must
     * stay green.
     */
    @Test
    fun lockedNeighbourStaysLockedWhenItBecomesServing() {
        val asNeighbour = nrRow("—", 156510, LockRequest.NrFrequency(156510, 15))
        val asServing = nrRow("269", 156510, LockRequest.NrCell(156510, 269, 15, 28), LockRole.SERVING)
        assertEquals(LockKind.FREQUENCY, asNeighbour.lockKindIn(arfcnState))
        assertTrue("the serving row is the same locked cell", asServing.isLockedIn(arfcnState))
        assertEquals(LockKind.FREQUENCY, asServing.lockKindIn(arfcnState))
    }

    @Test
    fun frequencyLockMarksEveryCellOnTheFrequency() {
        val same = nrRow("271", 156510, LockRequest.NrCell(156510, 271, 15, 28))
        val other = nrRow("272", 156520, LockRequest.NrCell(156520, 272, 15, 28))
        assertEquals(LockKind.FREQUENCY, same.lockKindIn(arfcnState))
        assertEquals(LockKind.NONE, other.lockKindIn(arfcnState))
        assertFalse(other.isLockedIn(arfcnState))
    }

    @Test
    fun nrPciLockWithAnotherArfcnMarksNothing() {
        val row = nrRow("269", 156520, LockRequest.NrCell(156520, 269, 15, 28))
        assertEquals(LockKind.NONE, row.lockKindIn(pciState))
    }

    @Test
    fun lteLockMarksItsCell() {
        assertEquals(LockKind.CELL, lteRow("42", 1300, LockRequest.LteCell(1300, 42)).lockKindIn(lteState))
        assertEquals(LockKind.NONE, lteRow("43", 1300, LockRequest.LteCell(1300, 43)).lockKindIn(lteState))
    }

    @Test
    fun noStateLocksNothing() {
        assertEquals(LockKind.NONE, nrRow("269", 156510, LockRequest.NrCell(156510, 269, 15, 28)).lockKindIn(null))
    }
}
