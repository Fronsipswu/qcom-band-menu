package dev.qcom.bandmenu.network

/*
 * What the Cell-Lock page's Live view shows: the cell the modem is on and every
 * neighbour it reports, each with the lock command that pins it.
 *
 * The modem's own vocabulary is kept: an LTE neighbour carries its PCI (so it
 * gets the full cell lock), an NR neighbour is reported as a frequency only
 * (QMI has no NR neighbour PCI), and WCDMA/GSM neighbours have no lock command
 * at all. Pure data, built from one monitoring round, so it's unit-tested.
 */

/** The modem command that pins one live row. */
sealed interface LockRequest {
    /** LTE cell lock: EARFCN + PCI. */
    data class LteCell(val earfcn: Long, val pci: Int) : LockRequest

    /** NR cell lock: NR-ARFCN, PCI, SCS and band. */
    data class NrCell(val arfcn: Long, val pci: Int, val scsKhz: Int, val band: Int) : LockRequest

    /** NR frequency lock: NR-ARFCN + SCS, no PCI (neighbours report none). */
    data class NrFrequency(val arfcn: Long, val scsKhz: Int) : LockRequest
}

/** The cell the modem is on, or a neighbour it measured. */
enum class LockRole { SERVING, NEIGHBOUR }

/**
 * One row of the Live list.
 *
 * [id] is the cell's own identity on its RAT ([idLabel] names it: PCI, PSC,
 * BSIC) and is "—" for an NR neighbour frequency, which has none. [metrics]
 * are empty when the modem reports no measurement (NR neighbour frequencies).
 * [lock] is null when the modem has no lock command for the RAT, with [note]
 * saying so; [scsKhz] is set for NR rows, and its assumption is if the modem
 * didn't report it.
 */
data class LockTarget(
    val rat: Rat,
    val role: LockRole,
    val idLabel: String,
    val id: String,
    val channelLabel: String,
    val channel: Long,
    val band: Band?,
    val scsKhz: Int? = null,
    val metrics: List<Metric> = emptyList(),
    val lock: LockRequest? = null,
    val note: String? = null,
    /** NR (DIAG) neighbour rows: the SSB beam index. */
    val beam: Int? = null
) {
    /** Stable identity of the row across rounds (for list keys and tests). */
    val key: String get() = "$rat/$channel/$id/${beam ?: ""}"
}

/**
 * A Network page neighbour row as a live row: the same cell, with the lock that
 * pins it (LTE neighbours report a PCI, so they get the cell lock; the other
 * RATs have no lock command, which [note] says).
 */
fun Neighbor.asLockTarget(role: LockRole = LockRole.NEIGHBOUR): LockTarget {
    val pci = id.toIntOrNull()
    val lock = if (rat == Rat.LTE && pci != null) LockRequest.LteCell(channel, pci) else null
    return LockTarget(
        rat = rat, role = role,
        idLabel = idLabel, id = id,
        channelLabel = channelLabel, channel = channel,
        band = band, metrics = metrics, lock = lock, beam = beam,
        note = when {
            lock != null -> null
            rat == Rat.LTE -> "No PCI reported"
            else -> "${rat.label} cell lock is not supported"
        }
    )
}

/**
 * The serving cells as lock rows, in the carrier tables' order: the primary
 * first (LTE PCell, SA's NR PCell, NSA's MCG), then its SCells (NSA: the LTE
 * SCells, the SCG, the NR SCells). A row that is the snapshot's own primary
 * target keeps that target, whose band and SCS come from more sources; the
 * others lock like a cell of their RAT. Without carrier tables: the primary
 * targets alone.
 */
fun servingLockTargets(snap: NetworkSnapshot): List<LockTarget> {
    val primaries = snap.lockTargets.filter { it.role == LockRole.SERVING }
    val carriers = snap.endc?.carriers ?: snap.carrierGroups.filter { it.table }.flatMap { it.carriers }
    if (carriers.isEmpty()) return primaries
    return carriers.mapNotNull { it.asServingTarget(primaries) }
}

