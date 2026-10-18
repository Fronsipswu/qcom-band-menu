package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PortHoldTest {
    private fun nr(pci: Int, ports: Int?, rx: Int?, arfcn: Long = 509202) = DiagNrCarrier(
        cc = 0, pcell = true, pci = pci, arfcn = arfcn, dlArfcn = null, band = 41, bandwidthMhz = 100,
        scsKhz = 30, maxLayers = 4, rsrp = -90.0, rsrq = -11.0, sinr = 10.0, cqi = null, ri = null,
        transportBlocks = if (rx != null) 100 else 0, mbps = 0.0, ulMbps = null, bler = null, mcs = null,
        rbs = null, layers = null, modulation = null, rxAntennas = rx, csiPorts = ports
    )

    private fun round(pci: Int, ports: Int?, rx: Int?, band: String? = "n41", arfcn: Long = 509202): NetworkSnapshot {
        val c = Carrier("P", band, arfcn.toString(), 100.0, null, pci, nr = nr(pci, ports, rx, arfcn))
        return NetworkSnapshot(
            sim = 1, takenAtElapsedMs = 0, roundMs = 0, mode = ServiceMode.NR_SA, modeLabel = "5G SA",
            plmn = null, roaming = null, rrc = emptyList(), cells = emptyList(),
            carrierGroups = listOf(CarrierGroup("NR aggregation", "", listOf(c), table = true, diag = true, nrTable = true)),
            neighbors = emptyList(), antennas = emptyList(), failedReads = emptyList()
        )
    }

    private fun PortHold.ports(pci: Int, ports: Int?, rx: Int?, band: String? = "n41", arfcn: Long = 509202) =
        apply(round(pci, ports, rx, band, arfcn)).carrierGroups.single().carriers.single().nr?.csiPorts

    @Test
    fun idleKeepsThePortsConfirmedWithTraffic() {
        val h = PortHold()
        assertEquals(8, h.ports(123, 8, 4))       // traffic: 8 ports confirmed
        assertEquals(8, h.ports(123, 1, null))    // idle: a 1 doesn't replace it
        assertEquals(8, h.ports(123, null, null))
        assertEquals(8, h.ports(123, 1, null, band = null))  // band missing this round: same cell
    }

    @Test
    fun withTrafficTheLiveCountWinsEvenOne() {
        val h = PortHold()
        assertEquals(8, h.ports(123, 8, 4))
        assertEquals(1, h.ports(123, 1, 2))       // RX valid: 1 port is real
        assertEquals(1, h.ports(123, 8, null))    // and is what idle keeps
    }

    @Test
    fun beforeAnyTrafficTheLargestIdleCountIsShown() {
        val h = PortHold()
        assertEquals(1, h.ports(123, 1, null))
        assertEquals(8, h.ports(123, 8, null))
        assertEquals(8, h.ports(123, 1, null))
    }

    @Test
    fun aNewPciOrBandOrChannelStartsOver() {
        val h = PortHold()
        assertEquals(8, h.ports(123, 8, 4))
        assertEquals(2, h.ports(456, 2, null))    // new PCI
        assertEquals(8, h.ports(456, 8, 4))
        assertEquals(4, h.ports(456, 4, null, band = "n78"))  // new band
        assertEquals(8, h.ports(123, 8, 4, arfcn = 509202))
        assertEquals(2, h.ports(123, 2, null, arfcn = 530000)) // same PCI and band, new channel
    }

    @Test
    fun endcColumnRenamePreservesHoldWhileReleaseStartsOver() {
        val h = PortHold()
        val mcg = Carrier("MCG", "B3", "1275", 20.0, "Primary", 100, diag = lte(100, 4, 4, tm = 4))
        val scg = Carrier("SCG", "n41", "509202", 100.0, "Primary", 123, nr = nr(123, 8, 4, 509202))
        val nrScellS1 = Carrier("S1", "n41", "530000", 100.0, "Active", 222, nr = nr(222, 8, 4, 530000))
        val base = round(123, 8, 4).copy(mode = ServiceMode.NR_NSA, carrierGroups = emptyList())
        h.apply(base.copy(endc = CarrierGroup("ENDC", "", listOf(mcg, scg, nrScellS1), table = true, diag = true, endc = true)))

        // An LTE SCell is inserted as S1, renumbering the NR SCell from S1 to S2 while idle.
        val lteScell = Carrier("S1", "B1", "300", 20.0, "Active", 101, diag = lte(101, 2, 2, earfcn = 300))
        val nrScellS2Idle = nrScellS1.copy(role = "S2", nr = nr(222, 1, null, 530000))
        val renamed = h.apply(base.copy(endc = CarrierGroup("ENDC", "", listOf(mcg, lteScell, scg, nrScellS2Idle), table = true, diag = true, endc = true)))
        assertEquals(8, renamed.endc!!.carriers.last().nr?.csiPorts)

        // Carrier released for one round, then re-added idle with 2 ports: starts over.
        h.apply(base.copy(endc = CarrierGroup("ENDC", "", listOf(mcg, scg), table = true, diag = true, endc = true)))
        val readded = h.apply(base.copy(endc = CarrierGroup("ENDC", "", listOf(mcg, scg, nrScellS1.copy(nr = nr(222, 2, null, 530000))), table = true, diag = true, endc = true)))
        assertEquals(2, readded.endc!!.carriers.last().nr?.csiPorts)
    }

    private fun lte(pci: Int, tx: Int?, rx: Int?, earfcn: Long = 1275, tm: Int? = null) = DiagCarrier(
        slot = 0, earfcn = earfcn, pci = pci, band = 3, bandwidthMhz = 20.0, state = 2,
        rsrp = -90.0, rsrq = -10.0, sinr = 12.0, transportBlocks = if (tx != null) 100 else 0, mbps = 0.0,
        ulMbps = null, bler = null, mcs = null, rbs = null, modulation = null,
        rank = null, transmissionMode = tm, txAntennas = tx, rxAntennas = rx
    )

    private fun PortHold.lteDiag(pci: Int, tx: Int?, rx: Int?, band: String? = "B3", earfcn: Long = 1275, tm: Int? = null): DiagCarrier? {
        val c = Carrier("P", band, earfcn.toString(), 20.0, "Primary", pci, diag = lte(pci, tx, rx, earfcn, tm))
        val snap = round(pci, null, null).copy(mode = ServiceMode.LTE,
            carrierGroups = listOf(CarrierGroup("Carrier aggregation", "", listOf(c), table = true, diag = true)))
        return apply(snap).carrierGroups.single().carriers.single().diag
    }

    @Test
    fun lteIdleKeepsTheLastTxCountAndTxMode() {
        val h = PortHold()
        assertEquals(null, h.lteDiag(335, null, null)?.txAntennas)  // nothing seen yet
        val r1 = h.lteDiag(335, 4, 4, tm = 4)!!
        assertEquals(4, r1.txAntennas)
        assertEquals(4, r1.transmissionMode)
        val idle = h.lteDiag(335, null, null, tm = null)!!          // B126 absent: both held
        assertEquals(4, idle.txAntennas)
        assertEquals(4, idle.transmissionMode)
        val updated = h.lteDiag(335, 2, 2, tm = 9)!!                // new B126: live values win
        assertEquals(2, updated.txAntennas)
        assertEquals(9, updated.transmissionMode)
        val heldAgain = h.lteDiag(335, null, null, tm = null)!!
        assertEquals(2, heldAgain.txAntennas)
        assertEquals(9, heldAgain.transmissionMode)
        // Same PCI and band on a different EARFCN starts over.
        val movedChannel = h.lteDiag(335, null, null, earfcn = 1850)!!
        assertEquals(null, movedChannel.txAntennas)
        assertEquals(null, movedChannel.transmissionMode)
        assertEquals(null, h.lteDiag(151, null, null)?.txAntennas)  // new PCI starts over
        assertEquals(null, h.lteDiag(151, null, null, band = "B1")?.txAntennas)
    }
}
