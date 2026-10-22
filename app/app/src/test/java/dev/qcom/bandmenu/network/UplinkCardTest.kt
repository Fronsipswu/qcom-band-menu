package dev.qcom.bandmenu.network

import dev.qcom.bandmenu.ui.ulMcsCell
import dev.qcom.bandmenu.ui.ulRbCell
import dev.qcom.bandmenu.ui.uplinkCarriers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uplink card: the helper's "ul_*" keys, and which carriers get a column
 * (the primaries always, an SCell only in a round it sent something).
 */
class UplinkCardTest {

    private val stats = DiagStats.parse(JSONObject("""{"ms":1000,
        "carriers":[
          {"slot":0,"earfcn":1300,"pci":10,"tb":5,"mbps":1.0,"ul_mbps":2.5,"ul_rbs":12.4,"ul_mod":"16QAM","ul_mcs":17.6,"ul_pwr":11.25},
          {"slot":1,"earfcn":3050,"pci":11,"tb":5,"mbps":1.0}],
        "nr":[
          {"cc":0,"pcell":true,"pci":300,"arfcn":630000,"bw":100,"scs":30,"band":78,"tb":3,"mbps":50.0,
           "ul_mbps":10.0,"ul_rbs":136.5,"ul_mcs":9.4,"ul_mod":"QPSK","ul_layers":1.5,"ul_tx_mode":"SMDL","ul_pwr":22.9},
          {"cc":1,"pcell":false,"pci":301,"arfcn":640000,"tb":3,"mbps":20.0}]}"""))

    @Test
    fun uplinkKeysParsed() {
        val lte = stats.carriers[0]
        assertEquals(12.4, lte.ulRbs!!, 1e-9)
        assertEquals(17.6, lte.ulMcs!!, 1e-9)
        assertEquals("16QAM", lte.ulModulation)
        assertEquals(11.25, lte.ulPower!!, 1e-9)
        assertTrue(lte.hasUplink)
        assertFalse(stats.carriers[1].hasUplink)
        val nr = stats.nr[0]
        assertEquals(1.5, nr.ulLayers!!, 1e-9)
        assertEquals("SMDL", nr.ulTxMode)
        assertEquals(22.9, nr.ulPower!!, 1e-9)
        assertTrue(nr.hasUplink)
        assertFalse(stats.nr[1].hasUplink)
        assertNull(stats.nr[1].ulMcs)
    }

    @Test
    fun cellsRoundTheMeansAndScaleTheBars() {
        val lte = carrier("P", diag = stats.carriers[0])
        assertEquals("18" to (17.6 / 31).toFloat(), ulMcsCell(lte))
        assertEquals("12" to (12.4 / 100).toFloat(), ulRbCell(lte))
        // n78, 30 kHz, 100 MHz: 273 RBs.
        val nr = carrier("P", nr = stats.nr[0])
        assertEquals("137" to (136.5 / 273).toFloat(), ulRbCell(nr))
        assertEquals(null to null, ulMcsCell(carrier("S1", nr = stats.nr[1])))
    }

    @Test
    fun primariesAlwaysScellsOnlyWithUplink() {
        val lteGroup = CarrierGroup("LTE", "", listOf(
            carrier("P", diag = stats.carriers[0]), carrier("S1", diag = stats.carriers[1])), table = true, diag = true)
        assertEquals(listOf("P"), uplinkCarriers(snapshot(listOf(lteGroup))).map { it.role })
        val endc = CarrierGroup("ENDC", "", listOf(
            carrier("MCG", diag = stats.carriers[1]), carrier("SCG", nr = stats.nr[1]),
            carrier("S1", nr = stats.nr[0])), table = true, diag = true, endc = true)
        assertEquals(listOf("MCG", "SCG", "S1"), uplinkCarriers(snapshot(listOf(lteGroup), endc)).map { it.role })
    }

    @Test
    fun noColumnsWithoutDiag() {
        val qmiOnly = CarrierGroup("LTE", "", listOf(carrier("P")), table = true)
        assertTrue(uplinkCarriers(snapshot(listOf(qmiOnly))).isEmpty())
    }

    private fun carrier(role: String, diag: DiagCarrier? = null, nr: DiagNrCarrier? = null) =
        Carrier(role, if (nr != null) "n78" else "B3", "1300", null, null, null, diag = diag, nr = nr)

    private fun snapshot(groups: List<CarrierGroup>, endc: CarrierGroup? = null) = NetworkSnapshot(
        sim = 1, takenAtElapsedMs = 0, roundMs = 1000, mode = ServiceMode.LTE, modeLabel = "LTE", plmn = null,
        roaming = null, rrc = emptyList(), cells = emptyList(), carrierGroups = groups, endc = endc,
        neighbors = emptyList(), antennas = emptyList(), failedReads = emptyList()
    )
}
