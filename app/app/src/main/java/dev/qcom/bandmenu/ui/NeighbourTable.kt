package dev.qcom.bandmenu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import dev.qcom.bandmenu.CellLockState
import dev.qcom.bandmenu.network.LockRequest
import dev.qcom.bandmenu.network.LockRole
import dev.qcom.bandmenu.network.LockTarget
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The neighbour table: Band | PCI | EARFCN | RSRP bar | RSRQ bar, headers once,
 * rows already sorted, best RSRQ first. The Network page and the Cell-Lock
 * page's table both use it, so a neighbour looks the same on either page.
 * Serving-cell rows (the Cell-Lock table's first rows) show PCI and channel
 * in blue.
 * With [onToggle] a row is the lock control for its cell: tapping it locks (or,
 * already locked, releases), and the locked one is marked.
 */
@Composable
internal fun NeighbourTable(
    rows: List<LockTarget>,
    lockedOf: (LockTarget) -> Boolean = { false },
    onToggle: ((LockTarget) -> Unit)? = null,
    /** Taller rows with bigger values: the Cell-Lock page's list is tapped,
     *  the Network page's is read. */
    roomy: Boolean = false
) {
    if (rows.isEmpty()) return
    val first = rows.first()
    val metricLabels = first.metrics.take(2).map { it.label }
    val grey = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val rowPadding = if (roomy) 10.dp else 2.dp
    val valueStyle = if (roomy) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.body2
    val idStyle = if (roomy) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.body2

    @Composable
    fun cols(
        id: @Composable () -> Unit, band: @Composable () -> Unit, ch: @Composable () -> Unit,
        metrics: List<@Composable () -> Unit>, modifier: Modifier = Modifier
    ) {
        Row(
            modifier.fillMaxWidth().padding(vertical = rowPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(0.7f), Alignment.CenterStart) { band() }
            Box(Modifier.weight(0.75f), Alignment.CenterStart) { id() }
            Box(Modifier.weight(0.95f), Alignment.CenterStart) { ch() }
            metrics.forEach { m -> Box(Modifier.weight(1.25f).padding(horizontal = 3.dp), Alignment.Center) { m() } }
            repeat(2 - metrics.size) { Spacer(Modifier.weight(1.25f)) }
        }
    }

    @Composable
    fun head(t: String) = Text(t, style = MiuixTheme.textStyles.footnote1, color = grey, maxLines = 1)

    cols({ head(first.idLabel) }, { head("Band") }, { head(first.channelLabel) }, metricLabels.map { l -> @Composable { head(l) } })
    rows.forEach { t ->
        val locked = lockedOf(t)
        val serving = t.role == LockRole.SERVING
        val servingColor = MiuixTheme.colorScheme.primary
        cols(
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (locked) {
                        Icon(
                            imageVector = MiuixIcons.Lock,
                            contentDescription = "Locked",
                            tint = LockGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        t.id,
                        style = idStyle,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = if (locked) LockGreen else if (serving) servingColor else MiuixTheme.colorScheme.onBackground,
                        maxLines = 1
                    )
                }
            },
            {
                Text(
                    t.band?.label ?: "\u2013",
                    style = valueStyle,
                    color = if (t.band == null) grey else bandColor(t.band.label) ?: MiuixTheme.colorScheme.onBackground,
                    maxLines = 1
                )
            },
            {
                Text(t.channel.toString(), style = valueStyle, maxLines = 1,
                    color = if (serving) servingColor else MiuixTheme.colorScheme.onBackground)
            },
            t.metrics.take(2).map { m -> @Composable {
                CellValueBar(m.value, m.kind, rat = m.rat, style = valueStyle)
            } },
            modifier = if (onToggle == null) Modifier else Modifier.clickable { onToggle(t) }
        )
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

/** This row is the cell the modem is currently pinned to. */
internal fun LockTarget.isLockedIn(state: CellLockState?): Boolean = when (val l = lock) {
    is LockRequest.LteCell ->
        state?.lte?.locks?.any { it.earfcn == l.earfcn.toInt() && it.pci == l.pci } == true
    is LockRequest.NrCell ->
        state?.nr?.pciLock?.let { it.arfcn == l.arfcn.toInt() && it.pci == l.pci } == true
    is LockRequest.NrFrequency -> state?.nr?.let { nr ->
        nr.arfcnLock?.any { it.arfcn == l.arfcn.toInt() } == true ||
            nr.pciLock?.arfcn == l.arfcn.toInt()
    } == true
    null -> false
}
