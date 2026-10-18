package dev.qcom.bandmenu.network

/**
 * Keeps a carrier's transmit port count (and LTE transmission mode) steady
 * across windows where the modem does not repeat them: an NR carrier's CSI-RS
 * ports, an LTE carrier's eNB Tx antennas and transmission mode (B126).
 *
 * NR: with traffic (an RX count is reported) the live port count is
 * authoritative, 1 included, and becomes the carrier's confirmed value. Without
 * traffic the modem's count can drop to 1 for a moment: the carrier then shows
 * its confirmed value, or before any traffic the largest count seen.
 *
 * LTE: B126 (Tx antenna count and transmission mode) arrives on configuration
 * updates and with PDSCH traffic rather than every window, so the last values
 * reported stay while the carrier remains present.
 *
 * Identity is table title + RAT + PCI + channel (SSB NR-ARFCN or LTE EARFCN)
 * with a compatible band; a different PCI, channel, or band, or the carrier
 * going away, starts over. One instance per SIM, live rounds only.
 */
class PortHold {
    private data class Key(val table: String, val nr: Boolean, val pci: Int, val channel: Long?)
    private data class Held(val band: String?, val ports: Int?, val txMode: Int? = null, val confirmed: Boolean)

    private var held = emptyMap<Key, Held>()

    fun apply(snap: NetworkSnapshot): NetworkSnapshot {
        if (snap.mode == ServiceMode.UNKNOWN) return snap
        val next = HashMap<Key, Held>()
        // The separate tables and NSA's combined table are tracked independently.
        val result = snap.copy(
            carrierGroups = snap.carrierGroups.map { hold(it, next) },
            endc = snap.endc?.let { hold(it, next) }
        )
        held = next
        return result
    }

    private fun hold(g: CarrierGroup, next: MutableMap<Key, Held>) = g.copy(carriers = g.carriers.map { c ->
        val nr = c.nr
        val lte = c.diag
        val pci = nr?.pci ?: lte?.pci ?: c.pci ?: return@map c
        val channel = nr?.arfcn ?: lte?.earfcn ?: c.channel.substringBefore(' ').toLongOrNull()
        val key = Key(g.title, nr != null, pci, channel)
        // Same cell: same RAT, PCI, and channel, and the same band where both rounds name one.
        val prev = held[key]?.takeIf { it.band == null || c.band == null || it.band == c.band }
        val band = c.band ?: prev?.band
        when {
            nr != null -> {
                val h = when {
                    nr.rxAntennas != null -> Held(band, nr.csiPorts, confirmed = nr.csiPorts != null)
                    prev?.confirmed == true -> prev.copy(band = band)
                    else -> Held(band, listOfNotNull(prev?.ports, nr.csiPorts).maxOrNull(), confirmed = false)
                }
                next[key] = h
                if (h.ports == nr.csiPorts) c else c.copy(nr = nr.copy(csiPorts = h.ports))
            }
            lte != null -> {
                val ports = lte.txAntennas ?: prev?.ports
                val txMode = lte.transmissionMode ?: prev?.txMode
                next[key] = Held(band, ports, txMode, confirmed = lte.txAntennas != null)
                if (ports == lte.txAntennas && txMode == lte.transmissionMode) c
                else c.copy(diag = lte.copy(txAntennas = ports, transmissionMode = txMode))
            }
            else -> c
        }
    })
}
