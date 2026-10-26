package dev.qcom.bandmenu.ui

import androidx.compose.ui.res.stringResource
import dev.qcom.bandmenu.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import dev.qcom.bandmenu.CellLockState
import dev.qcom.bandmenu.network.LockRequest
import dev.qcom.bandmenu.network.LockRole
import dev.qcom.bandmenu.network.LockTarget
import dev.qcom.bandmenu.network.Rat
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Which part of a Cell-Lock row was tapped: its PCI, its channel
 *  (EARFCN/NARFCN), or the rest of the row. */
internal enum class CellTap { ID, CHANNEL, ROW }

/**
 * The neighbour table: Band | PCI | EARFCN | RSRP bar | RSRQ bar, rows already
 * sorted. A header heads each run of rows of one RAT, so mixed lists (NSA's
 * LTE then NR rows) are labelled right; a run with NR beams gets a Beam column
 * after the PCI (Band | PCI | Beam | NARFCN | RSRP | RSRQ, as NSG lists them). The Network page and the Cell-Lock
 * page's table both use it, so a neighbour looks the same on either page.
 * Serving-cell rows (the Cell-Lock table's first rows) show PCI and channel
 * in blue; the locked cell's are green, bracketed to show what the lock pins
 * ("[269 156510]" for a cell, "269 [156510]" for a frequency).
 * With [onTap] a row is a lock control and reports the tapped column.
 */
@Composable
internal fun NeighbourTable(
    rows: List<LockTarget>,
    lockOf: (LockTarget) -> LockKind = { LockKind.NONE },
    onTap: ((LockTarget, CellTap) -> Unit)? = null,
    /** Taller rows with bigger values: the Cell-Lock page's list is tapped,
     *  the Network page's is read. */
    roomy: Boolean = false
) {
    if (rows.isEmpty()) return
    val grey = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val rowPadding = if (roomy) 10.dp else 2.dp
    val valueStyle = if (roomy) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.body2
    val idStyle = if (roomy) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.body2

    @Composable
    fun cols(
        id: @Composable () -> Unit, band: @Composable () -> Unit, beam: (@Composable () -> Unit)?, ch: @Composable () -> Unit,
        metrics: List<@Composable () -> Unit>, modifier: Modifier = Modifier,
        idModifier: Modifier = Modifier, chModifier: Modifier = Modifier
    ) {
        Row(
            modifier.fillMaxWidth().padding(vertical = rowPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Six columns with a Beam: shorter bars, so a six-digit NR-ARFCN
            // and the Beam header fit; each text column its text plus the
            // same gap.
            val bar = if (beam != null) 1.0f else 1.15f
            Box(Modifier.weight(if (beam != null) 0.67f else 0.7f), Alignment.CenterStart) { band() }
            Box(Modifier.weight(if (beam != null) 0.58f else 0.75f).then(idModifier), Alignment.CenterStart) { id() }
            // A beam index is one or two digits: centred under the header's
            // word (an invisible copy sizes the cell), left like the others.
            if (beam != null) Box(Modifier.weight(0.72f), Alignment.CenterStart) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Beam", style = MiuixTheme.textStyles.footnote1, maxLines = 1, modifier = Modifier.alpha(0f))
                    beam()
                }
            }
            Box(Modifier.weight(if (beam != null) 1.04f else 1.15f).then(chModifier), Alignment.CenterStart) { ch() }
            metrics.forEach { m -> Box(Modifier.weight(bar).padding(horizontal = 3.dp), Alignment.Center) { m() } }
            repeat(2 - metrics.size) { Spacer(Modifier.weight(bar)) }
        }
    }

    @Composable
    fun head(t: String) = Text(t, style = MiuixTheme.textStyles.footnote1, color = grey, maxLines = 1)

    // Runs of one RAT, each with its own header.
    val runs = mutableListOf<MutableList<LockTarget>>()
    rows.forEach { t -> if (runs.lastOrNull()?.first()?.rat == t.rat) runs.last().add(t) else runs.add(mutableListOf(t)) }
    runs.forEachIndexed { index, run ->
        val first = run.first()
        val beams = run.any { it.beam != null }
        if (index > 0) Spacer(Modifier.height(6.dp))
        cols({ head(first.idLabel) }, { head(stringResource(R.string.display_band)) }, if (beams) ({ head(stringResource(R.string.display_beam)) }) else null, { head(first.channelLabel) },
            first.metrics.take(2).map { m -> @Composable { head(m.label) } })
        run.forEach { t ->
            val kind = lockOf(t)
            val locked = kind != LockKind.NONE
            val idColor = when {
                locked -> LockGreen
                t.role == LockRole.SERVING -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onBackground
            }
            // The brackets show what the lock pins, as the manual fields read:
            // a cell lock wraps both ("[269 156510]"), a frequency lock only
            // the NARFCN ("269 [156510]").
            val (idText, chText) = lockTexts(kind, t.id, t.channel)
            cols(
                {
                    Text(
                        idText,
                        style = idStyle,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = idColor,
                        maxLines = 1
                    )
                },
                {
                    Text(
                        t.band?.label ?: "\u2013",
                        style = valueStyle,
                        color = if (t.band == null) grey else bandColor(t.band.label) ?: MiuixTheme.colorScheme.onBackground,
                        maxLines = 1
                    )
                },
                if (beams) ({ Text(t.beam?.toString() ?: "\u2013", style = valueStyle, maxLines = 1,
                    color = if (t.beam == null) grey else MiuixTheme.colorScheme.onBackground) }) else null,
                {
                    Text(chText, style = valueStyle, maxLines = 1, color = idColor)
                },
                t.metrics.take(2).map { m -> @Composable {
                    CellValueBar(m.value, m.kind, rat = m.rat, style = valueStyle)
                } },
                modifier = if (onTap == null) Modifier else Modifier.clickable { onTap(t, CellTap.ROW) },
                idModifier = if (onTap == null) Modifier else Modifier.clickable { onTap(t, CellTap.ID) },
                chModifier = if (onTap == null) Modifier else Modifier.clickable { onTap(t, CellTap.CHANNEL) }
            )
        }
    }
}

