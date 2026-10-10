package dev.qcom.bandmenu.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays every successful frame from Codex's on-device captures
 * (test/telephony-page/<name>.jsonl, raw tx/rx hex) through the Kotlin
 * decoders and compares the fields against Codex's independent Python
 * decoder output (<name>-decoded.json). test/ is not in git (the captures
 * hold real cell identities), so this skips where it's absent, such as CI.
 */
class NasCaptureReplayTest {

    private val dir = listOf("../../test/telephony-page", "../test/telephony-page", "test/telephony-page")
        .map(::File).firstOrNull { it.isDirectory }

    private data class Rec(val raw: JSONObject, val decoded: JSONObject)

    private fun captures(): List<Pair<String, List<Rec>>> {
        assumeTrue("captures not available", dir != null)
        return dir!!.listFiles { f -> f.name.endsWith(".jsonl") }!!.sorted().mapNotNull { jsonl ->
            val decodedFile = File(dir, jsonl.nameWithoutExtension + "-decoded.json").takeIf { it.exists() }
                ?: File(dir, "lte-ca-decoded.json").takeIf { jsonl.name == "lte-ca-raw.jsonl" }
                ?: return@mapNotNull null
            val raw = jsonl.readLines().filter { it.isNotBlank() }.map { JSONObject(it.removePrefix("﻿")) }
            val decoded = JSONArray(decodedFile.readText())
            assertEquals(jsonl.name, raw.size, decoded.length())
            jsonl.name to raw.indices.map { Rec(raw[it], decoded.getJSONObject(it)) }
        }
    }

    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null

    private fun radioIf(name: String) = when {
        name.endsWith("_LTE") -> NasRadioIf.LTE
        name.endsWith("_WCDMA") -> NasRadioIf.UMTS
        name.endsWith("_GSM") -> NasRadioIf.GSM
        else -> NasRadioIf.NR5G
    }

    @Test
    fun everySuccessfulCapturedFrameDecodesLikeTheReferenceDecoder() {
        var checked = 0
        for ((file, pairs) in captures()) {
            for ((raw, dec) in pairs) {
                val rx = raw.optString("rx_hex", "")
                if (rx.isEmpty()) continue
                val frame = QmiFrame.parseHex(rx)
                assertEquals("$file ${raw.getString("name")} result", dec.getInt("result"), frame.result)
                if (!frame.ok) continue
                val name = raw.getString("name")
                val ctx = "$file#${raw.optInt("sample")} $name"
                when {
                    name == "GET_SYS_INFO" -> checkSysInfo(ctx, decodeSysInfo(frame), dec)
                    name == "GET_SIG_INFO" -> checkSigInfo(ctx, decodeSigInfo(frame), dec)
                    name == "GET_CELL_LOCATION_INFO" -> checkCellLocation(ctx, decodeCellLocation(frame), dec)
                    name == "GET_RF_BAND_INFO" -> checkRfBands(ctx, decodeRfBandInfo(frame), dec)
                    name == "GET_ARFCN_LIST" -> checkArfcnList(ctx, decodeArfcnList(frame), dec)
                    name == "GET_LTE_CPHY_CA_INFO" -> checkLteCa(ctx, decodeLteCaInfo(frame), dec)
                    name.startsWith("GET_TX_RX_INFO_") -> checkTxRx(ctx, decodeTxRxInfo(frame, radioIf(name)), dec)
                    name == "GET_HOME_NETWORK" -> {
                        val h = decodeHomeNetwork(frame)!!; val e = dec.getJSONObject("home_network")
                        assertEquals(ctx, e.getInt("mcc"), h.mcc); assertEquals(ctx, e.getInt("mnc"), h.mnc)
                    }
                    name.startsWith("GET_PLMN_NAME_") -> {
                        val n = decodePlmnName(frame)!!; val e = dec.getJSONObject("requested_plmn_name")
                        assertEquals(ctx, e.getString("long_ascii_preview").trim(), n.longName)
                    }
                    else -> continue
                }
                checked++
            }
        }
        assertTrue("decoded $checked frames", checked > 300)
    }

