package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CsiPortHoldTest {
    private fun nr(pci: Int, ports: Int?, rx: Int?) = DiagNrCarrier(
        cc = 0, pcell = true, pci = pci, arfcn = 509202, dlArfcn = null, band = 41, bandwidthMhz = 100,
        scsKhz = 30, maxLayers = 4, rsrp = -90.0, rsrq = -11.0, sinr = 10.0, cqi = null, ri = null,
        transportBlocks = if (rx != null) 100 else 0, mbps = 0.0, ulMbps = null, bler = null, mcs = null,
        rbs = null, layers = null, modulation = null, rxAntennas = rx, csiPorts = ports
    )

    private fun round(pci: Int, ports: Int?, rx: Int?, band: String? = "n41"): NetworkSnapshot {
        val c = Carrier("P", band, "509202", 100.0, null, pci, nr = nr(pci, ports, rx))
        return NetworkSnapshot(
            sim = 1, takenAtElapsedMs = 0, roundMs = 0, mode = ServiceMode.NR_SA, modeLabel = "5G SA",
            plmn = null, roaming = null, rrc = emptyList(), cells = emptyList(),
            carrierGroups = listOf(CarrierGroup("NR aggregation", "", listOf(c), table = true, diag = true, nrTable = true)),
            neighbors = emptyList(), antennas = emptyList(), failedReads = emptyList()
        )
    }

    private fun CsiPortHold.ports(pci: Int, ports: Int?, rx: Int?, band: String? = "n41") =
        apply(round(pci, ports, rx, band)).carrierGroups.single().carriers.single().nr?.csiPorts

    @Test
    fun idleKeepsThePortsConfirmedWithTraffic() {
        val h = CsiPortHold()
        assertEquals(8, h.ports(123, 8, 4))       // traffic: 8 ports confirmed
        assertEquals(8, h.ports(123, 1, null))    // idle: a 1 doesn't replace it
        assertEquals(8, h.ports(123, null, null))
        assertEquals(8, h.ports(123, 1, null, band = null))  // band missing this round: same cell
    }

    @Test
    fun withTrafficTheLiveCountWinsEvenOne() {
        val h = CsiPortHold()
        assertEquals(8, h.ports(123, 8, 4))
        assertEquals(1, h.ports(123, 1, 2))       // RX valid: 1 port is real
        assertEquals(1, h.ports(123, 8, null))    // and is what idle keeps
    }

    @Test
    fun beforeAnyTrafficTheLargestIdleCountIsShown() {
        val h = CsiPortHold()
        assertEquals(1, h.ports(123, 1, null))
        assertEquals(8, h.ports(123, 8, null))
        assertEquals(8, h.ports(123, 1, null))
    }

    @Test
    fun aNewPciOrBandStartsOver() {
        val h = CsiPortHold()
        assertEquals(8, h.ports(123, 8, 4))
        assertEquals(2, h.ports(456, 2, null))    // new PCI
        assertEquals(8, h.ports(456, 8, 4))
        assertEquals(4, h.ports(456, 4, null, band = "n78"))  // new band
    }
}