internal val LockGreen = Color(0xFF4CAF50)

/**
 * The Cell-Lock field a live row drives and the input string it sends, in the
 * same shapes the Manual fields take (MainActivity parses both):
 * 0 = NR-ARFCN + SCS, 1 = NR cell (ARFCN PCI SCS Band), 4 = LTE cell
 * (EARFCN PCI).
 */
internal fun LockRequest.args(): Pair<Int, String> = when (this) {
    is LockRequest.LteCell -> 4 to "$earfcn $pci"
    is LockRequest.NrCell -> 1 to "$arfcn $pci $scsKhz $band"
    is LockRequest.NrFrequency -> 0 to "$arfcn $scsKhz"
}

/** How a row's cell is pinned: the whole cell (frequency + PCI), only its
 *  frequency, or not at all. */
internal enum class LockKind {
    NONE,

    /** Frequency + PCI (an LTE cell lock, or an NR PCI lock). */
    CELL,

    /** The frequency only (an NR-ARFCN lock): every cell on it is pinned. */
    FREQUENCY
}

/**
 * The PCI and channel as the table shows them: the brackets mark what the
 * lock pins, as the manual lock fields read them. A cell lock wraps both
 * ("[269", "156510]"), a frequency lock only the NARFCN ("269", "[156510]").
 */
internal fun lockTexts(kind: LockKind, id: String, channel: Long): Pair<String, String> = when (kind) {
    LockKind.CELL -> "[$id" to "$channel]"
    LockKind.FREQUENCY -> id to "[$channel]"
    LockKind.NONE -> id to channel.toString()
}

/**
 * How [state] pins this row, matched on the row's own identity rather than on
 * the [LockRequest] the row was built with. That matters once a cell becomes
 * the serving one: the modem then reports it with its PCI and band, so its
 * lock request may change shape (an NR-ARFCN lock row becomes a cell lock),
 * but it is the same locked cell and must stay green.
 */
internal fun LockTarget.lockKindIn(state: CellLockState?): LockKind {
    if (state == null) return LockKind.NONE
    return when (rat) {
        Rat.LTE ->
            if (state.lte.locks.any { it.earfcn == channel.toInt() && it.pci.toString() == id })
                LockKind.CELL else LockKind.NONE
        Rat.NR -> {
            val nr = state.nr
            val ch = channel.toInt()
            val pciLock = nr.pciLock?.takeIf { nr.type != "arfcn" }
            val freqLocks = nr.arfcnLock.orEmpty()
            when {
                // A cell lock wins when the modem names the PCI: an NR-ARFCN
                // lock's cells (each without a PCI, or with one the lock
                // doesn't name) are matched below instead.
                pciLock != null && pciLock.arfcn == ch && pciLock.pci.toString() == id ->
                    LockKind.CELL
                freqLocks.any { it.arfcn == ch } -> LockKind.FREQUENCY
                else -> LockKind.NONE
            }
        }
        else -> LockKind.NONE
    }
}

/** This row is the cell the modem is currently pinned to (or, for a
 *  frequency lock, one of the cells on the locked frequency). */
internal fun LockTarget.isLockedIn(state: CellLockState?): Boolean =
    lockKindIn(state) != LockKind.NONE