/**
 * An NR-ARFCN the modem reported (the Cell-Lock page's NR-ARFCN list): the
 * PCIs seen on it, and its band and SCS as DIAG's serving-cell data gives
 * them (null without DIAG or for a neighbour frequency: QMI only has the
 * NR-ARFCN, whose band is ambiguous where bands overlap).
 */
data class SeenNrArfcn(val arfcn: Long, val pcis: List<Int> = emptyList(), val band: Int? = null, val scsKhz: Int? = null)

/** The NR-ARFCNs of a reading: serving carriers and neighbours (QMI's
 *  frequencies, DIAG's cells), each with its PCIs, serving first. */
fun seenNrArfcns(snap: NetworkSnapshot): List<SeenNrArfcn> {
    val carriers = snap.endc?.carriers ?: snap.carrierGroups.filter { it.table }.flatMap { it.carriers }
    val diag = carriers.mapNotNull { c -> c.nr?.let { n -> (n.dlArfcn ?: c.channel.substringBefore(' ').toLongOrNull())?.let { it to n } } }
    return (servingLockTargets(snap) + snap.lockTargets).filter { it.rat == Rat.NR }.groupBy { it.channel }
        .map { (arfcn, rows) ->
            val d = diag.firstOrNull { it.first == arfcn }?.second
            SeenNrArfcn(
                arfcn,
                pcis = rows.mapNotNull { it.id.toIntOrNull() }.distinct(),
                band = d?.band?.takeIf { it > 0 && nrBandFitsArfcn(it, arfcn) },
                scsKhz = d?.scsKhz?.takeIf { it > 0 }
            )
        }
}

/** How many NR-ARFCNs or EARFCNs the Cell-Lock lists keep, and PCIs per
 *  channel: on the move new ones keep coming, and the least recently seen
 *  are dropped. */
const val SEEN_MAX = 10

/** [old] (least recently seen first) after a reading's [new] values (its
 *  most important first: serving cells, then neighbours): the values seen
 *  again move to the end, and beyond [SEEN_MAX] the oldest are dropped. */
private fun <T> recentFirstDropped(old: List<T>, new: List<T>): List<T> =
    (old - new.toSet() + new.asReversed()).takeLast(SEEN_MAX)

/** [old] plus a reading's NR-ARFCNs, at most [SEEN_MAX] (least recently seen
 *  first; see [recentFirstDropped]), each with at most [SEEN_MAX] PCIs. A
 *  known band or SCS is kept and a new one fills a gap. */
fun mergeSeenNrArfcns(old: List<SeenNrArfcn>, new: List<SeenNrArfcn>): List<SeenNrArfcn> {
    val byArfcn = LinkedHashMap<Long, SeenNrArfcn>()
    old.forEach { byArfcn[it.arfcn] = it }
    new.asReversed().forEach { n ->
        val o = byArfcn.remove(n.arfcn)
        byArfcn[n.arfcn] = SeenNrArfcn(
            n.arfcn, recentFirstDropped(o?.pcis.orEmpty(), n.pcis), o?.band ?: n.band, o?.scsKhz ?: n.scsKhz
        )
    }
    return byArfcn.values.toList().takeLast(SEEN_MAX)
}

/** An LTE EARFCN the modem reported (the Cell-Lock page's EARFCN list) and
 *  the PCIs seen on it. */
data class SeenEarfcn(val earfcn: Long, val pcis: List<Int> = emptyList(), val band: Int? = null)

/** The EARFCNs of a reading: serving carriers and neighbours, each with its
 *  PCIs, serving first. */
fun seenEarfcns(snap: NetworkSnapshot): List<SeenEarfcn> =
    (servingLockTargets(snap) + snap.lockTargets).filter { it.rat == Rat.LTE }.groupBy { it.channel }
        .map { (earfcn, rows) ->
            SeenEarfcn(earfcn, rows.mapNotNull { it.id.toIntOrNull() }.distinct(), rows.firstNotNullOfOrNull { it.band?.number })
        }

