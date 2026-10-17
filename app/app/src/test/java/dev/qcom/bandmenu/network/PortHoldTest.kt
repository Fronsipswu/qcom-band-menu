package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PortHoldTest {
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

    private fun PortHold.ports(pci: Int, ports: Int?, rx: Int?, band: String? = "n41") =
        apply(round(pci, ports, rx, band)).carrierGroups.single().carriers.single().nr?.csiPorts

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
    fun aNewPciOrBandStartsOver() {
        val h = PortHold()
        assertEquals(8, h.ports(123, 8, 4))
        assertEquals(2, h.ports(456, 2, null))    // new PCI
        assertEquals(8, h.ports(456, 8, 4))
        assertEquals(4, h.ports(456, 4, null, band = "n78"))  // new band
    }

    private fun lte(pci: Int, tx: Int?, rx: Int?) = DiagCarrier(
        slot = 0, earfcn = 1275, pci = pci, band = 3, bandwidthMhz = 20.0, state = 2,
        rsrp = -90.0, rsrq = -10.0, sinr = 12.0, transportBlocks = if (tx != null) 100 else 0, mbps = 0.0,
        ulMbps = null, bler = null, mcs = null, rbs = null, modulation = null,
        rank = null, transmissionMode = null, txAntennas = tx, rxAntennas = rx
    )

    private fun PortHold.tx(pci: Int, tx: Int?, rx: Int?, band: String? = "B3"): Int? {
        val c = Carrier("P", band, "1275", 20.0, "Primary", pci, diag = lte(pci, tx, rx))
        val snap = round(pci, null, null).copy(mode = ServiceMode.LTE,
            carrierGroups = listOf(CarrierGroup("Carrier aggregation", "", listOf(c), table = true, diag = true)))
        return apply(snap).carrierGroups.single().carriers.single().diag?.txAntennas
    }

    @Test
    fun lteIdleKeepsTheLastTxCount() {
        val h = PortHold()
        assertEquals(null, h.tx(335, null, null))  // nothing seen yet
        assertEquals(4, h.tx(335, 4, 4))
        assertEquals(4, h.tx(335, null, null))     // idle: held
        assertEquals(2, h.tx(335, 2, 2))           // traffic: the live count
        assertEquals(2, h.tx(335, null, null))
        assertEquals(null, h.tx(151, null, null))  // new PCI starts over
        assertEquals(null, h.tx(151, null, null, band = "B1"))
    }
}
