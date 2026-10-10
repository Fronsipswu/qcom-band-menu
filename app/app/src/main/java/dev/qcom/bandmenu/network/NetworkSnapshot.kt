package dev.qcom.bandmenu.network

import java.util.Locale

/*
 * Turns one monitoring round (decoded NAS reads) into what the Network page
 * shows. Pure and device-free, so it's unit-tested with captured frames.
 * Rules carried over from Codex's guide: family-specific identifiers, no
 * neighbour is ever promoted to a serving carrier, an NR secondary
 * frequency stays "frequency only", and nothing from a previous mode
 * survives a mode change (each snapshot is built from this round only).
 */

enum class ServiceMode(val label: String, val generation: String) {
    NO_SERVICE("No service", ""),
    LIMITED("Limited service", ""),
    GSM("GSM", "2G"),
    WCDMA("WCDMA", "3G"),
    LTE("LTE", "4G"),
    NR_NSA("NR NSA", "5G"),
    NR_SA("NR SA", "5G")
}

enum class MetricKind { RSRP, RSRQ, SINR, RSSI, RSCP, ECIO, TX_POWER }

/** One signal value; [value] null means unavailable (never shown as 0). */
data class Metric(
    val label: String,
    val kind: MetricKind,
    val rat: Rat,
    val value: Double?,
    val unit: String,
    /** Shown instead of the number when the value is an interval (GSM RxLev). */
    val text: String? = null
)

data class Field(val label: String, val value: String)

data class CellCard(val title: String, val rat: Rat, val fields: List<Field>, val metrics: List<Metric>)

data class Carrier(
    val role: String,
    val band: String?,
    val channel: String,
    val bandwidthMhz: Double?,
    /** "Active", "Configured" etc.; null when the modem doesn't report it. */
    val state: String?,
    val pci: Int?,
    /** L1 measurement of this carrier (CELL_LOCATION, matched by channel + PCI). */
    val rsrp: Double? = null,
    val rsrq: Double? = null,
    /** PCC only (SIG_INFO); QMI has no per-SCell SINR. */
    val sinr: Double? = null
)

data class CarrierGroup(
    val title: String,
    val summary: String,
    val carriers: List<Carrier>,
    val note: String? = null,
    /** Render as a PCC/SCC column table (LTE CA). */
    val table: Boolean = false
)

data class Neighbor(
    val rat: Rat, val channelLabel: String, val channel: Long, val idLabel: String, val id: String, val metrics: List<Metric>,
    /** Band derived from the channel number (neighbour reports carry none); null when ambiguous. */
    val band: Band? = null
)

data class AntennaGroup(val rat: Rat, val chains: List<RxChain>, val inTraffic: Boolean?, val txPower: Double?)

data class NetworkSnapshot(
    val sim: Int,
    val takenAtElapsedMs: Long,
    val roundMs: Int,
    val mode: ServiceMode,
    /** Text for the mode pill, e.g. "LTE 2xCA". */
    val modeLabel: String,
    val plmn: Plmn?,
    val roaming: Boolean?,
    val rrc: List<Field>,
    val cells: List<CellCard>,
    val carrierGroups: List<CarrierGroup>,
    val neighbors: List<Neighbor>,
    val antennas: List<AntennaGroup>,
    /** Reads that failed this round (shown in debug and as a footnote). */
    val failedReads: List<String>
)

/** Outcome of one read in a round. */
sealed class ReadOutcome {
    data class Ok(val frame: QmiFrame) : ReadOutcome()
    /** The daemon couldn't get a frame: timeout, send_failed, bad_frame, not_allowed. */
    data class Failed(val status: String) : ReadOutcome()
    data class Malformed(val reason: String) : ReadOutcome()
}

/** The read set for one round, as daemon "reads" entries. */
object NetworkReads {
    const val SYS = "004d"
    const val SIG = "004f"
    const val LOC = "0043"
    const val RF = "0031"
    const val ARFCN = "00d2"
    const val CA = "00ac"
    const val ERR = "0052"
    const val TXRX_LTE = "005a:01010008"
    const val TXRX_NR = "005a:0101000c"
    const val TXRX_WCDMA = "005a:01010005"
    const val TXRX_GSM = "005a:01010004"
    const val HOME = "0025"

