package dev.qcom.bandmenu.network

/**
 * Keeps a table column's transmit port count steady while the carrier idles:
 * an NR column's CSI-RS ports, an LTE column's eNB Tx antennas.
 *
 * NR: with traffic (an RX count is reported) the live port count is
 * authoritative, 1 included, and becomes the column's confirmed value. Without
 * traffic the modem's count can drop to 1 for a moment: the column then shows
 * its confirmed value, or before any traffic the largest count seen.
 *
 * LTE: the Tx count comes only with PDSCH traffic and is gone while idle, so
 * the last one reported stays (shown as "4×-" without an RX count).
 *
 * A column is one table column (table title + role); a different PCI or band,
 * or the column going away, starts over. One instance per SIM, live rounds only.
 */
class PortHold {
    private data class Held(val pci: Int, val band: String?, val ports: Int?, val confirmed: Boolean)

    private var held = emptyMap<String, Held>()

    fun apply(snap: NetworkSnapshot): NetworkSnapshot {
        val next = HashMap<String, Held>()
        // The separate tables and NSA's combined table are separate columns.
        val result = snap.copy(
            carrierGroups = snap.carrierGroups.map { hold(it, next) },
            endc = snap.endc?.let { hold(it, next) }
        )
        held = next
        return result
    }

    private fun hold(g: CarrierGroup, next: MutableMap<String, Held>) = g.copy(carriers = g.carriers.map { c ->
        val key = "${g.title}|${c.role}"
        val nr = c.nr
        val lte = c.diag
        val pci = nr?.pci ?: lte?.pci ?: c.pci ?: return@map c
        // Same cell: same PCI, and the same band where both rounds name one.
        val prev = held[key]?.takeIf { it.pci == pci && (it.band == null || c.band == null || it.band == c.band) }
        val band = c.band ?: prev?.band
        when {
            nr != null -> {
                val h = when {
                    nr.rxAntennas != null -> Held(pci, band, nr.csiPorts, confirmed = nr.csiPorts != null)
                    prev?.confirmed == true -> prev.copy(band = band)
                    else -> Held(pci, band, listOfNotNull(prev?.ports, nr.csiPorts).maxOrNull(), confirmed = false)
                }
                next[key] = h
                if (h.ports == nr.csiPorts) c else c.copy(nr = nr.copy(csiPorts = h.ports))
            }
            lte != null -> {
                val ports = lte.txAntennas ?: prev?.ports
                next[key] = Held(pci, band, ports, confirmed = lte.txAntennas != null)
                if (ports == lte.txAntennas) c else c.copy(diag = lte.copy(txAntennas = ports))
            }
            else -> c
        }
    })
}
