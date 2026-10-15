package dev.qcom.bandmenu.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Builds full snapshots from Codex's captured rounds (skipped where test/ is absent). */
class SnapshotCaptureTest {

    private val dir = listOf("../../test/telephony-page", "../test/telephony-page")
        .map(::File).firstOrNull { it.isDirectory }

    private val keys = mapOf(
        "GET_SYS_INFO" to NetworkReads.SYS, "GET_SIG_INFO" to NetworkReads.SIG,
        "GET_CELL_LOCATION_INFO" to NetworkReads.LOC, "GET_RF_BAND_INFO" to NetworkReads.RF,
        "GET_ARFCN_LIST" to NetworkReads.ARFCN, "GET_LTE_CPHY_CA_INFO" to NetworkReads.CA,
        "GET_ERR_RATE" to NetworkReads.ERR, "GET_TX_RX_INFO_LTE" to NetworkReads.TXRX_LTE,
        "GET_TX_RX_INFO_NR" to NetworkReads.TXRX_NR, "GET_TX_RX_INFO_WCDMA" to NetworkReads.TXRX_WCDMA,
        "GET_TX_RX_INFO_GSM" to NetworkReads.TXRX_GSM
    )

    /** Rounds keyed by (sim, sample), only those that include SYS_INFO. */
    private fun rounds(file: String): List<Map<String, ReadOutcome>> {
        assumeTrue("captures not available", dir != null)
        val bySample = LinkedHashMap<Pair<Int, Int>, MutableMap<String, ReadOutcome>>()
        File(dir, file).readLines().filter { it.isNotBlank() }.map { JSONObject(it.removePrefix("﻿")) }.forEach { e ->
            val key = keys[e.getString("name")] ?: return@forEach
            val rx = e.optString("rx_hex", "")
            val outcome = if (rx.isEmpty()) ReadOutcome.Failed("timeout") else ReadOutcome.Ok(QmiFrame.parseHex(rx))
            bySample.getOrPut(e.getInt("sim") to e.getInt("sample")) { LinkedHashMap() }[key] = outcome
        }
        return bySample.values.filter { NetworkReads.SYS in it }
    }

    private fun snapshot(r: Map<String, ReadOutcome>) = SnapshotBuilder(r).build(2, 0, 0)

    private fun dump(s: NetworkSnapshot) = buildString {
        appendLine("== ${s.modeLabel} plmn=${s.plmn} roaming=${s.roaming} rrc=${s.rrc} failed=${s.failedReads}")
        s.cells.forEach { c ->
            appendLine("  [${c.title}] " + c.fields.joinToString { "${it.label}=${it.value}" })
            appendLine("     " + c.metrics.joinToString { "${it.label}=${it.text ?: it.value}" })
        }
        s.carrierGroups.forEach { g -> appendLine("  {${g.title}} ${g.summary} :: " + g.carriers.joinToString { "${it.role} ${it.band} ${it.channel} ${it.bandwidthMhz} ${it.state} pci=${it.pci}" }) }
        appendLine("  neighbours=${s.neighbors.size} " + s.neighbors.take(3).joinToString { "${it.idLabel}${it.id}@${it.channel}" })
        s.antennas.forEach { a -> appendLine("  ant ${a.rat}: " + a.chains.joinToString { "rx${it.chain} ${it.tuned} ${it.rxPower} rsrp=${it.rsrp} rscp=${it.rscp}" } + " tx=${a.txPower} traffic=${a.inTraffic}") }
    }