/** [old] plus a reading's EARFCNs, kept as [mergeSeenNrArfcns] keeps
 *  NR-ARFCNs. */
fun mergeSeenEarfcns(old: List<SeenEarfcn>, new: List<SeenEarfcn>): List<SeenEarfcn> {
    val byEarfcn = LinkedHashMap<Long, SeenEarfcn>()
    old.forEach { byEarfcn[it.earfcn] = it }
    new.asReversed().forEach { n ->
        val o = byEarfcn.remove(n.earfcn)
        byEarfcn[n.earfcn] = SeenEarfcn(n.earfcn, recentFirstDropped(o?.pcis.orEmpty(), n.pcis), o?.band ?: n.band)
    }
    return byEarfcn.values.toList().takeLast(SEEN_MAX)
}

/** The band an NR-ARFCN is in when that's certain: the only candidate (among
 *  the modem's bands), or the band DIAG resolved for it. Null where bands
 *  overlap and nothing resolved it. */
fun nrLockBand(arfcn: Long, seen: List<SeenNrArfcn>, supported: Set<Int> = emptySet()): Int? =
    seen.firstOrNull { it.arfcn == arfcn }?.band ?: nrBandsForArfcn(arfcn, supported).singleOrNull()

/** The SCS for an NR-ARFCN: the serving cell's own when DIAG saw it,
 *  otherwise its band's usual one (TDD 30 kHz, FDD 15, FR2 120); where the
 *  candidate bands disagree, the most deployed band's. Null off the table. */
fun nrLockScs(arfcn: Long, seen: List<SeenNrArfcn>, supported: Set<Int> = emptySet()): Int? {
    seen.firstOrNull { it.arfcn == arfcn }?.scsKhz?.let { return it }
    nrLockBand(arfcn, seen, supported)?.let { return nrDefaultScsKhz(it) }
    val scs = nrBandsForArfcn(arfcn, supported).map(::nrDefaultScsKhz).distinct()
    return scs.singleOrNull() ?: nrBandForArfcn(arfcn, supported)?.let(::nrDefaultScsKhz)
}

private fun Carrier.asServingTarget(primaries: List<LockTarget>): LockTarget? {
    val ch = channel.substringBefore(' ').toLongOrNull() ?: return null
    val isNr = nr != null || band?.startsWith("n") == true
    val rat = if (isNr) Rat.NR else Rat.LTE
    primaries.firstOrNull { it.rat == rat && it.channel == ch && it.id == pci?.toString() }?.let { return it }
    val bandNumber = nr?.band?.takeIf { it > 0 } ?: band?.drop(1)?.toIntOrNull()
    val bandOf = bandNumber?.let { Band(rat, it, (if (isNr) "n" else "B") + it) }
    val scs = if (isNr) nr?.scsKhz ?: nrDefaultScsKhz(bandNumber) else null
    val lock = when {
        !isNr -> pci?.let { LockRequest.LteCell(ch, it) }
        pci != null && bandNumber != null -> LockRequest.NrCell(ch, pci, scs!!, bandNumber)
        else -> LockRequest.NrFrequency(ch, scs!!)
    }
    return LockTarget(
        rat = rat, role = LockRole.SERVING,
        idLabel = "PCI", id = pci?.toString() ?: "—",
        channelLabel = if (isNr) "NARFCN" else "EARFCN", channel = ch,
        band = bandOf, scsKhz = scs,
        // Neighbour-table labels: the table takes its headers from the first row.
        metrics = listOf(
            Metric("RSRP", MetricKind.RSRP, rat, rsrp, "dBm"),
            Metric("RSRQ", MetricKind.RSRQ, rat, rsrq, "dB")
        ),
        lock = lock,
        note = if (lock == null) "No PCI reported" else null
    )
}
