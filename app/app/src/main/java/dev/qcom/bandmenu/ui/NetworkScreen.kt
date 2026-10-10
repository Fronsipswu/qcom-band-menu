package dev.qcom.bandmenu.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import dev.qcom.bandmenu.network.AntennaGroup
import dev.qcom.bandmenu.network.CarrierGroup
import dev.qcom.bandmenu.network.CellCard
import dev.qcom.bandmenu.network.Field
import dev.qcom.bandmenu.network.Metric
import dev.qcom.bandmenu.network.MetricKind
import dev.qcom.bandmenu.network.Neighbor
import dev.qcom.bandmenu.network.NetworkMonitor
import dev.qcom.bandmenu.network.NetworkSnapshot
import dev.qcom.bandmenu.network.Rat
import dev.qcom.bandmenu.network.ServiceMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun NetworkScreen(
    monitor: NetworkMonitor,
    /** True while the pager is settled on this page. */
    isActive: Boolean,
    simLabels: List<String> = listOf("SIM 1", "SIM 2"),
    sim2Available: Boolean = true,
    backdrop: Backdrop? = null
) {
    val state by monitor.state.collectAsState()
    val density = LocalDensity.current
    val navInset = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val navbarSpace = 64.dp + 16.dp + navInset
    val topBarInset = WindowInsets.systemBars.only(WindowInsetsSides.Top).asPaddingValues(density).calculateTopPadding()
    val topBarHeight = topBarInset + TopAppBarDefaults.CollapsedHeight
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(isActive) { monitor.setVisible(isActive) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> monitor.setResumed(true)
                Lifecycle.Event.ON_PAUSE -> monitor.setResumed(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        monitor.setResumed(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            monitor.setVisible(false)
        }
    }

    // Ticks the "updated N s ago" label.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(isActive) {
        while (isActive) { now = SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(1000) }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val screenBackdrop = rememberLayerBackdrop(onDraw = { drawRect(surfaceColor); drawContent() })

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = { monitor.refreshOnce() },
            modifier = Modifier.fillMaxSize().layerBackdrop(screenBackdrop),
            contentPadding = PaddingValues(top = topBarHeight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = topBarHeight)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navbarSpace + 16.dp)
            ) {
                TabRowWithContour(
                    tabs = simLabels,
                    selectedTabIndex = state.sim - 1,
                    onTabSelected = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        monitor.selectSim(it + 1)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                val snap = state.snapshot
                when {
                    state.sim == 2 && !sim2Available -> Sim2UnavailableNote()
                    snap == null && state.error == null -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                    snap == null -> NoticeCard(state.error!!)
                    else -> NetworkContent(snap, state, now)
                }
            }
        }

        if (backdrop != null) {
            Box(
                modifier = Modifier.fillMaxWidth().drawPlainBackdrop(
                    backdrop = screenBackdrop,
                    shape = { RectangleShape },
                    effects = { blur(8f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.5f)) }
                )
            ) { SmallTopAppBar(title = "Network", color = Color.Transparent) }
        } else {
            SmallTopAppBar(title = "Network")
        }
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                monitor.setFrozen(!state.frozen)
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = topBarInset + 8.dp, end = 8.dp)
        ) {
            Icon(
                imageVector = if (state.frozen) MiuixIcons.Lock else MiuixIcons.Unlock,
                contentDescription = if (state.frozen) "Resume live monitoring" else "Freeze monitoring",
                tint = if (state.frozen) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun NetworkContent(snap: NetworkSnapshot, state: NetworkMonitor.State, now: Long) {
    SummaryCard(snap, state, now)
    state.error?.let { NoticeCard("Last read failed: $it. Showing the previous reading.") }

    snap.cells.forEach { CellSection(it) }
    if (snap.cells.isEmpty() && snap.mode != ServiceMode.NO_SERVICE && snap.mode != ServiceMode.LIMITED) {
        NoticeCard("No serving-cell details were reported.")
    }
    snap.carrierGroups.forEach { CarrierSection(it) }
    if (snap.antennas.isNotEmpty()) {
        Expandable("Antennas (${snap.antennas.sumOf { a -> a.chains.count { it.tuned } }} tuned)") {
            snap.antennas.forEach { AntennaRows(it) }
        }
    }
    if (snap.neighbors.isNotEmpty()) {
        Expandable("Neighbour cells (${snap.neighbors.size})") { NeighborRows(snap.neighbors) }
    }
    if (snap.failedReads.isNotEmpty()) {
        Text(
            "Not available this round: ${snap.failedReads.joinToString()}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(snap: NetworkSnapshot, state: NetworkMonitor.State, now: Long) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snap.mode.generation.isNotEmpty()) {
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(snap.mode.generation, color = MiuixTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(snap.mode.label, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            val operator = listOfNotNull(state.operatorName, snap.plmn?.toString()).joinToString(" · ")
            if (operator.isNotEmpty()) Text(operator, style = MiuixTheme.textStyles.body1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                snap.roaming?.let { Chip(if (it) "Roaming" else "Home network", highlight = it) }
                snap.rrc.forEach { Chip("${it.label}: ${it.value}", highlight = it.value == "Connected") }
            }
            Spacer(Modifier.height(8.dp))
            val age = ((now - snap.takenAtElapsedMs) / 1000).coerceAtLeast(0)
            val status = if (state.frozen) {
                val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - snap.takenAtElapsedMs)
                "Frozen · reading from ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(wall))}"
            } else if (age <= 1) "Live" else "Live · updated $age s ago"
            Text(status, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun Chip(text: String, highlight: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (highlight) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text, style = MiuixTheme.textStyles.footnote1,
            color = if (highlight) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun CellSection(cell: CellCard) {
    SmallTitle(cell.title, modifier = Modifier.padding(top = 6.dp))
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            cell.metrics.forEach { SignalBar(it, Modifier.padding(vertical = 5.dp)) }
            if (cell.metrics.isNotEmpty() && cell.fields.isNotEmpty()) Spacer(Modifier.height(10.dp))
            FieldGrid(cell.fields)
        }
    }
}

/** Two-column label/value grid; long values (ECI, DL/UL) take a full row. */
@Composable
private fun FieldGrid(fields: List<Field>) {
    val wide = setOf("ECI", "DL / UL", "Cell ID", "Timing advance", "NCI", "BSIC")
    val rows = buildList<List<Field>> {
        var pending: Field? = null
        for (f in fields) {
            if (f.label in wide || f.value.length > 14) {
                pending?.let { add(listOf(it)); pending = null }
                add(listOf(f))
            } else if (pending == null) pending = f
            else { add(listOf(pending!!, f)); pending = null }
        }
        pending?.let { add(listOf(it)) }
    }
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            row.forEach { f ->
                Column(Modifier.weight(1f)) {
                    Text(f.label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(f.value, style = MiuixTheme.textStyles.body1)
                }
            }
            if (row.size == 1 && row[0].label !in wide && row[0].value.length <= 14) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CarrierSection(group: CarrierGroup) {
    SmallTitle(group.title, modifier = Modifier.padding(top = 6.dp))
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(group.summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(8.dp))
            group.carriers.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.role, modifier = Modifier.width(76.dp), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 1)
                    c.band?.let { Text(it, modifier = Modifier.width(48.dp), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold) }
                    Column(Modifier.weight(1f)) {
                        Text(c.channel, style = MiuixTheme.textStyles.body2)
                        val sub = listOfNotNull(c.pci?.let { "PCI $it" }, c.bandwidthMhz?.let { "${formatMetric(it)} MHz" }).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    c.state?.let { Chip(it, highlight = it == "Active" || it == "Primary") }
                }
            }
            group.note?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title.substringBefore(" (")) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            val angle by animateFloatAsState(if (open) 270f else 90f, label = "chevron")
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.rotate(angle)
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}

@Composable
private fun AntennaRows(a: AntennaGroup) {
    Text("${a.rat.label} receiver chains", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
    a.chains.forEach { c ->
        if (!c.tuned) {
            Text("RX${c.chain}: not tuned", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 3.dp))
            return@forEach
        }
        // RSRP (LTE/NR) or RSCP (WCDMA) gets a bar; RX power is the raw level.
        val m = when {
            c.rsrp != null -> Metric("RX${c.chain}", MetricKind.RSRP, a.rat, c.rsrp, "dBm")
            c.rscp != null -> Metric("RX${c.chain}", MetricKind.RSCP, a.rat, c.rscp, "dBm")
            else -> Metric("RX${c.chain}", MetricKind.RSSI, a.rat, c.rxPower, "dBm")
        }
        SignalBar(m, Modifier.padding(top = 4.dp))
        c.rxPower?.let {
            Text("RX power ${formatMetric(it)} dBm" + (c.ecio?.let { e -> " · Ec/Io ${formatMetric(e)} dB" } ?: ""),
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 74.dp, bottom = 2.dp))
        }
    }
    val tx = when {
        a.txPower != null -> "TX power ${formatMetric(a.txPower)} dBm"
        a.inTraffic == false -> "TX: not transmitting"
        else -> null
    }
    tx?.let { Text(it, style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun NeighborRows(neighbors: List<Neighbor>) {
    neighbors.groupBy { it.channelLabel to it.channel }.forEach { (key, cells) ->
        Text("${key.first} ${key.second}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        cells.forEach { n ->
            n.metrics.firstOrNull()?.let { m ->
                SignalBar(m.copy(label = "${n.idLabel} ${n.id}"), Modifier.padding(vertical = 3.dp), labelWidth = 72.dp)
            }
            n.metrics.drop(1).takeIf { it.isNotEmpty() }?.let { rest ->
                Text(rest.joinToString(" · ") { "${it.label} ${it.value?.let(::formatMetric) ?: "—"} ${it.unit}" },
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 82.dp))
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text, modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

