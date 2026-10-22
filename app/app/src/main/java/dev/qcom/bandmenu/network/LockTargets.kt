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
    val note: String? = null
) {
    /** Stable identity of the row across rounds (for list keys and tests). */
    val key: String get() = "$rat/$channel/$id"
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
        band = band, metrics = metrics, lock = lock,
        note = when {
            lock != null -> null
            rat == Rat.LTE -> "No PCI reported"
            else -> "${rat.label} cell lock is not supported"
        }
    )
}
