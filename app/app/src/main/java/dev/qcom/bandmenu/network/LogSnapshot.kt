package dev.qcom.bandmenu.network

import org.json.JSONArray
import org.json.JSONObject

/** Explicit field names survive R8 and retain every logical field consumed by NetworkContent. */
object LogSnapshot {
    fun encode(s: NetworkSnapshot): JSONObject = obj(
        "sim" to s.sim, "takenAtElapsedMs" to s.takenAtElapsedMs, "roundMs" to s.roundMs,
        "mode" to s.mode.name, "modeLabel" to s.modeLabel, "plmn" to s.plmn, "roaming" to s.roaming,
        "rrc" to s.rrc, "cells" to s.cells, "carrierGroups" to s.carrierGroups, "endc" to s.endc,
        "neighbors" to s.neighbors, "antennas" to s.antennas, "failedReads" to s.failedReads)

    private fun value(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Double -> if (v.isFinite()) v else JSONObject.NULL
        is Float -> if (v.isFinite()) v else JSONObject.NULL
        is Enum<*> -> v.name
        is Collection<*> -> JSONArray(v.map(::value))
        is Plmn -> obj("mcc" to v.mcc, "mnc" to v.mnc)
        is Field -> obj("label" to v.label, "value" to v.value)
        is Metric -> obj("label" to v.label, "kind" to v.kind, "rat" to v.rat, "value" to v.value, "unit" to v.unit, "text" to v.text)
        is CellCard -> obj("title" to v.title, "rat" to v.rat, "fields" to v.fields, "metrics" to v.metrics, "nci" to v.nci)
        is Carrier -> obj("role" to v.role, "band" to v.band, "channel" to v.channel, "bandwidthMhz" to v.bandwidthMhz,
            "state" to v.state, "pci" to v.pci, "rsrp" to v.rsrp, "rsrq" to v.rsrq, "sinr" to v.sinr, "diag" to v.diag, "nr" to v.nr)
        is CarrierGroup -> obj("title" to v.title, "summary" to v.summary, "carriers" to v.carriers, "note" to v.note,
            "table" to v.table, "diag" to v.diag, "nrTable" to v.nrTable, "endc" to v.endc)
        is Neighbor -> obj("rat" to v.rat, "channelLabel" to v.channelLabel, "channel" to v.channel, "idLabel" to v.idLabel,
            "id" to v.id, "metrics" to v.metrics, "band" to v.band)
        is Band -> obj("rat" to v.rat, "number" to v.number, "label" to v.label)
        is AntennaGroup -> obj("rat" to v.rat, "chains" to v.chains, "inTraffic" to v.inTraffic, "txPower" to v.txPower)
        is RxChain -> obj("chain" to v.chain, "tuned" to v.tuned, "rxPower" to v.rxPower, "ecio" to v.ecio,
            "rscp" to v.rscp, "rsrp" to v.rsrp, "phaseDeg" to v.phaseDeg)
        is DiagCarrier -> obj("slot" to v.slot, "earfcn" to v.earfcn, "pci" to v.pci, "band" to v.band, "bandwidthMhz" to v.bandwidthMhz,
            "state" to v.state, "rsrp" to v.rsrp, "rsrq" to v.rsrq, "sinr" to v.sinr, "transportBlocks" to v.transportBlocks,
            "mbps" to v.mbps, "ulMbps" to v.ulMbps, "bler" to v.bler, "mcs" to v.mcs, "rbs" to v.rbs, "modulation" to v.modulation,
            "rank" to v.rank, "transmissionMode" to v.transmissionMode, "txAntennas" to v.txAntennas, "rxAntennas" to v.rxAntennas, "layers" to v.layers)
        is DiagNrCarrier -> obj("cc" to v.cc, "pcell" to v.pcell, "pci" to v.pci, "arfcn" to v.arfcn, "dlArfcn" to v.dlArfcn,
            "band" to v.band, "bandwidthMhz" to v.bandwidthMhz, "scsKhz" to v.scsKhz, "maxLayers" to v.maxLayers,
            "rsrp" to v.rsrp, "rsrq" to v.rsrq, "sinr" to v.sinr, "cqi" to v.cqi, "ri" to v.ri,
            "transportBlocks" to v.transportBlocks, "mbps" to v.mbps, "ulMbps" to v.ulMbps, "bler" to v.bler,
            "mcs" to v.mcs, "rbs" to v.rbs, "layers" to v.layers, "modulation" to v.modulation, "rxAntennas" to v.rxAntennas,
            "beamSinr" to v.beamSinr, "csiSinr" to v.csiSinr, "csiPorts" to v.csiPorts)
        else -> v
    }
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { o.put(it.first, value(it.second)) } }

    /** All leaves are selectable/exportable; explicit carrier aliases align UI and packet graphs. */
    fun updates(s: NetworkSnapshot): JSONArray {
        val out = JSONArray()
        fun walk(v: Any?, path: String, unit: String = "", label: String? = null) {
            when (v) {
                is JSONObject -> v.keys().asSequence().toList().sorted().forEach { k ->
                    val semantic = if (k == "value" && v.has("label"))
                        listOf(v.optString("rat"), v.optString("label")).filter { it.isNotEmpty() }.joinToString(" ") else null
                    walk(v.opt(k), "$path/$k", v.optString("unit", unit), semantic)
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), "$path/$i", unit)
                else -> out.put(obj("entity" to "ui:${path.substringBeforeLast('/')}", "identity" to s.mode.name,
                    "metric" to path.substringAfterLast('/'), "value" to v, "unit" to unit, "aggregation" to "last", "label" to label))
            }
        }
        walk(encode(s), "snapshot")
        val seen = HashSet<String>()
        for (group in s.carrierGroups) for ((index, c) in group.carriers.withIndex()) {
            val nr = c.nr
            val lte = c.diag
            val entity = when { nr != null -> "nr:${nr.cc}"; lte != null -> "lte:${lte.slot}";
                group.nrTable -> "ui:nr:$index"; else -> "lte:$index" }
            val identity = "${nr?.arfcn ?: lte?.earfcn ?: c.channel.substringBefore(' ')}:${nr?.pci ?: lte?.pci ?: c.pci ?: "unknown"}"
            fun metric(name: String, v: Any?, unit: String = "", aggregateEligible: Boolean? = null) {
                if (seen.add("$entity/$identity/$name")) out.put(obj("entity" to entity, "identity" to identity,
                    "metric" to name, "value" to v, "unit" to unit, "aggregation" to "last", "aggregateEligible" to aggregateEligible))
            }
            metric("rsrp", c.rsrp, "dBm", c.rsrp != null && c.rsrp == (nr?.rsrp ?: lte?.rsrp))
            metric("rsrq", c.rsrq, "dB", c.rsrq != null && c.rsrq == (nr?.rsrq ?: lte?.rsrq))
            metric("sinr", c.sinr, "dB", c.sinr != null && c.sinr == (nr?.sinr ?: lte?.sinr))
            metric("band", c.band)
            metric("bw", c.bandwidthMhz, "MHz")
            metric("state", c.state)
            if (nr != null || lte != null) {
                metric("mcs", nr?.mcs ?: lte?.mcs); metric("rbs", nr?.rbs ?: lte?.rbs)
                metric("layers", nr?.layers ?: lte?.layers); metric("bler", nr?.bler ?: lte?.bler, "%")
                metric("mod", nr?.modulation ?: lte?.modulation)
                metric("mbps_sched", nr?.mbps ?: lte?.mbps, "Mbps"); metric("ul_mbps", nr?.ulMbps ?: lte?.ulMbps, "Mbps")
                metric("rx", nr?.rxAntennas ?: lte?.rxAntennas)
            }
            if (nr != null) { metric("cqi", nr.cqi); metric("ri", nr.ri); metric("csinr", nr.csiSinr, "dB"); metric("csi_ports", nr.csiPorts) }
            if (lte != null) { metric("tx", lte.txAntennas); metric("tm", lte.transmissionMode); metric("rank", lte.rank) }
        }
        return out
    }
}