    val ROUND = listOf(SYS, SIG, LOC, RF, ARFCN, CA, ERR, TXRX_LTE, TXRX_NR, TXRX_WCDMA, TXRX_GSM)
}

private fun fmt(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

private fun mhz(v: Double) = "${fmt(v)} MHz"

class SnapshotBuilder(
    private val reads: Map<String, ReadOutcome>,
    /** NR bands the modem supports, to resolve overlapping NR-ARFCN ranges. */
    private val nrHardwareBands: Set<Int> = emptySet()
) {

    private inline fun <T> decode(key: String, block: (QmiFrame) -> T): T? {
        val r = reads[key] as? ReadOutcome.Ok ?: return null
        if (!r.frame.ok) return null
        return try { block(r.frame) } catch (e: QmiFormatException) { malformed += key; null }
    }

    private val malformed = mutableListOf<String>()

    private val sys = decode(NetworkReads.SYS, ::decodeSysInfo)
    private val sig = decode(NetworkReads.SIG, ::decodeSigInfo)
    private val loc = decode(NetworkReads.LOC, ::decodeCellLocation)
    private val rf = decode(NetworkReads.RF, ::decodeRfBandInfo) ?: emptyList()
    private val arfcn = decode(NetworkReads.ARFCN, ::decodeArfcnList)
    private val ca = decode(NetworkReads.CA, ::decodeLteCaInfo)
    private val txLte = decode(NetworkReads.TXRX_LTE) { decodeTxRxInfo(it, NasRadioIf.LTE) }
    private val txNr = decode(NetworkReads.TXRX_NR) { decodeTxRxInfo(it, NasRadioIf.NR5G) }
    private val txWcdma = decode(NetworkReads.TXRX_WCDMA) { decodeTxRxInfo(it, NasRadioIf.UMTS) }
    private val txGsm = decode(NetworkReads.TXRX_GSM) { decodeTxRxInfo(it, NasRadioIf.GSM) }
    private val errRate = decode(NetworkReads.ERR) { f ->
        Pair(f.tlv(0x12)?.let { TlvReader(it).u8() }?.takeIf { it != 0xFF },
             f.tlv(0x13)?.let { TlvReader(it).u8() }?.takeIf { it != 0xFF })
    }

    private fun rfFor(radioIf: Int) = rf.firstOrNull { it.radioIf == radioIf }

    val mode: ServiceMode = run {
        val s = sys ?: return@run ServiceMode.NO_SERVICE
        val nrLeg = s.nrPci != null || !arfcn?.nrServing.isNullOrEmpty() || arfcn?.nrRrc != null
        // Stay in the NSA layout while the LTE cell offers EN-DC, so the NR SCG
        // card doesn't pop in and out whenever the NR leg drops briefly (the
        // same signal Android uses to keep the 5G icon).
        val endcOffered = s.endcAvailable == true && s.dcnrRestricted != true
        when {
            s.inService(Rat.LTE) && (nrLeg || endcOffered) -> ServiceMode.NR_NSA
            s.inService(Rat.LTE) -> ServiceMode.LTE
            s.inService(Rat.NR) -> ServiceMode.NR_SA
            s.inService(Rat.WCDMA) -> ServiceMode.WCDMA
            s.inService(Rat.GSM) -> ServiceMode.GSM
            s.status.values.any { it.status == NasServiceStatus.LIMITED || it.status == NasServiceStatus.LIMITED_REGIONAL } -> ServiceMode.LIMITED
            else -> ServiceMode.NO_SERVICE
        }
    }

    private val servingRat: Rat? = when (mode) {
        ServiceMode.LTE, ServiceMode.NR_NSA -> Rat.LTE
        ServiceMode.NR_SA -> Rat.NR
        ServiceMode.WCDMA -> Rat.WCDMA
        ServiceMode.GSM -> Rat.GSM
        else -> null
    }

    fun build(sim: Int, takenAtElapsedMs: Long, roundMs: Int): NetworkSnapshot {
        val common = servingRat?.let { sys?.common?.get(it) }
        val cells = buildList {
            when (mode) {
                ServiceMode.LTE -> lteCell()?.let(::add)
                ServiceMode.NR_NSA -> { lteCell()?.let(::add); nrCell(sa = false)?.let(::add) }
                ServiceMode.NR_SA -> nrCell(sa = true)?.let(::add)
                ServiceMode.WCDMA -> wcdmaCell()?.let(::add)
                ServiceMode.GSM -> gsmCell()?.let(::add)
                else -> {}
            }
        }
        val failed = reads.filter { (_, o) -> o !is ReadOutcome.Ok }.keys + malformed
        return NetworkSnapshot(
            sim = sim,
            takenAtElapsedMs = takenAtElapsedMs,
            roundMs = roundMs,
            mode = mode,
            modeLabel = modeLabel(),
            plmn = sys?.primaryPlmn ?: common?.plmn,
            roaming = common?.roaming,
            rrc = rrcFields(),
            cells = cells,
            carrierGroups = listOfNotNull(lteCarriers(), nrCarriers()),
            neighbors = neighbors(),
            antennas = antennas(),
            failedReads = failed.toList()
        )
    }

    /** LTE with SCells configured shows the carrier count: "LTE 2xCA". */
    private fun modeLabel(): String {
        if (mode != ServiceMode.LTE) return mode.label
        val c = ca ?: return mode.label
        if (c.pcell == null) return mode.label
        val carriers = 1 + c.scells.count { it.state == ScellState.CONFIGURED || it.state == ScellState.ACTIVATED }
        return if (carriers >= 2) "${mode.label} ${carriers}xCA" else mode.label
    }

    private fun rrcFields() = buildList {
        val a = arfcn ?: return@buildList
        when (mode) {
            ServiceMode.LTE, ServiceMode.NR_NSA -> {
                a.lteRrc?.let { add(Field("LTE RRC", NasRrc.lte(it))) }
                if (mode == ServiceMode.NR_NSA) a.nrRrc?.let { add(Field("NR RRC", NasRrc.nr(it))) }
            }
            ServiceMode.NR_SA -> a.nrRrc?.let { add(Field("NR RRC", NasRrc.nr(it))) }
            ServiceMode.WCDMA -> a.wcdmaRrc?.let { add(Field("RRC", NasRrc.wcdma(it))) }
            else -> {}
        }
    }

    // ── Cells ────────────────────────────────────────────────────────────

    private fun lteCell(): CellCard? {
        val l = loc?.lte
        val pcell = ca?.pcell
        val pci = l?.pci ?: pcell?.pci
        val earfcn = l?.earfcn ?: pcell?.earfcn
        val band = rfFor(NasRadioIf.LTE)
        val fields = buildList {
            (band?.bandEnum ?: pcell?.bandEnum)?.let { add(Field("Band", NasBands.label(it))) }
            earfcn?.let { add(Field("EARFCN", it.toString())) }
            (pcell?.bandwidthMhz ?: band?.bandwidthMhz)?.let { add(Field("Bandwidth", mhz(it))) }
            pci?.let { add(Field("PCI", it.toString())) }
            earfcn?.let { lteFrequenciesForEarfcn(it.toInt()) }?.let { f ->
                add(Field("DL / UL", "${fmt(f.downlinkMhz)} / ${f.uplinkMhz?.let(::fmt) ?: "—"} MHz"))
            }
            (l?.tac ?: sys?.common?.get(Rat.LTE)?.tac)?.let { add(Field("TAC", it.toString())) }
            (l?.eci ?: sys?.common?.get(Rat.LTE)?.cellId)?.let { eci ->
                add(Field("ECI (eNB/sector)", "$eci (${eci shr 8}/${eci and 0xFF})"))
            }
        }
        val s = sig
        val metrics = listOf(
            Metric("RSRP", MetricKind.RSRP, Rat.LTE, s?.lteRsrp?.toDouble(), "dBm"),
            Metric("RSRQ", MetricKind.RSRQ, Rat.LTE, s?.lteRsrq?.toDouble(), "dB"),
            Metric("SINR", MetricKind.SINR, Rat.LTE, s?.lteSnr, "dB"),
            // UE transmit power (TX_RX_INFO, only while in traffic).
            Metric("TX PWR", MetricKind.TX_POWER, Rat.LTE, txLte?.txPower, "dBm")
        )
        if (fields.isEmpty() && metrics.all { it.value == null }) return null
        return CellCard(if (mode == ServiceMode.NR_NSA) "LTE MCG" else "LTE primary cell", Rat.LTE, fields, metrics)
    }

    private fun nrCell(sa: Boolean): CellCard? {
        val s = sys
        val n = loc?.nr
        val pci = s?.nrPci ?: n?.pci
        val ch = s?.nrArfcn ?: loc?.nrArfcn ?: arfcn?.nrServing?.firstOrNull()
        val band = rfFor(NasRadioIf.NR5G)
        // NSA keeps a fixed set of fields ("–" when the NR leg is down) so the
        // SCG card holds its shape instead of popping in and out.
        fun MutableList<Field>.put(label: String, v: String?) { if (v != null) add(Field(label, v)) else if (!sa) add(Field(label, "–")) }
        val fields = buildList {
            // RF band info has no NR entry in NSA; derive it from the ARFCN.
            put("Band", band?.let { NasBands.label(it.bandEnum) } ?: ch?.let { nrBandForArfcn(it, nrHardwareBands) }?.let { "n$it" })
            put("NR-ARFCN", ch?.toString())
            put("Frequency", ch?.let { nrFrequencyForArfcn(it.toInt()) }?.let(::mhz))
            put("PCI", pci?.toString())
            put("SCS", s?.nrScsKhz?.let { "$it kHz" })
            band?.bandwidthMhz?.let { add(Field("Bandwidth", mhz(it))) }
            if (sa) {
                (s?.nrTac ?: n?.tac)?.let { add(Field("TAC", it.toString())) }
                // NCI is never split into gNB/sector: the gNB ID length isn't reported.
                (s?.nrNci ?: n?.nci)?.let { add(Field("NCI", it.toString())) }
            }
        }
        val metrics = listOf(
            Metric("SS-RSRP", MetricKind.RSRP, Rat.NR, sig?.nrRsrp?.toDouble(), "dBm"),
            Metric("SS-RSRQ", MetricKind.RSRQ, Rat.NR, sig?.nrRsrq?.toDouble(), "dB"),
            Metric("SS-SINR", MetricKind.SINR, Rat.NR, sig?.nrSnr, "dB")
        )
        if (sa && fields.isEmpty() && metrics.all { it.value == null }) return null
        return CellCard(if (sa) "NR primary cell" else "NR SCG", Rat.NR, fields, metrics)
    }

    private fun wcdmaCell(): CellCard? {
        val w = loc?.wcdma
        val band = rfFor(NasRadioIf.UMTS)
        val fields = buildList {
            w?.psc?.let { add(Field("PSC", it.toString())) }
            (w?.uarfcn ?: arfcn?.wcdmaServingUarfcn)?.let { add(Field("UARFCN", it.toString())) }
            band?.let { add(Field("Band", NasBands.label(it.bandEnum))) }
            band?.bandwidthMhz?.let { add(Field("Bandwidth", mhz(it))) }
            w?.lac?.let { add(Field("LAC", it.toString())) }
            w?.cellId?.let { add(Field("Cell ID", "$it (RNC ${it shr 16}, CID ${it and 0xFFFF})")) }
            errRate?.second?.let { add(Field("BLER", "$it %")) }
        }
        // SIG_INFO WCDMA signs are anomalous on this firmware; CELL_LOCATION
        // (whole dBm / dB) is the validated source.
        val metrics = listOf(
            Metric("RSCP", MetricKind.RSCP, Rat.WCDMA, w?.rscp?.toDouble(), "dBm"),
            Metric("Ec/Io", MetricKind.ECIO, Rat.WCDMA, w?.ecio?.toDouble(), "dB"),
            Metric("TX PWR", MetricKind.TX_POWER, Rat.WCDMA, txWcdma?.txPower, "dBm")
        )
        if (fields.isEmpty() && w == null) return null
        return CellCard("WCDMA serving cell", Rat.WCDMA, fields, metrics)
    }

    private fun gsmCell(): CellCard? {
        val g = loc?.gsm
        val c = g?.cell
        val band = rfFor(NasRadioIf.GSM)
        val fields = buildList {
            c?.bsic?.let { add(Field("BSIC", "$it (NCC ${c.ncc}, BCC ${c.bcc})")) }
            (c?.arfcn ?: arfcn?.gsmServingArfcn)?.let { add(Field("ARFCN", it.toString())) }
            band?.let { add(Field("Band", NasBands.label(it.bandEnum))) }
            c?.lac?.let { add(Field("LAC", it.toString())) }
            c?.cellId?.let { add(Field("Cell ID", it.toString())) }
            errRate?.first?.let { add(Field("RxQual", it.toString())) }
        }
        val range = c?.rssiRange
        val metrics = listOf(
            Metric("RSSI", MetricKind.RSSI, Rat.GSM, sig?.gsmRssi?.toDouble(), "dBm"),
            Metric("RxLev", MetricKind.RSSI, Rat.GSM, range?.first?.toDouble(), "dBm",
                text = when {
                    c == null -> null
                    range != null -> "${range.first} to ${range.last} dBm"
                    c.rxlev == 0 -> "below −110 dBm"
                    c.rxlev >= 63 -> "above −48 dBm"
                    else -> null
                }),
            Metric("TX PWR", MetricKind.TX_POWER, Rat.GSM, txGsm?.txPower, "dBm")
        )
        if (fields.isEmpty() && g == null) return null
        return CellCard("GSM serving cell", Rat.GSM, fields, metrics)
    }

    // ── Carriers ─────────────────────────────────────────────────────────

    private fun lteCarriers(): CarrierGroup? {
        if (mode != ServiceMode.LTE && mode != ServiceMode.NR_NSA) return null
        // Always shown in LTE/NSA (just the P column without CA), so the card
        // doesn't pop in and out. The PCell comes from the CA read, else from
        // the serving cell itself.
        val p = ca?.pcell ?: loc?.lte?.let { l ->
            val rfLte = rfFor(NasRadioIf.LTE)
            LteCarrier(l.pci, l.earfcn, rfLte?.bandwidthMhz, rfLte?.bandEnum ?: -1, null, null, null)
        } ?: return null
        val configured = ca?.scells.orEmpty().filter { it.state == ScellState.CONFIGURED || it.state == ScellState.ACTIVATED }
        val all = listOf(p) + configured
        // Per-carrier RSRP/RSRQ: the modem's L1 measurements on each serving
        // frequency, matched by EARFCN + PCI (an SCell's PCI on its EARFCN).
        val meas = buildMap {
            loc?.lte?.let { s -> s.intraFreq.forEach { put(s.earfcn to it.pci, it) } }
            loc?.lteInterFreq?.forEach { g -> g.cells.forEach { put(g.earfcn to it.pci, it) } }
        }
        return CarrierGroup(
            title = "Carrier aggregation",
            summary = "",
            table = true,
            carriers = all.mapIndexed { i, x ->
                val m = meas[x.earfcn to x.pci]
                Carrier(
                    role = if (i == 0) "P" else "S${x.index ?: i}",
                    rsrp = m?.rsrp ?: if (i == 0) sig?.lteRsrp?.toDouble() else null,
                    rsrq = m?.rsrq ?: if (i == 0) sig?.lteRsrq?.toDouble() else null,
                    sinr = if (i == 0) sig?.lteSnr else null,
                    band = if (x.bandEnum < 0) lteBandForEarfcn(x.earfcn.toInt())?.let { "B$it" } else NasBands.label(x.bandEnum),
                    channel = x.earfcn.toString(),
                    bandwidthMhz = x.bandwidthMhz,
                    state = when (x.state) {
                        null -> "Primary"; ScellState.ACTIVATED -> "Active"; ScellState.CONFIGURED -> "Configured"
                        else -> null
                    },
                    pci = x.pci
                )
            }
        )
    }

    private fun nrCarriers(): CarrierGroup? {
        if (mode != ServiceMode.NR_SA && mode != ServiceMode.NR_NSA) return null
        val serving = arfcn?.nrServing.orEmpty()
        val primary = sys?.nrArfcn ?: loc?.nrArfcn
        val extra = serving.filter { it != primary }
        // SA always shows the card (only the note when there's no secondary),
        // so it doesn't pop in and out.
        if (extra.isEmpty() && mode != ServiceMode.NR_SA) return null
        return CarrierGroup(
            title = "NR aggregation",
            summary = "",
            carriers = extra.map { ch ->
                Carrier(nrBandForArfcn(ch, nrHardwareBands)?.let { "n$it" } ?: "n?", null,
                    ch.toString() + (nrFrequencyForArfcn(ch.toInt())?.let { " (${mhz(it)})" } ?: ""), null, null, null)
            },
            note = "QMI only reports frequency data for NR Scells"
        )
    }

    // ── Neighbours and antennas ──────────────────────────────────────────

    // Sorted by band number (unknown last), then channel.
    private fun neighbors(): List<Neighbor> = neighborList().sortedWith(
        compareBy<Neighbor>({ it.band?.number ?: Int.MAX_VALUE }, { it.channel })
    )

    private fun neighborList(): List<Neighbor> = buildList {
        val l = loc ?: return@buildList
        if (servingRat == Rat.LTE) {
            l.lte?.let { s ->
                s.intraFreq.filter { it.pci != s.pci }.forEach { add(lteNeighbor(s.earfcn, it)) }
            }
            l.lteInterFreq.forEach { g -> g.cells.forEach { add(lteNeighbor(g.earfcn, it)) } }
        }
        if (servingRat == Rat.WCDMA) {
            l.wcdma?.neighbors?.forEach {
                add(Neighbor(Rat.WCDMA, "UARFCN", it.uarfcn.toLong(), "PSC", it.psc.toString(), listOf(
                    Metric("RSCP", MetricKind.RSCP, Rat.WCDMA, it.rscp.toDouble(), "dBm"),
                    Metric("Ec/Io", MetricKind.ECIO, Rat.WCDMA, it.ecio.toDouble(), "dB")),
                    band = wcdmaBandForUarfcn(it.uarfcn)?.let { b -> Band(Rat.WCDMA, b, "B$b") }))
            }
        }
        if (servingRat == Rat.GSM) {
            l.gsm?.neighbors?.forEach {
                val r = it.rssiRange
                add(Neighbor(Rat.GSM, "ARFCN", it.arfcn.toLong(), "BSIC", it.bsic?.toString() ?: "—", listOf(
                    Metric("RxLev", MetricKind.RSSI, Rat.GSM, r?.first?.toDouble(), "dBm",
                        text = r?.let { x -> "${x.first} to ${x.last} dBm" })),
                    band = gsmBandForArfcn(it.arfcn)))
            }
        }
    }

    private fun lteNeighbor(earfcn: Long, m: LteMeas) = Neighbor(Rat.LTE, "EARFCN", earfcn, "PCI", m.pci.toString(), listOf(
        Metric("RSRP", MetricKind.RSRP, Rat.LTE, m.rsrp, "dBm"),
        Metric("RSRQ", MetricKind.RSRQ, Rat.LTE, m.rsrq, "dB")),
        band = lteBandForEarfcn(earfcn.toInt())?.let { Band(Rat.LTE, it, "B$it") })

    private fun antennas(): List<AntennaGroup> = buildList {
        fun add(rat: Rat, t: TxRxInfo?) {
            if (t == null || t.chains.none { it.tuned }) return
            add(AntennaGroup(rat, t.chains, t.inTraffic, t.txPower))
        }
        when (mode) {
            ServiceMode.LTE -> add(Rat.LTE, txLte)
            ServiceMode.NR_NSA -> { add(Rat.LTE, txLte); add(Rat.NR, txNr) }
            ServiceMode.NR_SA -> add(Rat.NR, txNr)
            ServiceMode.WCDMA -> add(Rat.WCDMA, txWcdma)
            ServiceMode.GSM -> add(Rat.GSM, txGsm)
            else -> {}
        }
    }
}
