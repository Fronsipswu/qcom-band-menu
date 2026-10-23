package dev.qcom.bandmenu.network

/**
 * Keeps a carrier's PUCCH transmit power across windows without one: PUCCH
 * goes out only with uplink control (HARQ feedback, CSI, scheduling
 * requests), so many windows have none. A primary carrier (P, MCG, SCG)
 * keeps its last value until the next one; an SCell for at most
 * [SCELL_HOLD_MS].
 *
 * Identity is table title + RAT + PCI + channel, as in [PortHold]; a
 * different cell, or the carrier going away, drops the value. One instance
 * per SIM, live rounds only.
 */
class PucchHold {
    private data class Key(val table: String, val nr: Boolean, val pci: Int, val channel: Long?)
    private data class Held(val value: Double, val atMs: Long)

    private var held = emptyMap<Key, Held>()

    fun apply(snap: NetworkSnapshot): NetworkSnapshot {
        if (snap.mode == ServiceMode.UNKNOWN) return snap
        val next = HashMap<Key, Held>()
        val now = snap.takenAtElapsedMs
        val result = snap.copy(
            carrierGroups = snap.carrierGroups.map { hold(it, now, next) },
            endc = snap.endc?.let { hold(it, now, next) }
        )
        held = next
        return result
    }

    private fun hold(g: CarrierGroup, now: Long, next: MutableMap<Key, Held>) = g.copy(carriers = g.carriers.map { c ->
        val nr = c.nr
        val lte = c.diag
        val pci = nr?.pci ?: lte?.pci ?: return@map c
        val key = Key(g.title, nr != null, pci, nr?.arfcn ?: lte?.earfcn)
        val live = nr?.ulPucch ?: lte?.ulPucch
        if (live != null) {
            next[key] = Held(live, now)
            return@map c
        }
        val prev = held[key] ?: return@map c
        val primary = c.role == "P" || c.role == "MCG" || c.role == "SCG"
        if (!primary && now - prev.atMs > SCELL_HOLD_MS) return@map c
        next[key] = prev
        when {
            nr != null -> c.copy(nr = nr.copy(ulPucch = prev.value))
            lte != null -> c.copy(diag = lte.copy(ulPucch = prev.value))
            else -> c
        }
    })

    companion object {
        const val SCELL_HOLD_MS = 3_000L
    }
}