    @Test
    fun modesAndKeyFieldsPerCapture() {
        val sa = rounds("sa-n28-primary-20261009-1.jsonl").map(::snapshot)
        println(dump(sa.first()))
        assertTrue(sa.all { it.mode == ServiceMode.NR_SA })
        val saCell = sa.first().cells.single()
        assertEquals("807", saCell.fields.first { it.label == "PCI" }.value)
        assertEquals("n28", saCell.fields.first { it.label == "Band" }.value)
        assertEquals("8957641705", saCell.fields.first { it.label == "NCI" }.value)
        val saNr = sa.first().carrierGroups.single()
        assertEquals("529950", saNr.carriers.single().channel.substringBefore(" "))

        val nsa = rounds("nsa-qmi-20261009-3.jsonl").map(::snapshot)
        println(dump(nsa.first()))
        assertTrue(nsa.all { it.mode == ServiceMode.NR_NSA })
        assertEquals(listOf("LTE MCG", "NR SCG"), nsa.first().cells.map { it.title })
        // NSA reads carry no NR TAC/NCI.
        assertTrue(nsa.first().cells[1].fields.none { it.label == "NCI" || it.label == "TAC" })

        // ENDC table: the MCG is the LTE table's PCell, the SCG the NR serving cell.
        nsa.forEach { s ->
            val endc = s.endc!!
            println("  {${endc.title}} " + endc.carriers.joinToString { "${it.role} ${it.band} ${it.channel} pci=${it.pci} rsrp=${it.rsrp}" })
            assertEquals("MCG", endc.carriers[0].role)
            assertEquals(s.carrierGroups.first().carriers.first().channel, endc.carriers[0].channel)
            assertEquals("SCG", endc.carriers[1].role)
            assertTrue(endc.carriers[1].band!!.startsWith("n"))
            assertEquals(endc.carriers.drop(2).mapIndexed { i, _ -> "S${i + 1}" }, endc.carriers.drop(2).map { it.role })
        }
        assertTrue(sa.all { it.endc == null })

        val off = rounds("nsa-n28-off-20261009-1.jsonl").map(::snapshot)
        println(dump(off.first()))
        // NSA without an NR secondary: the NR aggregation card stays, empty.
        assertTrue(off.all { s -> s.carrierGroups.single { it.title == "NR aggregation" }.carriers.isEmpty() })

        val w = rounds("wcdma-20261009-1.jsonl").map(::snapshot)
        println(dump(w.first()))
        assertTrue(w.all { it.mode == ServiceMode.WCDMA })
        val wFields = w.first().cells.single().fields
        assertEquals("83", wFields.first { it.label == "PSC" }.value)
        assertEquals(listOf("Band", "UARFCN", "Frequency", "PSC", "Cell ID"), wFields.take(5).map { it.label })
        assertEquals("937.4 MHz", wFields.first { it.label == "Frequency" }.value)
        assertEquals("137711403 (2101, 20267)", wFields.first { it.label == "Cell ID" }.value)
        assertTrue(wFields.none { it.label == "Bandwidth" })
        // WCDMA RSCP must come out negative (SIG_INFO's raw sign anomaly is not used).
        assertTrue(w.first().cells.single().metrics.first { it.label == "RSCP" }.value!! < 0)

        val g = rounds("gsm-20261009-1.jsonl").map(::snapshot)
        println(dump(g.first()))
        assertTrue(g.all { it.mode == ServiceMode.GSM })
        assertTrue(g.first().cells.single().fields.first { it.label == "BSIC" }.value.startsWith("14 (NCC 1, BCC 6)"))
        assertTrue("no TX power without traffic", g.all { s -> s.antennas.all { it.inTraffic == true || it.txPower == null } })

        // Mode transitions never carry topology over: every snapshot is
        // built only from its own round.
        val nsaThenWcdma = snapshot(rounds("wcdma-20261009-1.jsonl").first())
        assertTrue(nsaThenWcdma.carrierGroups.isEmpty())
    }

    @Test
    fun nsaNeedsALiveNrLeg() {
        // With the ARFCN list, NSA follows its NR fields; without it, SYS_INFO's
        // NR PCI counts only with a valid NR RSRP (a leftover PCI doesn't).
        val nsa = rounds("nsa-qmi-20261009-3.jsonl").first()
        assertEquals(ServiceMode.NR_NSA, snapshot(nsa).mode)
        val noArfcn = nsa - NetworkReads.ARFCN
        assertEquals(ServiceMode.NR_NSA, snapshot(noArfcn).mode)
        // SIG_INFO without a valid NR RSRP (from the WCDMA capture: no NR values).
        val noNrSig = rounds("wcdma-20261009-1.jsonl").first { NetworkReads.SIG in it }.getValue(NetworkReads.SIG)
        assertEquals(ServiceMode.LTE, snapshot(noArfcn + (NetworkReads.SIG to noNrSig)).mode)
    }

    @Test
    fun lteTrafficCaptureShowsCarrierAggregation() {
        // load capture has SYS_INFO in only some samples; merge it into each round.
        assumeTrue(dir != null)
        val file = "load-raw-20261009-1.jsonl"
        val all = File(dir, file).readLines().filter { it.isNotBlank() }.map { JSONObject(it.removePrefix("﻿")) }
        val sysHex = all.first { it.getString("name") == "GET_SYS_INFO" && it.has("rx_hex") }.getString("rx_hex")
        val bySample = all.groupBy { it.getInt("sim") to it.getInt("sample") }.values.map { entries ->
            val m = LinkedHashMap<String, ReadOutcome>()
            m[NetworkReads.SYS] = ReadOutcome.Ok(QmiFrame.parseHex(sysHex))
            entries.forEach { e -> keys[e.getString("name")]?.let { k -> e.optString("rx_hex").takeIf { it.isNotEmpty() }?.let { m[k] = ReadOutcome.Ok(QmiFrame.parseHex(it)) } } }
            m
        }
        val snaps = bySample.map(::snapshot)
        val withCa = snaps.first { s -> s.carrierGroups.any { it.carriers.size == 4 } }
        println(dump(withCa))
        assertEquals("LTE 4xCA", withCa.modeLabel)
        assertEquals(60.0, withCa.carrierGroups.first().carriers.sumOf { it.bandwidthMhz!! }, 1e-9)
    }
}
