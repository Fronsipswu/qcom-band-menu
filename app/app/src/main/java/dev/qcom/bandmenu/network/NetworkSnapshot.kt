package dev.qcom.bandmenu.network

import java.util.Locale

/*
 * Turns one monitoring round (decoded NAS reads) into what the Network page
 * shows. Pure and device-free, so it's unit-tested with captured frames.
 * Rules: family-specific identifiers, no
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

data class CellCard(val title: String, val rat: Rat, val fields: List<Field>, val metrics: List<Metric>, val nci: Long? = null)

/** Splits a 36-bit NR Cell Identity into gNB ID ([gnbBits] = 22..32) and Cell ID (36 - [gnbBits]). */
fun formatNrNci(nci: Long, gnbBits: Int = 22): String {
    val cellBits = (36 - gnbBits).coerceIn(4, 14)
    return "${nci ushr cellBits} / ${nci and ((1L shl cellBits) - 1)}"
}

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
    val sinr: Double? = null,
    /** PDSCH summary from DIAG; null when DIAG is off or has nothing for it. */
    val diag: DiagCarrier? = null,
    /** NR carrier from DIAG (NR table rows). */
    val nr: DiagNrCarrier? = null
)

data class CarrierGroup(
    val title: String,
    val summary: String,
    val carriers: List<Carrier>,
    val note: String? = null,
    /** Render as a PCC/SCC column table (LTE CA). */
    val table: Boolean = false,
    /** DIAG is on: the table adds its PDSCH rows. */
    val diag: Boolean = false,
    /** An NR table from DIAG (NR row set). */
    val nrTable: Boolean = false,
    /** NSA's LTE and NR carriers in the LTE table's rows (MCG, SCG, S1...). */
    val endc: Boolean = false
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
    /** NSA only: [carrierGroups]' LTE and NR tables combined into one. */
    val endc: CarrierGroup? = null,
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
    private val nrHardwareBands: Set<Int> = emptySet(),
    /** DIAG PDSCH stats for this round; null when DIAG is off. */
    private val diag: DiagStats? = null,
    /** DIAG mode is on (even when this round's DIAG read failed or hasn't started). */
    private val diagMode: Boolean = diag != null
) {

    /**
     * An NR band derived from the NR-ARFCN. In DIAG mode an ARFCN two bands
     * share (n77/n78, n38/n41, n5/n26) gives none, so the band shows "–"
     * until the modem names it (B825, B823, RRC); one that only one band
     * covers is shown at once.
     */
    private fun nrBandFromArfcn(arfcn: Long): String? = nrBandLabelForArfcn(arfcn, nrHardwareBands, pairs = !diagMode)

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
        // The ARFCN list's NR RRC state / serving channels when it was read (every
        // NSA capture has them, also rounds without SYS_INFO's NR PCI). Without
        // it, SYS_INFO's NR PCI (TLV 0x54) only with a valid NR RSRP in SIG_INFO:
        // the PCI can stay after NR is gone (OnePlus 11 set to LTE only kept
        // reporting one, with NR RSRP invalid, and the page stuck on NR NSA);
        // every NSA capture has a valid NR RSRP.
        // Either way the NR carrier must have a valid RSRP/RSRQ (SIG_INFO, or
        // DIAG's NR carriers): after the SCG is released while idle, the ARFCN
        // list keeps an NR RRC state and sometimes a stale NR channel, with no NR
        // measurement anywhere (OnePlus 11 recording), and the page sat on NR
        // NSA with an empty SCG.
        val nrMeasured = sig?.nrRsrp != null || sig?.nrRsrq != null ||
            diag?.nr?.any { it.rsrp != null || it.rsrq != null } == true
        val nrLeg = nrMeasured && (if (arfcn != null) !arfcn.nrServing.isNullOrEmpty() || arfcn.nrRrc != null
            else s.nrPci != null)
        // NSA only while an NR leg is actually up; when the SCG drops the page
        // falls back to the LTE layout (EN-DC availability is not used).
        when {
            s.inService(Rat.LTE) && nrLeg -> ServiceMode.NR_NSA
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
        val lteGroup = lteCarriers()
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
            carrierGroups = listOfNotNull(lteGroup, nrCarriers()),
            endc = if (mode == ServiceMode.NR_NSA) endcCarriers(lteGroup) else null,
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
            // NSA shows only the LTE RRC state (the anchor), keeping two chips
            // beside the mode pill; the NR leg's RRC state still counts towards
            // detecting NSA.
            // One "RRC" chip: the serving RAT's state (the pill already names it).
            ServiceMode.LTE, ServiceMode.NR_NSA -> a.lteRrc?.let { add(Field("RRC", NasRrc.lte(it))) }
            ServiceMode.NR_SA -> a.nrRrc?.let { add(Field("RRC", NasRrc.nr(it))) }
            ServiceMode.WCDMA -> a.wcdmaRrc?.let { add(Field("RRC", NasRrc.wcdma(it))) }
            else -> {}
        }
    }

    // ── Cells ────────────────────────────────────────────────────────────

    /**
     * The modem's L1 measurements on each LTE serving frequency
     * (GET_CELL_LOCATION_INFO), keyed by EARFCN + PCI. The serving card and
     * the CA table's P column both read the PCell from here, so they match.
     */
    private val lteMeas: Map<Pair<Long, Int>, LteMeas> by lazy {
        buildMap {
            loc?.lte?.let { s -> s.intraFreq.forEach { put(s.earfcn to it.pci, it) } }
            loc?.lteInterFreq?.forEach { g -> g.cells.forEach { put(g.earfcn to it.pci, it) } }
        }
    }

    private fun lteCell(): CellCard? {
        val l = loc?.lte
        val pcell = ca?.pcell
        val pci = l?.pci ?: pcell?.pci
        val earfcn = l?.earfcn ?: pcell?.earfcn
        val band = rfFor(NasRadioIf.LTE)
        // DIAG's bandwidth (the RRC configuration) first when DIAG has this cell.
        val d = if (earfcn != null && pci != null) diag?.forPcell(earfcn, pci) else null
        // Rows of three (after the operator row): PCI, TAC, Timing Advance /
        // Band, Bandwidth, EARFCN / eNB/Cell, DL / UL. The first two rows
        // show "–" for a missing value so each field keeps its row.
        val fields = buildList {
            fun row(label: String, v: String?) = add(Field(label, v ?: "–"))
            row("PCI", pci?.toString())
            row("TAC", (l?.tac ?: sys?.common?.get(Rat.LTE)?.tac)?.toString())
            row("TA", diag?.lteTimingAdvance?.toString())
            row("Band", (band?.bandEnum ?: pcell?.bandEnum)?.let { NasBands.label(it) })
            row("Bandwidth", (d?.bandwidthMhz ?: pcell?.bandwidthMhz ?: band?.bandwidthMhz)?.let(::mhz))
            row("EARFCN", earfcn?.toString())
            // eNB/cell only (not the full ECI): fits a slot, so DL / UL shares the row.
            // An ECI is 28 bits (36.331); larger is the modem's "unknown" filler,
            // which would also be too wide for the slot.
            fun eciOf(v: Long?) = v?.takeIf { it in 0..0x0FFFFFFF }
            (eciOf(l?.eci) ?: eciOf(sys?.common?.get(Rat.LTE)?.cellId))?.let { eci ->
                add(Field("eNB/Cell", "${eci shr 8}/${eci and 0xFF}"))
            }
            earfcn?.let { lteFrequenciesForEarfcn(it.toInt()) }?.let { f ->
                add(Field("DL / UL", "${fmt(f.downlinkMhz)} / ${f.uplinkMhz?.let(::fmt) ?: "—"} MHz"))
            }
        }
        val s = sig
        // RSRP/RSRQ: the PCell's CELL_LOCATION measurement, as in the CA
        // table's P column (SIG_INFO only when it's missing). SINR exists only
        // in SIG_INFO, for both. With DIAG on, all three come from the PCell's
        // B193 values, as in the DIAG CA table (QMI only where DIAG has none).
        val m = if (earfcn != null && pci != null) lteMeas[earfcn to pci] else null
        val metrics = listOf(
            Metric("RSRP", MetricKind.RSRP, Rat.LTE, d?.rsrp ?: m?.rsrp ?: s?.lteRsrp?.toDouble(), "dBm"),
            Metric("RSRQ", MetricKind.RSRQ, Rat.LTE, d?.rsrq ?: m?.rsrq ?: s?.lteRsrq?.toDouble(), "dB"),
            Metric("SINR", MetricKind.SINR, Rat.LTE, d?.sinr ?: s?.lteSnr, "dB")
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
        // With DIAG on, this cell's values come from the DIAG NR table's entry
        // for the same PCI and NR-ARFCN (QMI where DIAG has none).
        val d = diag?.nr?.firstOrNull { it.pci == pci && (it.arfcn == ch || it.dlArfcn == ch) }
        // RF band info has no NR entry in NSA: DIAG's configured band, else
        // the band derived from the ARFCN (ambiguous where bands overlap).
        val bandLabel = band?.let { NasBands.label(it.bandEnum) }
            ?: d?.band?.takeIf { it > 0 && nrBandFitsArfcn(it, d.arfcn) }?.let { "n$it" }
            ?: ch?.let(::nrBandFromArfcn)
        val frequency = ch?.let { nrFrequencyForArfcn(it.toInt()) }?.let(::mhz)
        val nci = (s?.nrNci ?: n?.nci)?.takeIf { it in 0..0xFFFFFFFFFL }
        val fields = buildList {
            if (sa) {
                // Rows of three (after the operator row): Band, Bandwidth, NR-ARFCN /
                // SCS, Frequency, Timing Advance / PCI, TAC, gNB / Cell. A missing value is
                // "–" so each field keeps its row.
                fun row(label: String, v: String?) = add(Field(label, v ?: "–"))
                row("Band", bandLabel)
                // DIAG first: some modems (X60) report one fixed RF bandwidth
                // code for every NR band (100 MHz on n41 90 MHz and on n28).
                row("Bandwidth", (d?.bandwidthMhz?.toDouble() ?: band?.bandwidthMhz)?.let(::mhz))
                row("NARFCN", ch?.toString())
                row("SCS", s?.nrScsKhz?.let { "$it kHz" })
                row("Frequency", frequency)
                row("TA", diag?.nrTimingAdvance?.toString())
                row("PCI", pci?.toString())
                row("TAC", (s?.nrTac ?: n?.tac)?.toString())
                row("gNB / Cell", nci?.let(::formatNrNci))
            } else {
                put("Band", bandLabel)
                put("NARFCN", ch?.toString())
                put("Frequency", frequency)
                put("PCI", pci?.toString())
                // With DIAG on, the SCG's Timing Advance (the same NR random-access
                // TA as in SA) takes SCS's place; QMI has no TA, so it keeps SCS.
                if (diag != null) put("TA", diag.nrTimingAdvance?.toString())
                else put("SCS", (s?.nrScsKhz ?: d?.scsKhz)?.let { "$it kHz" })
                // QMI has no SCG bandwidth; DIAG does (B825/B887), so with DIAG
                // on the second row is always PCI, TA, Bandwidth.
                val bw = d?.bandwidthMhz?.toDouble() ?: band?.bandwidthMhz
                if (diag != null) put("Bandwidth", bw?.let(::mhz)) else bw?.let { add(Field("Bandwidth", mhz(it))) }
            }
        }
        val metrics = listOf(
            Metric("SS-RSRP", MetricKind.RSRP, Rat.NR, d?.rsrp ?: sig?.nrRsrp?.toDouble(), "dBm"),
            Metric("SS-RSRQ", MetricKind.RSRQ, Rat.NR, d?.rsrq ?: sig?.nrRsrq?.toDouble(), "dB"),
            Metric("SS-SINR", MetricKind.SINR, Rat.NR, d?.sinr ?: sig?.nrSnr, "dB")
        )
        if (sa && fields.isEmpty() && metrics.all { it.value == null }) return null
        return CellCard(if (sa) "NR primary cell" else "NR SCG", Rat.NR, fields, metrics, nci = if (sa) nci else null)
    }

    private fun wcdmaCell(): CellCard? {
        val w = loc?.wcdma
        val band = rfFor(NasRadioIf.UMTS)
        val uarfcn = w?.uarfcn ?: arfcn?.wcdmaServingUarfcn
        val fields = buildList {
            (band?.let { NasBands.label(it.bandEnum) } ?: uarfcn?.let(::wcdmaBandForUarfcn)?.let { "B$it" })
                ?.let { add(Field("Band", it)) }
            uarfcn?.let { add(Field("UARFCN", it.toString())) }
            uarfcn?.let(::wcdmaFrequencyForUarfcn)?.let { add(Field("Frequency", mhz(it))) }
            w?.psc?.let { add(Field("PSC", it.toString())) }
            // Cell ID = RNC (high 16 bits) / CID, short enough for PSC, Cell ID and
            // LAC to share a row.
            w?.cellId?.let { add(Field("Cell ID", "${it shr 16} / ${it and 0xFFFF}")) }
            w?.lac?.let { add(Field("LAC", it.toString())) }
            errRate?.second?.let { add(Field("BLER", "$it %")) }
        }
        // SIG_INFO WCDMA signs are anomalous on this firmware; CELL_LOCATION
        // (whole dBm / dB) is the validated source.
        val metrics = listOf(
            Metric("RSCP", MetricKind.RSCP, Rat.WCDMA, w?.rscp?.toDouble(), "dBm"),
            Metric("Ec/Io", MetricKind.ECIO, Rat.WCDMA, w?.ecio?.toDouble(), "dB")
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
        diagCarriers()?.let { return it }
        // Always shown in LTE/NSA (just the P column without CA), so the card
        // doesn't pop in and out. The PCell comes from the CA read, else from
        // the serving cell itself.
        val p = ca?.pcell ?: loc?.lte?.let { l ->
            val rfLte = rfFor(NasRadioIf.LTE)
            LteCarrier(l.pci, l.earfcn, rfLte?.bandwidthMhz, rfLte?.bandEnum ?: -1, null, null, null)
        } ?: return null
        val configured = ca?.scells.orEmpty().filter { it.state == ScellState.CONFIGURED || it.state == ScellState.ACTIVATED }
        val all = listOf(p) + configured
        // Per-carrier RSRP/RSRQ: matched by EARFCN + PCI (an SCell's PCI on its EARFCN).
        return CarrierGroup(
            title = "Carrier aggregation",
            summary = "",
            table = true,
            diag = diag != null,
            carriers = all.mapIndexed { i, x ->
                val m = lteMeas[x.earfcn to x.pci]
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
                    pci = x.pci,
                    diag = diag?.let { d -> if (i == 0) d.forPcell(x.earfcn, x.pci) else d.forScell(x.earfcn, x.pci) }
                )
            }
        )
    }

    /**
     * With DIAG on, the whole table comes from the helper: the carrier list,
     * band and bandwidth from B184, RSRP/RSRQ/SINR (SCells too) from B193 and
     * the PDSCH rows from B173/B126. Until the helper has seen a B184 (the
     * first round or so), the QMI table is used instead.
     */
    private fun diagCarriers(): CarrierGroup? {
        val list = diag?.caCarriers?.takeIf { it.isNotEmpty() } ?: return null
        return CarrierGroup(
            title = "Carrier aggregation",
            summary = "",
            table = true,
            diag = true,
            carriers = list.map { d ->
                Carrier(
                    role = if (d.slot == 0) "P" else "S${d.slot}",
                    band = d.band?.takeIf { it > 0 }?.let { "B$it" } ?: lteBandForEarfcn(d.earfcn!!.toInt())?.let { "B$it" },
                    channel = d.earfcn.toString(),
                    bandwidthMhz = d.bandwidthMhz ?: qmiBandwidth(d.earfcn!!, d.pci!!),
                    state = when (d.state) { 2 -> if (d.slot == 0) "Primary" else "Active"; 1 -> "Configured"; else -> null },
                    pci = d.pci,
                    rsrp = d.rsrp, rsrq = d.rsrq, sinr = d.sinr,
                    diag = d
                )
            }
        )
    }

    /** QMI's bandwidth for a carrier, for DIAG rows without a current B184
     *  (some modems only send it on CA events). */
    private fun qmiBandwidth(earfcn: Long, pci: Int): Double? {
        ca?.pcell?.takeIf { it.earfcn == earfcn && it.pci == pci }?.bandwidthMhz?.let { return it }
        ca?.scells?.firstOrNull { it.earfcn == earfcn && it.pci == pci }?.bandwidthMhz?.let { return it }
        val l = loc?.lte
        return if (l != null && l.earfcn == earfcn && l.pci == pci) rfFor(NasRadioIf.LTE)?.bandwidthMhz else null
    }

    private fun nrCarriers(): CarrierGroup? {
        if (mode != ServiceMode.NR_SA && mode != ServiceMode.NR_NSA) return null
        diagNrCarriers()?.let { return it }
        // NSA with DIAG on but no NR carrier from DIAG (the SCG dropped while
        // idle): no card, rather than the QMI frequency-only fallback.
        if (diag != null && mode == ServiceMode.NR_NSA) return null
        val serving = arfcn?.nrServing.orEmpty()
        val primary = sys?.nrArfcn ?: loc?.nrArfcn
        val extra = serving.filter { it != primary }
        // Always shown in SA and NSA (only the note when there's no secondary),
        // so it doesn't pop in and out.
        return CarrierGroup(
            title = "NR aggregation",
            summary = "",
            carriers = extra.map { ch ->
                Carrier(nrBandFromArfcn(ch) ?: if (diagMode) "–" else "n?", null,
                    ch.toString() + (nrFrequencyForArfcn(ch.toInt())?.let { " (${String.format(Locale.US, "%.1f MHz", it)})" } ?: ""), null, null, null)
            },
            note = "QMI only reports frequency data for NR Scells"
        )
    }

    /**
     * With DIAG on, the NR card is a full CA table from the helper: carriers
     * from B825 (PCell from B823), RSRP/RSRQ from B97F, SINR from B8D8, CQI
     * from B8A7 and the PDSCH rows from B887 (bandwidth and SCS from B887
     * when B825, sent only on reconfiguration, hasn't been seen).
     * The PCell: B823 when the helper saw one, else QMI's NR serving cell,
     * else the modem's carrier 0.
     * Band: the helper's (B825, B823 for the PCell, RRC), used only if the
     * cell's NR-ARFCN is in it; else derived from the NR-ARFCN when only one
     * band covers it; else "–" until the modem names it.
     */
    private fun diagNrCarriers(): CarrierGroup? {
        val list = diag?.nr?.takeIf { it.isNotEmpty() } ?: return null
        val qmiPci = sys?.nrPci ?: loc?.nr?.pci
        val qmiArfcn = sys?.nrArfcn ?: loc?.nrArfcn
        val primary = list.firstOrNull { it.pcell == true }
            ?: list.firstOrNull { it.pcell == null && it.pci == qmiPci && it.arfcn == qmiArfcn }
            ?: list.minBy { it.cc }
        val ordered = listOf(primary) + list.filter { it !== primary }.sortedBy { it.cc }
        return CarrierGroup(
            title = "NR aggregation",
            summary = "",
            table = true,
            diag = true,
            nrTable = true,
            carriers = ordered.mapIndexed { i, d ->
                Carrier(
                    role = if (i == 0) "P" else "S$i",
                    band = d.band?.takeIf { it > 0 && nrBandFitsArfcn(it, d.arfcn) }?.let { "n$it" } ?: nrBandFromArfcn(d.arfcn),
                    channel = d.arfcn.toString(),
                    bandwidthMhz = d.bandwidthMhz?.toDouble(),
                    state = if (d === primary) "Primary" else "Active",
                    pci = d.pci,
                    // SS-SINR for every carrier (CSI-SINR has its own row;
                    // the beam SNR, another kind, isn't shown).
                    rsrp = d.rsrp, rsrq = d.rsrq, sinr = d.sinr,
                    nr = d
                )
            }
        )
    }

    /**
     * NSA's LTE and NR carriers in one table with the LTE table's rows (NR
     * fitted in: ARFCN for both channels; no NR-only rows such as SCS or
     * CSI-SINR). Columns: MCG (the LTE PCell), the LTE SCells, SCG (the NR
     * PSCell), then the NR SCells; the SCells numbered S1, S2... across both
     * (LTE first).
     * NR carriers come from DIAG, else from QMI (the SCG's serving cell and
     * the NR SCells' frequencies).
     */
    private fun endcCarriers(lte: CarrierGroup?): CarrierGroup? {
        val mcg = lte?.carriers?.firstOrNull() ?: return null
        val diagNr = diagNrCarriers()?.carriers
        val nr = diagNr ?: qmiNrCarriers()
        val lteS = lte.carriers.drop(1)
        val nrS = nr.drop(1)
        return CarrierGroup(
            title = "ENDC",
            summary = "",
            table = true,
            diag = diag != null,
            endc = true,
            carriers = listOf(mcg.copy(role = "MCG")) +
                lteS.mapIndexed { i, c -> c.copy(role = "S${i + 1}") } +
                nr.take(1).map { it.copy(role = "SCG") } +
                nrS.mapIndexed { i, c -> c.copy(role = "S${lteS.size + i + 1}") },
            note = if (diagNr == null && nr.size > 1) "QMI only reports frequency data for NR Scells" else null
        )
    }

    /** The NR leg from QMI: the SCG's serving cell, then frequency-only NR SCells. */
    private fun qmiNrCarriers(): List<Carrier> {
        val ch = sys?.nrArfcn ?: loc?.nrArfcn ?: arfcn?.nrServing?.firstOrNull() ?: return emptyList()
        val band = rfFor(NasRadioIf.NR5G)
        val scg = Carrier(
            role = "SCG",
            band = band?.let { NasBands.label(it.bandEnum) } ?: nrBandFromArfcn(ch),
            channel = ch.toString(),
            bandwidthMhz = band?.bandwidthMhz,
            state = "Primary",
            pci = sys?.nrPci ?: loc?.nr?.pci,
            rsrp = sig?.nrRsrp?.toDouble(), rsrq = sig?.nrRsrq?.toDouble(), sinr = sig?.nrSnr
        )
        val scells = arfcn?.nrServing.orEmpty().filter { it != ch }.map {
            Carrier("S", nrBandFromArfcn(it) ?: if (diagMode) null else "n?", it.toString(), null, null, null)
        }
        return listOf(scg) + scells
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