    private fun checkSysInfo(ctx: String, s: SysInfo, d: JSONObject) {
        d.optJSONObject("nr_system")?.let { e ->
            assertEquals("$ctx nr pci", e.optIntOrNull("pci"), s.nrPci)
            assertEquals("$ctx nr arfcn", e.optLongOrNull("arfcn"), s.nrArfcn)
            assertEquals("$ctx nr tac", e.optIntOrNull("tac"), s.nrTac)
            assertEquals("$ctx nr nci", e.optLongOrNull("nci"), s.nrNci)
            assertEquals("$ctx nr scs", e.optIntOrNull("subcarrier_spacing_khz"), s.nrScsKhz)
        }
        d.optJSONObject("service_status")?.let { e ->
            for ((key, rat) in listOf("GSM" to Rat.GSM, "WCDMA" to Rat.WCDMA, "LTE" to Rat.LTE, "NR" to Rat.NR)) {
                assertEquals("$ctx status $key", e.optJSONObject(key)?.getInt("status"), s.status[rat]?.status)
            }
        }
        d.optJSONObject("lte_system")?.let { e ->
            val c = s.common[Rat.LTE]!!
            assertEquals("$ctx lte cid", e.optLongOrNull("cell_id"), c.cellId)
            assertEquals("$ctx lte tac", e.optIntOrNull("tac"), c.tac)
            if (!e.isNull("mcc")) assertEquals("$ctx lte plmn", "${e.getString("mcc")}-${e.getString("mnc")}", c.plmn.toString())
        }
        d.optJSONObject("primary_plmn")?.let { e ->
            assertEquals("$ctx primary plmn", "${e.getString("mcc")}-${e.getString("mnc")}", s.primaryPlmn.toString())
        }
    }

    private fun checkSigInfo(ctx: String, s: SigInfo, d: JSONObject) {
        d.optJSONObject("lte_signal")?.let { e ->
            assertEquals("$ctx lte rsrp", e.getInt("rsrp_dbm"), s.lteRsrp)
            assertEquals("$ctx lte rsrq", e.getInt("rsrq_db"), s.lteRsrq)
            assertEquals("$ctx lte snr", e.getDouble("snr_db"), s.lteSnr!!, 1e-9)
        }
        d.optJSONObject("nr_signal")?.let { e ->
            assertEquals("$ctx nr rsrp", e.optIntOrNull("rsrp_dbm"), s.nrRsrp)
            assertEquals("$ctx nr rsrq", e.optIntOrNull("rsrq_db"), s.nrRsrq)
            assertEquals("$ctx nr snr", e.optDoubleOrNull("snr_db"), s.nrSnr)
        }
        d.optJSONObject("gsm_signal")?.let { assertEquals("$ctx gsm rssi", it.getInt("rssi_dbm"), s.gsmRssi) }
        d.optJSONObject("wcdma_signal")?.let { e ->
            e.optIntOrNull("rscp_raw")?.let { assertEquals("$ctx wcdma rscp raw", it, s.wcdmaRscpRaw) }
            e.optIntOrNull("ecio_raw")?.let { assertEquals("$ctx wcdma ecio raw", it, s.wcdmaEcioRaw) }
        }
    }

