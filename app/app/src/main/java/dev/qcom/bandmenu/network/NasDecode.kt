package dev.qcom.bandmenu.network

/*
 * Per-message decoders for the NAS GETs the Network page uses. Ported from
 * test/telephony-page/decode-qmi-probe.py (Codex) and checked against the
 * NAS spec. Every decoder reads only TLVs that are present: an absent TLV
 * stays null, and sentinel values become null, never zero.
 */

object NasMsg {
    const val HOME_NETWORK = 0x0025
    const val RF_BAND_INFO = 0x0031
    const val CELL_LOCATION = 0x0043
    const val PLMN_NAME = 0x0044
    const val SYS_INFO = 0x004D
    const val SIG_INFO = 0x004F
    const val ERR_RATE = 0x0052
    const val TX_RX_INFO = 0x005A
    const val LTE_CA_INFO = 0x00AC
    const val ARFCN_LIST = 0x00D2
}

private fun Int.validOrNull(invalid: Int) = takeIf { it != invalid }

/** PLMN as kept by the modem: ASCII digits, 0xFF padding for a 2-digit MNC. */
data class Plmn(val mcc: String, val mnc: String) {
    override fun toString() = "$mcc-$mnc"
}

private fun asciiDigits(b: ByteArray): String? {
    val s = b.takeWhile { it != 0xFF.toByte() }.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
    return s.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

/** 3GPP TS 24.008 packed BCD PLMN (CELL_LOCATION). */
fun bcdPlmn(b: ByteArray): Plmn? {
    if (b.size != 3) return null
    fun d(v: Int) = if (v in 0..9) ('0' + v) else null
    val mcc = listOf(d(b[0].toInt() and 0xF), d((b[0].toInt() shr 4) and 0xF), d(b[1].toInt() and 0xF))
    val mnc3 = (b[1].toInt() shr 4) and 0xF
    val mnc = listOfNotNull(d(b[2].toInt() and 0xF), d((b[2].toInt() shr 4) and 0xF), if (mnc3 == 0xF) null else d(mnc3))
    if (mcc.any { it == null } || mnc.size < 2) return null
    return Plmn(mcc.joinToString(""), mnc.joinToString(""))
}

// ── GET_SYS_INFO (0x004D) ────────────────────────────────────────────────

data class ServiceStatus(val status: Int, val trueStatus: Int)

/** Common 3GPP system info block (GSM 0x17, WCDMA 0x18, LTE 0x19, NR5G 0x4B). */
data class SysCommon(
    val roaming: Boolean?,
    val forbidden: Boolean?,
    val lac: Int?,
    val cellId: Long?,
    val plmn: Plmn?,
    /** LTE only (0x19). */
    val tac: Int?
)

data class SysInfo(
    val status: Map<Rat, ServiceStatus>,
    val common: Map<Rat, SysCommon>,
    val primaryPlmn: Plmn?,
    val nrPci: Int?,
    val nrArfcn: Long?,
    val nrScsKhz: Int?,
    val nrTac: Int?,
    val nrNci: Long?,
    val endcAvailable: Boolean?,
    val dcnrRestricted: Boolean?
) {
    fun inService(rat: Rat) = status[rat]?.status == NasServiceStatus.SERVICE
}

fun decodeSysInfo(f: QmiFrame): SysInfo {
    val status = LinkedHashMap<Rat, ServiceStatus>()
    for ((tag, rat) in listOf(0x12 to Rat.GSM, 0x13 to Rat.WCDMA, 0x14 to Rat.LTE, 0x4A to Rat.NR)) {
        f.tlv(tag)?.let { val r = TlvReader(it); status[rat] = ServiceStatus(r.u8(), r.u8()) }
    }
    val common = LinkedHashMap<Rat, SysCommon>()
    for ((tag, rat) in listOf(0x17 to Rat.GSM, 0x18 to Rat.WCDMA, 0x19 to Rat.LTE, 0x4B to Rat.NR)) {
        val v = f.tlv(tag) ?: continue
        if (v.size < 26) throw QmiFormatException("sys info 0x%02x too short (${v.size})".format(tag))
        val r = TlvReader(v)
        r.skip(4) // service domain / capability (+ valid flags)
        val roamValid = r.u8() == 1; val roam = r.u8()
        val forbValid = r.u8() == 1; val forb = r.u8()
        val lacValid = r.u8() == 1; val lac = r.u16()
        val cellValid = r.u8() == 1; val cell = r.u32()
        r.skip(3) // registration reject info
        val netValid = r.u8() == 1; val mcc = r.bytes(3); val mnc = r.bytes(3)
        var tac: Int? = null
        if (rat == Rat.LTE && r.remaining >= 3) {
            val tacValid = r.u8() == 1; val t = r.u16(); if (tacValid) tac = t
        }
        val plmn = if (netValid) asciiDigits(mcc)?.let { m -> asciiDigits(mnc)?.let { Plmn(m, it) } } else null
        common[rat] = SysCommon(
            roaming = if (roamValid) roam != 0 else null,
            forbidden = if (forbValid) forb != 0 else null,
            lac = if (lacValid && rat != Rat.LTE && rat != Rat.NR) lac else null,
            cellId = if (cellValid && rat != Rat.NR) cell else null,
            plmn = plmn,
            tac = tac
        )
    }
    val primaryPlmn = f.tlv(0x5F)?.let {
        val r = TlvReader(it); r.u8(); val mcc = r.bytes(3); val mnc = r.bytes(3)
        asciiDigits(mcc)?.let { m -> asciiDigits(mnc)?.let { n -> Plmn(m, n) } }
    }
    fun bool(tag: Int) = f.tlv(tag)?.let { TlvReader(it).u8() != 0 }
    return SysInfo(
        status = status,
        common = common,
        primaryPlmn = primaryPlmn,
        nrPci = f.tlv(0x54)?.let { TlvReader(it).u16() },
        nrArfcn = f.tlv(0x60)?.let { TlvReader(it).u32() },
        nrScsKhz = f.tlv(0x5D)?.let { nrScsKhz(TlvReader(it).i32()) },
        nrTac = f.tlv(0x50)?.takeIf { it.size == 3 }?.let { ((it[0].toInt() and 0xFF) shl 16) or ((it[1].toInt() and 0xFF) shl 8) or (it[2].toInt() and 0xFF) },
        nrNci = f.tlv(0x5E)?.let { TlvReader(it).u64() },
        endcAvailable = bool(0x4E),
        dcnrRestricted = bool(0x4F)
    )
}

// ── GET_SIG_INFO (0x004F) ────────────────────────────────────────────────

data class SigInfo(
    val gsmRssi: Int?,
    /** Raw WCDMA fields; their signs are anomalous on this firmware (see Codex WCDMA findings). */
    val wcdmaRssiRaw: Int?,
    val wcdmaEcioRaw: Int?,
    val wcdmaRscpRaw: Int?,
    val lteRssi: Int?,
    val lteRsrq: Int?,
    val lteRsrp: Int?,
    /** dB (TLV carries tenths). */
    val lteSnr: Double?,
    val nrRsrp: Int?,
    val nrRsrq: Int?,
    val nrSnr: Double?
)

fun decodeSigInfo(f: QmiFrame): SigInfo {
    val wcdma = f.tlv(0x13)?.let { val r = TlvReader(it); r.i8() to r.i16() }
    val lte = f.tlv(0x14)?.let { val r = TlvReader(it); listOf(r.i8(), r.i8(), r.i16(), r.i16()) }
    val nr = f.tlv(0x17)?.let { val r = TlvReader(it); r.i16() to r.i16() }
    return SigInfo(
        gsmRssi = f.tlv(0x12)?.let { TlvReader(it).i8() },
        wcdmaRssiRaw = wcdma?.first,
        wcdmaEcioRaw = wcdma?.second,
        wcdmaRscpRaw = f.tlv(0x19)?.let { TlvReader(it).i16() },
        lteRssi = lte?.get(0),
        lteRsrq = lte?.get(1),
        lteRsrp = lte?.get(2),
        lteSnr = lte?.get(3)?.validOrNull(INT16_INVALID)?.div(10.0),
        nrRsrp = nr?.first?.validOrNull(INT16_INVALID),
        nrSnr = nr?.second?.validOrNull(INT16_INVALID)?.div(10.0),
        nrRsrq = f.tlv(0x18)?.let { TlvReader(it).i16().validOrNull(INT16_INVALID) }
    )
}

// ── GET_CELL_LOCATION_INFO (0x0043) ──────────────────────────────────────

/** One LTE measurement: values in dB/dBm (TLV carries tenths). */
data class LteMeas(val pci: Int, val rsrq: Double, val rsrp: Double, val rssi: Double)

data class LteServingLoc(
    val idle: Boolean, val plmn: Plmn?, val tac: Int, val eci: Long, val earfcn: Long, val pci: Int,
    val intraFreq: List<LteMeas>
)

data class LteFreqGroup(val earfcn: Long, val cells: List<LteMeas>)

data class NrServingLoc(
    val plmn: Plmn?, val tac: Int, val nci: Long, val pci: Int,
    val rsrq: Double, val rsrp: Double, val snr: Double
)

data class WcdmaNeighbor(val uarfcn: Int, val psc: Int, val rscp: Int, val ecio: Int)

data class WcdmaServingLoc(
    val cellId: Long, val plmn: Plmn?, val lac: Int, val uarfcn: Int, val psc: Int,
    val rscp: Int, val ecio: Int, val neighbors: List<WcdmaNeighbor>
)

data class GsmCell(
    val cellId: Long?, val plmn: Plmn?, val lac: Int?, val arfcn: Int, val bsic: Int?, val rxlev: Int
) {
    val ncc get() = bsic?.shr(3)
    val bcc get() = bsic?.and(7)
    /** RxLev is an interval: 0 = below -110, 63 = above -48 dBm. */
    val rssiRange: IntRange? get() = if (rxlev in 1..62) (rxlev - 111)..(rxlev - 110) else null
}

data class GsmServingLoc(val cell: GsmCell, val timingAdvance: Long?, val neighbors: List<GsmCell>)

data class CellLocation(
    val lte: LteServingLoc?,
    val lteInterFreq: List<LteFreqGroup>,
    /** Microseconds; distinct from Android's TA index. */
    val lteTimingAdvanceUs: Int?,
    val nrArfcn: Long?,
    val nr: NrServingLoc?,
    val wcdma: WcdmaServingLoc?,
    val gsm: GsmServingLoc?
)

private fun TlvReader.lteMeas() = LteMeas(u16(), i16() / 10.0, i16() / 10.0, i16() / 10.0).also { i16() /* srxlev */ }

private fun TlvReader.gsmCell(): GsmCell {
    val cid = u32(); val plmn = bytes(3); val lac = u16(); val arfcn = u16(); val bsic = u8()
    return GsmCell(cid.takeIf { it != UINT32_INVALID }, bcdPlmn(plmn), lac.takeIf { cid != UINT32_INVALID }, arfcn, bsic.takeIf { it != 0xFF }, 0)
}

fun decodeCellLocation(f: QmiFrame): CellLocation {
    val lte = f.tlv(0x13)?.let {
        val r = TlvReader(it)
        val idle = r.u8() != 0; val plmn = r.bytes(3); val tac = r.u16(); val eci = r.u32()
        var earfcn = r.u16().toLong(); val pci = r.u16()
        r.skip(4) // reselection priority + thresholds
        val count = r.u8()
        f.tlv(0x27)?.let { e -> earfcn = TlvReader(e).u32() }
        val meas = List(count) { r.lteMeas() }
        r.done()
        LteServingLoc(idle, bcdPlmn(plmn), tac, eci, earfcn, pci, meas)
    }
    val inter = f.tlv(0x14)?.let {
        val r = TlvReader(it); r.u8(); val count = r.u8()
        val ext = f.tlv(0x28)?.let { e -> TlvReader(e).also { er -> if (er.u8() != count) throw QmiFormatException("LTE inter-freq count mismatch") } }
        val groups = List(count) {
            var earfcn = r.u16().toLong(); r.skip(3); val cells = r.u8()
            ext?.let { e -> earfcn = e.u32() }
            LteFreqGroup(earfcn, List(cells) { r.lteMeas() })
        }
        r.done(); ext?.done()
        groups
    } ?: emptyList()
    val nr = f.tlv(0x2F)?.let {
        val r = TlvReader(it)
        val plmn = r.bytes(3); val tacB = r.bytes(3)
        val tac = ((tacB[0].toInt() and 0xFF) shl 16) or ((tacB[1].toInt() and 0xFF) shl 8) or (tacB[2].toInt() and 0xFF)
        val nci = r.u64(); val pci = r.u16()
        val rsrq = r.i16(); val rsrp = r.i16(); val snr = r.i16()
        r.done()
        NrServingLoc(bcdPlmn(plmn), tac, nci, pci, rsrq / 10.0, rsrp / 10.0, snr / 10.0)
    }
    val wcdma = f.tlv(0x11)?.let {
        val r = TlvReader(it)
        var cid = r.u16().toLong(); val plmn = r.bytes(3); val lac = r.u16(); val uarfcn = r.u16(); val psc = r.u16()
        val rscp = r.i16(); val ecio = r.i16(); val count = r.u8()
        val n = List(count) { WcdmaNeighbor(r.u16(), r.u16(), r.i16(), r.i16()) }
        val geran = r.u8(); r.skip(geran * 6)
        r.done()
        f.tlv(0x17)?.let { c -> cid = TlvReader(c).u32() }
        WcdmaServingLoc(cid, bcdPlmn(plmn), lac, uarfcn, psc, rscp, ecio, n)
    }
    val gsm = f.tlv(0x10)?.let {
        val r = TlvReader(it)
        val cid = r.u32(); val plmn = r.bytes(3); val lac = r.u16(); val arfcn = r.u16(); val bsic = r.u8()
        val ta = r.u32(); val rxlev = r.u16(); val count = r.u8()
        val serving = GsmCell(cid.takeIf { c -> c != UINT32_INVALID }, bcdPlmn(plmn), lac.takeIf { cid != UINT32_INVALID }, arfcn, bsic.takeIf { b -> b != 0xFF }, rxlev)
        val n = List(count) { r.gsmCell().copy(rxlev = r.u16()) }
        r.done()
        GsmServingLoc(serving, ta.takeIf { t -> t != UINT32_INVALID }, n)
    }
    return CellLocation(
        lte = lte, lteInterFreq = inter,
        lteTimingAdvanceUs = f.tlv(0x1E)?.let { TlvReader(it).i32().takeIf { t -> t != -1 } },
        nrArfcn = f.tlv(0x2E)?.let { TlvReader(it).u32() },
        nr = nr, wcdma = wcdma, gsm = gsm
    )
}

// ── GET_RF_BAND_INFO (0x0031) ────────────────────────────────────────────

data class RfBand(val radioIf: Int, val bandEnum: Int, val channel: Long, val bandwidthMhz: Double?)

fun decodeRfBandInfo(f: QmiFrame): List<RfBand> {
    val ext = f.tlv(0x11)
    val v = ext ?: f.tlv(0x01) ?: return emptyList()
    val r = TlvReader(v); val count = r.u8()
    val bands = List(count) { Triple(r.u8(), r.u16(), if (ext != null) r.u32() else r.u16().toLong()) }
    r.done()
    val widths = f.tlv(0x12)?.let {
        val w = TlvReader(it); val c = w.u8()
        List(c) { w.u8() to w.i32() }.also { w.done() }.toMap()
    } ?: emptyMap()
    return bands.map { (rat, band, ch) -> RfBand(rat, band, ch, widths[rat]?.let(NasBandwidth::rfMhz)) }
}

// ── GET_ARFCN_LIST (0x00D2) ──────────────────────────────────────────────

data class LteFreq(val downlinkOnly: Boolean, val dlEarfcn: Long, val ulEarfcn: Long, val bandwidthMhz: Double?)

data class ArfcnList(
    val lteServing: List<LteFreq>?,
    val lteNeighbor: List<LteFreq>?,
    val lteRrc: Int?,
    val nrRrc: Int?,
    /** Serving NR frequencies (0x1C); more than one means NR CA is reported. */
    val nrServing: List<Long>?,
    /** null = TLV absent (no information), not an empty list. */
    val nrNeighbor: List<Long>?,
    val wcdmaServingUarfcn: Int?,
    val wcdmaRrc: Int?,
    val gsmServingArfcn: Int?
)

fun decodeArfcnList(f: QmiFrame): ArfcnList {
    fun lteList(tag: Int) = f.tlv(tag)?.let {
        val r = TlvReader(it); val c = r.u8()
        List(c) { LteFreq(r.u8() != 0, r.u32(), r.u32(), NasBandwidth.lteMhz(r.i32())) }.also { r.done() }
    }
    fun u32List(tag: Int) = f.tlv(tag)?.let {
        val r = TlvReader(it); val c = r.u8(); List(c) { r.u32() }.also { r.done() }
    }
    return ArfcnList(
        lteServing = lteList(0x18), lteNeighbor = lteList(0x1A),
        lteRrc = f.tlv(0x19)?.let { TlvReader(it).i32() },
        nrRrc = f.tlv(0x1B)?.let { TlvReader(it).i32() },
        nrServing = u32List(0x1C), nrNeighbor = u32List(0x1D),
        wcdmaServingUarfcn = f.tlv(0x14)?.let { TlvReader(it).u16() },
        wcdmaRrc = f.tlv(0x16)?.let { TlvReader(it).i32() },
        gsmServingArfcn = f.tlv(0x10)?.let { TlvReader(it).u16() }
    )
}

// ── GET_LTE_CPHY_CA_INFO (0x00AC) ────────────────────────────────────────

enum class ScellState { DECONFIGURED, CONFIGURED, ACTIVATED, UNKNOWN }

data class LteCarrier(
    val pci: Int, val earfcn: Long, val bandwidthMhz: Double?, val bandEnum: Int,
    /** null for the PCell. */
    val state: ScellState?, val index: Int?, val uplinkConfigured: Boolean?
)

data class LteCaInfo(val pcell: LteCarrier?, val scells: List<LteCarrier>)

fun decodeLteCaInfo(f: QmiFrame): LteCaInfo {
    val pcell = f.tlv(0x13)?.let {
        val r = TlvReader(it); val pci = r.u16(); var ch = r.u16().toLong(); val bw = r.i32(); val band = r.u16()
        f.tlv(0x16)?.let { e -> ch = TlvReader(e).u32() }
        LteCarrier(pci, ch, NasBandwidth.lteMhz(bw), band, null, null, null)
    }
    val scells = f.tlv(0x19)?.let {
        val r = TlvReader(it); val c = r.u8()
        List(c) {
            val pci = r.u16(); val ch = r.u32(); val bw = r.i32(); val band = r.u16(); val st = r.i32(); val idx = r.u8(); val ul = r.u8()
            LteCarrier(pci, ch, NasBandwidth.lteMhz(bw), band,
                when (st) { 0 -> ScellState.DECONFIGURED; 1 -> ScellState.CONFIGURED; 2 -> ScellState.ACTIVATED; else -> ScellState.UNKNOWN },
                idx, ul != 0)
        }.also { r.done() }
    } ?: emptyList()
    return LteCaInfo(pcell, scells)
}

// ── GET_TX_RX_INFO (0x005A) ──────────────────────────────────────────────

data class RxChain(
    val chain: Int, val tuned: Boolean,
    /** dBm / dB, already divided from tenths; null when not valid for the RAT. */
    val rxPower: Double?, val ecio: Double?, val rscp: Double?, val rsrp: Double?, val phaseDeg: Double?
)

data class TxRxInfo(val radioIf: Int, val chains: List<RxChain>, val inTraffic: Boolean?, val txPower: Double?)

fun decodeTxRxInfo(f: QmiFrame, radioIf: Int): TxRxInfo {
    val chains = listOf(0x10 to 0, 0x11 to 1, 0x15 to 2, 0x16 to 3).mapNotNull { (tag, chain) ->
        f.tlv(tag)?.let {
            val r = TlvReader(it)
            val tuned = r.u8() != 0; val power = r.i32(); val ecio = r.i32(); val rscp = r.i32(); val rsrp = r.i32(); val phase = r.u32()
            r.done()
            fun t(v: Int) = v.takeIf { x -> x != INT32_INVALID }?.div(10.0)
            val legacy = radioIf == NasRadioIf.UMTS || radioIf == NasRadioIf.GSM
            RxChain(
                chain, tuned,
                rxPower = t(power),
                ecio = if (radioIf == NasRadioIf.UMTS) t(ecio) else null,
                rscp = if (radioIf == NasRadioIf.UMTS) t(rscp) else null,
                // LTE placeholders <= -200 dBm were seen even with tuned=true.
                rsrp = if (legacy) null else t(rsrp)?.takeIf { v -> v > -200.0 },
                phaseDeg = phase.takeIf { p -> p != UINT32_INVALID }?.div(100.0)
            )
        }
    }
    val tx = f.tlv(0x12)?.let { val r = TlvReader(it); val traffic = r.u8() != 0; val p = r.i32(); traffic to p }
    return TxRxInfo(
        radioIf, chains,
        inTraffic = tx?.first,
        // TX power is only meaningful while in traffic.
        txPower = tx?.takeIf { it.first }?.second?.takeIf { it != INT32_INVALID }?.div(10.0)
    )
}

// ── GET_HOME_NETWORK (0x0025) / GET_PLMN_NAME (0x0044) ───────────────────

data class HomeNetwork(val mcc: Int, val mnc: Int, val description: String?)

fun decodeHomeNetwork(f: QmiFrame): HomeNetwork? = f.tlv(0x01)?.let {
    val r = TlvReader(it); val mcc = r.u16(); val mnc = r.u16(); val n = r.u8()
    val desc = r.bytes(n); r.done()
    HomeNetwork(mcc, mnc, printableAscii(desc))
}

data class PlmnName(val spn: String?, val shortName: String?, val longName: String?)

fun decodePlmnName(f: QmiFrame): PlmnName? = f.tlv(0x10)?.let {
    val r = TlvReader(it)
    val spnEnc = r.u8(); val spn = r.bytes(r.u8())
    val sEnc = r.u8(); r.skip(2); val short = r.bytes(r.u8())
    val lEnc = r.u8(); r.skip(2); val long = r.bytes(r.u8())
    r.done()
    PlmnName(decodeName(spnEnc, spn), decodeName(sEnc, short), decodeName(lEnc, long))
}

/** Request payload for GET_PLMN_NAME for a PLMN, keeping a 3-digit MNC. */
fun plmnNamePayload(plmn: Plmn): String {
    val mcc = plmn.mcc.toInt(); val mnc = plmn.mnc.toInt()
    val pcs = if (plmn.mnc.length == 3) 1 else 0
    return "010400%02x%02x%02x%02x110100%02x".format(mcc and 0xFF, mcc shr 8, mnc and 0xFF, mnc shr 8, pcs)
}

private fun printableAscii(b: ByteArray): String? {
    val s = String(b, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
    return s.takeIf { it.isNotEmpty() && it.all { c -> c in ' '..'~' } }
}

/** 0 = GSM default alphabet (unpacked ASCII-compatible on this modem), 1 = UCS-2. */
private fun decodeName(enc: Int, b: ByteArray): String? {
    if (b.isEmpty()) return null
    return when (enc) {
        1 -> String(b, Charsets.UTF_16BE).trim().takeIf { it.isNotEmpty() }
        0 -> printableAscii(b)
        else -> null
    }
}
