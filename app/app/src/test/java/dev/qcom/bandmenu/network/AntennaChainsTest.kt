package dev.qcom.bandmenu.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The antenna card with DIAG: a group per serving carrier (PCell, SCells; NSA's NR PSCell) from
 * the helper's per-chain arrays, QMI's serving-cell chains for a RAT the helper has none for.
 * Hand-built frames and helper replies (no device data).
 */
class AntennaChainsTest {

    private fun tlv(tag: Int, vararg value: Int) =
        byteArrayOf(tag.toByte(), (value.size and 0xFF).toByte(), (value.size shr 8).toByte()) +
            ByteArray(value.size) { value[it].toByte() }

    private fun frame(msg: Int, vararg tlvs: ByteArray): ReadOutcome {
        val body = tlv(0x02, 0, 0, 0, 0) + tlvs.fold(ByteArray(0)) { a, b -> a + b }
        return ReadOutcome.Ok(QmiFrame.parse(byteArrayOf(2, 1, 0, (msg and 0xFF).toByte(), (msg shr 8).toByte(),
            (body.size and 0xFF).toByte(), (body.size shr 8).toByte()) + body))
    }

    private fun le32(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v ushr 24) and 0xFF)

    private val lteInService = tlv(0x14, 2, 2)

    /** NSA: LTE in service and the NR leg's PCI (with DIAG's NR measurements). */
    private val nsaSys = frame(NasMsg.SYS_INFO, lteInService, tlv(0x54, 9, 0))

    /** QMI's LTE chains: RX0 and RX1 tuned (RSRP in tenths), in traffic at 12.3 dBm. */
    private val lteTxRx = frame(
        NasMsg.TX_RX_INFO,
        tlv(0x10, 1, *le32(-800), *le32(Int.MIN_VALUE), *le32(Int.MIN_VALUE), *le32(-852), *le32(-1)),
        tlv(0x11, 1, *le32(-820), *le32(Int.MIN_VALUE), *le32(Int.MIN_VALUE), *le32(-874), *le32(-1)),
        tlv(0x12, 1, *le32(123))
    )

    private val lteCarriers = """
        [{"slot":0,"earfcn":1650,"pci":11,"state":2,"band":3,"bw":20.0,"rsrp":-86.0,"rsrq":-10.0,"sinr":15.0,
          "rsrp_rx":[-86.0,-90.0,-95.5,null],"rsrq_rx":[-10.0,-12.0,-11.5,null],"sinr_rx":[15.0,12.5,8.0,null]},
         {"slot":1,"earfcn":300,"pci":22,"state":2,"band":1,"bw":10.0,
          "rsrp_rx":[-94.0,-95.0,null,null],"rsrq_rx":[-11.25,-11.88,null,null],"sinr_rx":[6.0,5.0,null,null]},
         {"slot":2,"earfcn":3750,"pci":33,"state":1,"band":8,"bw":10.0}]
    """.trimIndent()

    private val nrCarriers = """
        [{"cc":0,"pcell":true,"pci":9,"arfcn":640000,"band":78,"bw":100,"rsrp":-88.0,"rsrq":-11.0,"sinr":20.0,
          "rsrp_rx":[-88.0,-89.5,-90.5,-91.25],"sinr_rx":[20.0,10.0,0.0,null],"csinr_rx":[13.0,10.0,null,null]},
         {"cc":1,"pcell":false,"pci":5,"arfcn":155000,"band":28,"rsrp":-95.0,"rsrp_rx":[-95.0,-97.0,null,null]}]
    """.trimIndent()

    private fun stats(carriers: String = "[]", nr: String = "[]") =
        DiagStats.parse(JSONObject("""{"ms":1000,"carriers":$carriers,"nr":$nr}"""))

    private fun build(reads: Map<String, ReadOutcome>, diag: DiagStats) = SnapshotBuilder(reads, diag = diag).build(1, 0, 0)

    @Test
    fun chainArraysParsedWithNullChains() {
        val s = stats(lteCarriers, nrCarriers)
        assertEquals(listOf(-86.0, -90.0, -95.5, null), s.carriers[0].rsrpRx)
        assertEquals(listOf(15.0, 12.5, 8.0, null), s.carriers[0].sinrRx)
        assertNull(s.carriers[2].rsrpRx)
        assertEquals(listOf(13.0, 10.0, null, null), s.nr[0].csiSinrRx)
        assertNull(s.nr[1].sinrRx)
    }

    @Test
    fun everyLteCarrierWithChainsHasItsGroup() {
        val snap = build(mapOf(NetworkReads.SYS to frame(NasMsg.SYS_INFO, lteInService), NetworkReads.TXRX_LTE to lteTxRx),
            stats(lteCarriers))
        assertEquals(ServiceMode.LTE, snap.mode)
        // The deactivated S2 has no chain values: no group.
        assertEquals(listOf("PCell", "SCell 1"), snap.antennas.map { it.carrier })
        val (pcell, scell) = snap.antennas
        assertEquals(Triple("B3", "1650", 11), Triple(pcell.band, pcell.channel, pcell.pci))
        assertEquals(listOf(0, 1, 2), pcell.chains.map { it.chain })
        assertEquals(listOf(-86.0, -90.0, -95.5), pcell.chains.map { it.rsrp })
        assertEquals(listOf(-10.0, -12.0, -11.5), pcell.chains.map { it.rsrq })
        assertEquals(listOf(15.0, 12.5, 8.0), pcell.chains.map { it.sinr })
        assertEquals(listOf(-94.0, -95.0), scell.chains.map { it.rsrp })
        // QMI's transmit power stays with the first group only.
        assertEquals(12.3, pcell.txPower!!, 1e-9)
        assertEquals(true, pcell.inTraffic)
        assertNull(scell.txPower)
    }

    @Test
    fun qmiChainsWhenTheHelperHasNone() {
        val noChains = """[{"slot":0,"earfcn":1650,"pci":11,"state":2,"band":3,"rsrp":-86.0}]"""
        val snap = build(mapOf(NetworkReads.SYS to frame(NasMsg.SYS_INFO, lteInService), NetworkReads.TXRX_LTE to lteTxRx),
            stats(noChains))
        val qmi = snap.antennas.single()
        assertNull(qmi.carrier)
        assertEquals(listOf(-85.2, -87.4), qmi.chains.map { it.rsrp })
        assertEquals(12.3, qmi.txPower!!, 1e-9)
    }

    @Test
    fun nrPcellAndScellsInSa() {
        val snap = build(mapOf(NetworkReads.SYS to frame(NasMsg.SYS_INFO, tlv(0x4A, 2, 2))), stats(nr = nrCarriers))
        assertEquals(ServiceMode.NR_SA, snap.mode)
        assertEquals(listOf("PCell", "SCell 1"), snap.antennas.map { it.carrier })
        assertTrue(snap.antennas.all { it.rat == Rat.NR })
        val pcell = snap.antennas[0]
        assertEquals(Triple("n78", "640000", 9), Triple(pcell.band, pcell.channel, pcell.pci))
        // No RX count here: RX 3 has an RSRP but neither SINR, so it is left out.
        assertEquals(listOf(-88.0, -89.5, -90.5), pcell.chains.map { it.rsrp })
        assertEquals(listOf(20.0, 10.0, 0.0), pcell.chains.map { it.sinr })
        assertEquals(listOf(13.0, 10.0, null), pcell.chains.map { it.csiSinr })
        assertEquals(listOf(-95.0, -97.0), snap.antennas[1].chains.map { it.rsrp })
    }

    @Test
    fun nsaShowsLteCarriersThenTheNrPscell() {
        val snap = build(mapOf(NetworkReads.SYS to nsaSys), stats(lteCarriers, nrCarriers))
        assertEquals(ServiceMode.NR_NSA, snap.mode)
        assertEquals(
            listOf(Rat.LTE to "PCell", Rat.LTE to "SCell 1", Rat.NR to "PSCell", Rat.NR to "SCell 1"),
            snap.antennas.map { it.rat to it.carrier }
        )
    }

    @Test
    fun nrCaRxCountCapsEachCarriersMetricsInNsaAndSa() {
        val nr = org.json.JSONArray(nrCarriers)
        nr.getJSONObject(0).put("rx", 4)
        nr.getJSONObject(1).apply {
            put("rx", 2)
            put("rsrp_rx", org.json.JSONArray("[-95,-97,-80,-81]"))
            put("sinr_rx", org.json.JSONArray("[10,11,20,21]"))
            put("csinr_rx", org.json.JSONArray("[12,13,22,23]"))
        }
        for (sys in listOf(nsaSys, frame(NasMsg.SYS_INFO, tlv(0x4A, 2, 2)))) {
            val snap = build(mapOf(NetworkReads.SYS to sys), stats(nr = nr.toString()))
            val groups = snap.antennas.filter { it.rat == Rat.NR }
            assertEquals(listOf(0, 1, 2, 3), groups[0].chains.map { it.chain })
            assertEquals(listOf(0, 1), groups[1].chains.map { it.chain })
            assertEquals(listOf(-95.0, -97.0), groups[1].chains.map { it.rsrp })
            assertEquals(listOf(10.0, 11.0), groups[1].chains.map { it.sinr })
            assertEquals(listOf(12.0, 13.0), groups[1].chains.map { it.csiSinr })
        }
    }

    @Test
    fun updatedRxCountImmediatelyCapsOldChainMeasurements() {
        val nr = org.json.JSONArray(nrCarriers)
        for (rx in listOf(4, 2, 1, 4)) {
            nr.getJSONObject(0).put("rx", rx)
            val snap = build(mapOf(NetworkReads.SYS to nsaSys), stats(nr = nr.toString()))
            assertEquals((0 until rx).toList(), snap.antennas[0].chains.map { it.chain })
        }
    }

    @Test
    fun idleNrRx2AndRx3NeedTheirOwnSinr() {
        // Idle n28 (no RX count from PDSCH): RX 2 and 3 with an RSRP but neither SINR are left out.
        val nr = org.json.JSONArray(nrCarriers)
        nr.getJSONObject(1).apply {
            put("rsrp_rx", org.json.JSONArray("[-95,-97,-101,-103]"))
            put("sinr_rx", org.json.JSONArray("[10,11,null,null]"))
            put("csinr_rx", org.json.JSONArray("[12,13,null,null]"))
        }
        val snap = build(mapOf(NetworkReads.SYS to frame(NasMsg.SYS_INFO, tlv(0x4A, 2, 2))), stats(nr = nr.toString()))
        assertEquals(listOf(0, 1), snap.antennas[1].chains.map { it.chain })
        // With an SS-SINR or a CSI-SINR of its own the chain stays.
        nr.getJSONObject(1).put("csinr_rx", org.json.JSONArray("[12,13,null,7]"))
        val withSinr = build(mapOf(NetworkReads.SYS to frame(NasMsg.SYS_INFO, tlv(0x4A, 2, 2))), stats(nr = nr.toString()))
        assertEquals(listOf(0, 1, 3), withSinr.antennas[1].chains.map { it.chain })
        // A known receiver count decides alone (4 in traffic: RX 2 without SINR stays).
        nr.getJSONObject(1).put("rx", 4)
        val inTraffic = build(mapOf(NetworkReads.SYS to frame(NasMsg.SYS_INFO, tlv(0x4A, 2, 2))), stats(nr = nr.toString()))
        assertEquals(listOf(0, 1, 2, 3), inTraffic.antennas[1].chains.map { it.chain })
    }

    @Test
    fun lteCaRxCountCapsRsrpRsrqAndSinr() {
        val lte = org.json.JSONArray(lteCarriers)
        lte.getJSONObject(0).put("rx", 2)
        val snap = build(mapOf(NetworkReads.SYS to frame(NasMsg.SYS_INFO, lteInService)), stats(lte.toString()))
        assertEquals(listOf(0, 1), snap.antennas[0].chains.map { it.chain })
        assertEquals(listOf(-10.0, -12.0), snap.antennas[0].chains.map { it.rsrq })
        assertEquals(listOf(15.0, 12.5), snap.antennas[0].chains.map { it.sinr })
    }
}