    private fun checkCellLocation(ctx: String, c: CellLocation, d: JSONObject) {
        d.optJSONObject("lte_serving")?.let { e ->
            val l = c.lte!!
            assertEquals("$ctx lte pci", e.getInt("pci"), l.pci)
            assertEquals("$ctx lte earfcn", e.getLong("earfcn"), l.earfcn)
            assertEquals("$ctx lte eci", e.getLong("eci"), l.eci)
            assertEquals("$ctx lte tac", e.getInt("tac"), l.tac)
            val m = e.getJSONArray("intrafrequency_measurements")
            assertEquals("$ctx lte intra count", m.length(), l.intraFreq.size)
            for (i in 0 until m.length()) {
                assertEquals("$ctx intra pci", m.getJSONObject(i).getInt("pci"), l.intraFreq[i].pci)
                assertEquals("$ctx intra rsrp", m.getJSONObject(i).getDouble("rsrp_dbm"), l.intraFreq[i].rsrp, 1e-9)
            }
        } ?: assertNull("$ctx unexpected lte", c.lte)
        d.optJSONArray("interfrequency_measurements")?.let { g ->
            assertEquals("$ctx inter groups", g.length(), c.lteInterFreq.size)
            for (i in 0 until g.length()) assertEquals("$ctx inter earfcn", g.getJSONObject(i).getLong("earfcn"), c.lteInterFreq[i].earfcn)
        }
        d.optJSONObject("nr_serving")?.let { e ->
            val n = c.nr!!
            assertEquals("$ctx nr pci", e.getInt("pci"), n.pci)
            assertEquals("$ctx nr nci", e.getLong("nci"), n.nci)
            assertEquals("$ctx nr tac", e.getInt("tac"), n.tac)
            assertEquals("$ctx nr rsrp", e.getDouble("rsrp_dbm"), n.rsrp, 1e-9)
            assertEquals("$ctx nr snr", e.getDouble("snr_db"), n.snr, 1e-9)
        }
        d.optLongOrNull("nr_arfcn")?.let { assertEquals("$ctx nr arfcn", it, c.nrArfcn) }
        d.optJSONObject("wcdma_serving")?.let { e ->
            val w = c.wcdma!!
            assertEquals("$ctx wcdma psc", e.getInt("psc"), w.psc)
            assertEquals("$ctx wcdma uarfcn", e.getInt("uarfcn"), w.uarfcn)
            assertEquals("$ctx wcdma cid", e.getLong("cell_id"), w.cellId)
            assertEquals("$ctx wcdma rscp", e.getInt("rscp_dbm"), w.rscp)
            assertEquals("$ctx wcdma ecio", e.getInt("ecio_db"), w.ecio)
            assertEquals("$ctx wcdma neighbors", d.getJSONArray("wcdma_neighbors").length(), w.neighbors.size)
        }
        d.optJSONObject("gsm_serving")?.let { e ->
            val g = c.gsm!!
            assertEquals("$ctx gsm bsic", e.optIntOrNull("bsic"), g.cell.bsic)
            assertEquals("$ctx gsm arfcn", e.getInt("arfcn"), g.cell.arfcn)
            assertEquals("$ctx gsm cid", e.optLongOrNull("cell_id"), g.cell.cellId)
            assertEquals("$ctx gsm rxlev", e.getInt("rxlev_raw"), g.cell.rxlev)
            assertEquals("$ctx gsm ta", e.optLongOrNull("timing_advance_bit_periods"), g.timingAdvance)
            val n = d.getJSONArray("gsm_neighbors")
            assertEquals("$ctx gsm neighbors", n.length(), g.neighbors.size)
            for (i in 0 until n.length()) {
                assertEquals("$ctx gsm n rxlev", n.getJSONObject(i).getInt("rxlev_raw"), g.neighbors[i].rxlev)
                assertEquals("$ctx gsm n bsic", n.getJSONObject(i).optIntOrNull("bsic"), g.neighbors[i].bsic)
            }
        }
        if (d.has("timing_advance_us")) assertEquals("$ctx ta", d.optIntOrNull("timing_advance_us"), c.lteTimingAdvanceUs)
    }

    private fun checkRfBands(ctx: String, b: List<RfBand>, d: JSONObject) {
        val e = d.getJSONArray("rf_bands")
        assertEquals("$ctx rf count", e.length(), b.size)
        for (i in 0 until e.length()) {
            assertEquals("$ctx rf band", e.getJSONObject(i).getInt("band_enum"), b[i].bandEnum)
            assertEquals("$ctx rf channel", e.getJSONObject(i).getLong("channel"), b[i].channel)
        }
        d.optJSONArray("rf_bandwidths")?.let { w ->
            for (i in 0 until w.length()) {
                val rat = w.getJSONObject(i).getInt("rat")
                assertEquals("$ctx rf width", w.getJSONObject(i).optDoubleOrNull("bandwidth_mhz"), b.first { it.radioIf == rat }.bandwidthMhz)
            }
        }
    }

    private fun checkArfcnList(ctx: String, a: ArfcnList, d: JSONObject) {
        fun longs(k: String) = d.optJSONArray(k)?.let { arr -> List(arr.length()) { arr.getLong(it) } }
        assertEquals("$ctx nr serving", longs("nr_serving_arfcns"), a.nrServing)
        assertEquals("$ctx nr neighbor", longs("nr_neighbor_arfcns"), a.nrNeighbor)
        assertEquals("$ctx lte rrc", d.optIntOrNull("lte_rrc_state_enum"), a.lteRrc)
        assertEquals("$ctx nr rrc", d.optIntOrNull("nr_rrc_state_enum"), a.nrRrc)
        assertEquals("$ctx wcdma rrc", d.optIntOrNull("wcdma_rrc_state_enum"), a.wcdmaRrc)
        assertEquals("$ctx wcdma uarfcn", d.optIntOrNull("wcdma_serving_uarfcn"), a.wcdmaServingUarfcn)
        d.optJSONArray("lte_serving_frequencies")?.let { assertEquals("$ctx lte serving", it.length(), a.lteServing!!.size) }
    }

