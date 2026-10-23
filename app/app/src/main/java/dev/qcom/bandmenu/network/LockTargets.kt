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