    private fun checkLteCa(ctx: String, c: LteCaInfo, d: JSONObject) {
        d.optJSONObject("primary")?.let { e ->
            assertEquals("$ctx pcell pci", e.getInt("pci"), c.pcell!!.pci)
            assertEquals("$ctx pcell earfcn", e.getLong("earfcn"), c.pcell.earfcn)
            assertEquals("$ctx pcell bw", e.optDoubleOrNull("bandwidth_mhz"), c.pcell.bandwidthMhz)
        }
        d.optJSONArray("secondaries")?.let { s ->
            assertEquals("$ctx scell count", s.length(), c.scells.size)
            for (i in 0 until s.length()) {
                val e = s.getJSONObject(i)
                assertEquals("$ctx scell pci", e.getInt("pci"), c.scells[i].pci)
                assertEquals("$ctx scell earfcn", e.getLong("earfcn"), c.scells[i].earfcn)
                assertEquals("$ctx scell state", e.getInt("state_raw"), c.scells[i].state!!.ordinal)
            }
        }
    }

    private fun checkTxRx(ctx: String, t: TxRxInfo, d: JSONObject) {
        val chains = d.getJSONArray("rx_chains")
        assertEquals("$ctx chains", chains.length(), t.chains.size)
        for (i in 0 until chains.length()) {
            val e = chains.getJSONObject(i)
            assertEquals("$ctx tuned", e.getBoolean("tuned"), t.chains[i].tuned)
            assertEquals("$ctx rx power", e.getDouble("rx_power_dbm"), t.chains[i].rxPower!!, 1e-9)
        }
        d.optJSONObject("tx")?.let { e ->
            assertEquals("$ctx traffic", e.getBoolean("in_traffic"), t.inTraffic)
            assertEquals("$ctx tx power", e.optDoubleOrNull("power_dbm"), t.txPower)
        }
    }

    @Test
    fun captureSpotChecksFromCodexGuide() {
        val all = captures().toMap()
        fun frames(file: String, name: String) = all.getValue(file)
            .filter { it.raw.getString("name") == name && it.raw.optString("rx_hex").isNotEmpty() }
            .map { QmiFrame.parseHex(it.raw.getString("rx_hex")) }.filter { it.ok }

        // SA: PCI 807 / ARFCN 153630 / n28 / TAC 5201020 / NCI 8957641705; secondary 529950 frequency only.
        val saSys = frames("sa-n28-primary-20261009-1.jsonl", "GET_SYS_INFO").map(::decodeSysInfo).first { it.nrPci != null }
        assertEquals(807, saSys.nrPci); assertEquals(153630L, saSys.nrArfcn)
        assertEquals(5201020, saSys.nrTac); assertEquals(8957641705L, saSys.nrNci)
        val saArfcn = frames("sa-n28-primary-20261009-1.jsonl", "GET_ARFCN_LIST").map(::decodeArfcnList).first { it.nrServing != null }
        assertEquals(listOf(153630L, 529950L), saArfcn.nrServing)

        // NSA n28 removal: [529950,153630] (repeat 3) -> [529950] (n28 off).
        fun nrServing(file: String) = frames(file, "GET_ARFCN_LIST").mapNotNull { decodeArfcnList(it).nrServing }.toSet()
        assertEquals(setOf(listOf(529950L, 153630L)), nrServing("nsa-qmi-20261009-3.jsonl"))
        assertEquals(setOf(listOf(529950L)), nrServing("nsa-n28-off-20261009-1.jsonl"))

        // WCDMA: PSC 83 / UARFCN 2987 / CID 137711403.
        val w = frames("wcdma-20261009-1.jsonl", "GET_CELL_LOCATION_INFO").map(::decodeCellLocation).first { it.wcdma != null }.wcdma!!
        assertEquals(83, w.psc); assertEquals(2987, w.uarfcn); assertEquals(137711403L, w.cellId)

        // GSM: BSIC 14 (NCC 1 / BCC 6) / ARFCN 122 / CID 44432; RxLev 49 -> [-62,-61].
        val g = frames("gsm-20261009-1.jsonl", "GET_CELL_LOCATION_INFO").map(::decodeCellLocation).first { it.gsm != null }.gsm!!.cell
        assertEquals(14, g.bsic); assertEquals(1, g.ncc); assertEquals(6, g.bcc)
        assertEquals(122, g.arfcn); assertEquals(44432L, g.cellId)
        val rx49 = GsmCell(null, null, null, 0, null, 49)
        assertEquals(-62..-61, rx49.rssiRange)
        assertNull(GsmCell(null, null, null, 0, null, 63).rssiRange)

        // LTE traffic: four LTE carriers totalling 60 MHz with activation states.
        val ca = frames("load-raw-20261009-1.jsonl", "GET_LTE_CPHY_CA_INFO").map(::decodeLteCaInfo).first { it.scells.size == 3 }
        val total = (listOf(ca.pcell!!) + ca.scells).sumOf { it.bandwidthMhz!! }
        assertEquals(60.0, total, 1e-9)
        assertNotNull(ca.scells.firstOrNull { it.state == ScellState.ACTIVATED })
    }
}
