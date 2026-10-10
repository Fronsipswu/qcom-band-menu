package dev.qcom.bandmenu.ui

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.launch
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import dev.qcom.bandmenu.network.Carrier
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
    /** Extra work on pull-to-refresh (MainActivity re-checks SIM 2's card status). */
    onRefresh: () -> Unit = {},
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

    // SIM pager, as on the Bands page: the pager is the source of truth and
    // the monitor follows its target page. Sized to the current page so a
    // short SIM page leaves no blank scroll space.
    // Without a usable SIM 2 there's one page, so a swipe goes straight on to
    // the neighbouring screen.
    val pagerState = rememberPagerState(initialPage = if (sim2Available) state.sim - 1 else 0,
        pageCount = { if (sim2Available) 2 else 1 })
    val pagerScope = rememberCoroutineScope()
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.targetPage }.collect { monitor.selectSim(it + 1) }
    }
    val pageHeights = remember { mutableStateMapOf<Int, Int>() }
    // At least the visible area below the tabs, so a swipe anywhere on a short
    // page (e.g. "No service") still reaches the SIM pager, not the screen pager.
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val minPageHeight = (windowHeight - topBarHeight - 64.dp - navbarSpace - 16.dp).coerceAtLeast(0.dp)
    val pagerHeight = pageHeights[pagerState.currentPage]?.let { with(density) { it.toDp() } }?.coerceAtLeast(minPageHeight)

    val surfaceColor = MiuixTheme.colorScheme.surface
    val screenBackdrop = rememberLayerBackdrop(onDraw = { drawRect(surfaceColor); drawContent() })

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = { onRefresh(); monitor.refreshOnce() },
            modifier = Modifier.fillMaxSize().layerBackdrop(screenBackdrop),
            contentPadding = PaddingValues(top = topBarHeight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = topBarHeight)
                    .padding(bottom = navbarSpace + 16.dp)
            ) {
                TabRowWithContour(
                    tabs = simLabels,
                    selectedTabIndex = pagerState.targetPage,
                    onTabSelected = {
                        if (it >= pagerState.pageCount) return@TabRowWithContour
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (pagerState.targetPage != it) pagerScope.launch { pagerState.animateScrollToPage(it) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(12.dp))

                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().then(pagerHeight?.let { Modifier.height(it) } ?: Modifier)
                ) { page ->
                    val sim = page + 1
                    // Only the selected SIM is polled; the other page shows
                    // that SIM's own last reading (or a spinner), never this one's.
                    val pageState = if (sim == state.sim) state
                        else state.copy(sim = sim, snapshot = state.lastBySim[sim]?.snapshot,
                            operatorName = state.lastBySim[sim]?.operatorName, error = null)
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            // Natural height, unconstrained by the pager (see pagerHeight).
                            .wrapContentHeight(align = Alignment.Top, unbounded = true)
                            .onSizeChanged { pageHeights[page] = it.height }
                    ) {
                        val snap = pageState.snapshot
                        when {
                            sim == 2 && !sim2Available -> Sim2UnavailableNote()
                            snap == null && pageState.error == null -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                            snap == null -> NoticeCard(pageState.error!!)
                            else -> NetworkContent(snap, pageState)
                        }
                    }
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
private fun NetworkContent(snap: NetworkSnapshot, state: NetworkMonitor.State) {
    // The summary and the serving cell (the LTE MCG in NSA) share one card;
    // any further cell (the NR SCG in NSA) keeps its own card below.
    SmallTitle("Serving cell", modifier = Modifier.padding(top = 6.dp))
    SummaryCard(snap, state, snap.cells.firstOrNull())
    state.error?.let { NoticeCard("Last read failed: $it. Showing the previous reading.") }

    snap.cells.drop(1).forEach { CellSection(it) }
    if (snap.cells.isEmpty() && snap.mode != ServiceMode.NO_SERVICE && snap.mode != ServiceMode.LIMITED) {
        NoticeCard("No serving-cell details were reported.")
    }
    snap.carrierGroups.forEach { CarrierSection(it) }
    if (snap.antennas.isNotEmpty()) {
        Expandable("Per antenna measurements") {
            // Headings only when LTE and NR chains are shown together (NSA).
            val showHeadings = snap.antennas.size > 1
            snap.antennas.forEach { AntennaRows(it, showHeadings) }
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

@Composable
private fun SummaryCard(snap: NetworkSnapshot, state: NetworkMonitor.State, serving: CellCard?) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            // The mode itself is the pill (blue when in service, grey otherwise),
            // with the roaming and RRC chips stacked beside it. The pill takes the
            // stack's height so the two line up.
            val inService = snap.mode.generation.isNotEmpty()
            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp))
                        .background(if (inService) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        snap.modeLabel,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                        color = if (inService) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground
                    )
                }
                val chips = buildList {
                    snap.roaming?.let { add("Roaming: $it" to it) }
                    snap.rrc.forEach { add("${it.label}: ${it.value}" to (it.value == "Connected")) }
                }
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chips.forEach { (text, highlight) -> Chip(text, highlight, compact = true) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val operator = listOfNotNull(state.operatorName, snap.plmn?.toString()).joinToString(" | ")
            if (operator.isNotEmpty()) Text(operator, style = MiuixTheme.textStyles.body2)
            // Serving cell: its bars, then all its fields.
            if (serving != null) {
                Spacer(Modifier.height(10.dp))
                serving.metrics.forEach { SignalBar(it, Modifier.padding(vertical = 5.dp)) }
                if (serving.metrics.isNotEmpty() && serving.fields.isNotEmpty()) Spacer(Modifier.height(10.dp))
                FieldGrid(serving.fields)
            }
            // Only a frozen reading is labelled; live needs no text.
            if (state.frozen) {
                val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - snap.takenAtElapsedMs)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Frozen · reading from ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(wall))}",
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, highlight: Boolean = false, compact: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (highlight) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = if (compact) 2.dp else 4.dp)
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

/**
 * Compact three-column label/value grid. A field spans 1, 2 or 3 columns by
 * value length (ECI, DL/UL and WCDMA Cell ID need more), packed in order.
 */
@Composable
private fun FieldGrid(fields: List<Field>) {
    fun span(f: Field) = when {
        f.value.length <= 11 -> 1
        f.value.length <= 24 -> 2
        else -> 3
    }
    val rows = buildList<List<Field>> {
        var row = mutableListOf<Field>()
        var used = 0
        for (f in fields) {
            if (used + span(f) > 3) { add(row); row = mutableListOf(); used = 0 }
            row += f; used += span(f)
        }
        if (row.isNotEmpty()) add(row)
    }
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            row.forEach { f ->
                Column(Modifier.weight(span(f).toFloat())) {
                    Text(f.label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(f.value, style = MiuixTheme.textStyles.body2)
                }
            }
            val free = 3 - row.sumOf(::span)
            if (free > 0) Spacer(Modifier.weight(free.toFloat()))
        }
    }
}

@Composable
private fun CarrierSection(group: CarrierGroup) {
    SmallTitle(group.title, modifier = Modifier.padding(top = 6.dp))
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (group.summary.isNotEmpty()) {
                Text(group.summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(8.dp))
            }
            if (group.table) CaTable(group.carriers) else group.carriers.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.role, modifier = Modifier.width(76.dp), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 1)
                    c.band?.let { Text(it, modifier = Modifier.width(48.dp), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold) }
                    Column(Modifier.weight(1f)) {
                        Text(c.channel, style = MiuixTheme.textStyles.body2)
                        val sub = listOfNotNull(c.pci?.let { "PCI $it" }, c.bandwidthMhz?.let { "${formatPlain(it)} MHz" }).joinToString(" · ")
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

/** Cellular-Pro-style CA table: one column per carrier, one row per parameter. */
@Composable
private fun CaTable(carriers: List<Carrier>) = BoxWithConstraints {
    // Up to four carriers (P, S1, S2, S3) each column is fixed at the width
    // four columns share, and the columns sit from the left, so a lone PCell
    // isn't centred in empty space. From five carriers (S4) the columns share
    // the width, which shrinks them further.
    val colWidth = (maxWidth - 64.dp) / 4
    val fixedCols = carriers.size <= 4
    val barMax = colWidth - 6.dp
    Column {
    val line = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    @Composable
    fun row(label: String, header: Boolean = false, cell: @Composable (Carrier) -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).drawBehind {
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.width(64.dp), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontWeight = if (header) FontWeight.Medium else null)
            carriers.forEach { c ->
                Box((if (fixedCols) Modifier.width(colWidth) else Modifier.weight(1f)).padding(horizontal = 3.dp), Alignment.Center) { cell(c) }
            }
        }
    }
    @Composable
    fun text(v: String?, bold: Boolean = false, color: Color = MiuixTheme.colorScheme.onBackground) =
        Text(v ?: "–", style = MiuixTheme.textStyles.body2, fontWeight = if (bold) FontWeight.SemiBold else null,
            color = if (v == null) MiuixTheme.colorScheme.onSurfaceVariantSummary else color, maxLines = 1)

    // The primary cell's role is blue; the secondaries stay grey.
    row("", header = true) {
        text(it.role, color = if (it.role == "P") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    row("Band") { text(it.band, bold = true) }
    row("EARFCN") { text(it.channel) }
    row("PCI") { text(it.pci?.toString()) }
    row("BW") { text(it.bandwidthMhz?.let { b -> "${formatPlain(b)} MHz" }) }
    row("RSRP") { CellValueBar(it.rsrp, MetricKind.RSRP, barMax) }
    row("RSRQ") { CellValueBar(it.rsrq, MetricKind.RSRQ, barMax) }
    row("SINR") { CellValueBar(it.sinr, MetricKind.SINR, barMax) }
} }

/** A value with a level-coloured bar behind it, like Cellular-Pro's cells. */
@Composable
private fun CellValueBar(v: Double?, kind: MetricKind, maxWidth: Dp = Dp.Unspecified, rat: Rat = Rat.LTE) {
    if (v == null) {
        Text("–", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        return
    }
    val scale = signalScale(kind, rat)
    val color = signalLevelColor(scale.level(v))
    val fraction = scale.fraction(v)
    Box(
        Modifier.widthIn(max = maxWidth).fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .drawBehind { drawRect(color, size = Size(size.width * fraction, size.height)) }
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(formatMetric(v), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onBackground)
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
private fun AntennaRows(a: AntennaGroup, showHeading: Boolean) {
    if (showHeading) {
        Text("${a.rat.label} receiver chains", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
    }
    // LTE/NR chains can report tuned=true with a placeholder RSRP and a bogus
    // 0 dBm RX power (seen live on chains 3-4); without a valid RSRP the chain
    // has nothing real to show, so it's left out.
    val chains = if (a.rat == Rat.LTE || a.rat == Rat.NR) a.chains.filter { !it.tuned || it.rsrp != null } else a.chains
    chains.forEach { c ->
        if (!c.tuned) {
            Text("TX${c.chain}: not tuned", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 3.dp))
            return@forEach
        }
        // TX_RX_INFO has only RSRP (LTE/NR), RSCP (WCDMA) or RX power per
        // chain; per-antenna RSRQ/SINR don't exist in QMI. Labelled TX0, TX1 and so on
        // by chain number, whatever the metric.
        val n = "TX${c.chain}"
        val m = when {
            c.rsrp != null -> Metric(n, MetricKind.RSRP, a.rat, c.rsrp, "dBm")
            c.rscp != null -> Metric(n, MetricKind.RSCP, a.rat, c.rscp, "dBm")
            else -> Metric(n, MetricKind.RSSI, a.rat, c.rxPower, "dBm")
        }
        SignalBar(m, Modifier.padding(vertical = 4.dp), labelWidth = 64.dp)
    }
}

@Composable
private fun NeighborRows(neighbors: List<Neighbor>) {
    // NSG-style table: PCI | Band | EARFCN | RSRP bar | RSRQ bar, headers once,
    // rows already sorted by band. Band is derived from the channel.
    val first = neighbors.first()
    val metricLabels = first.metrics.take(2).map { it.label }
    val grey = MiuixTheme.colorScheme.onSurfaceVariantSummary

    @Composable
    fun cols(id: @Composable () -> Unit, band: @Composable () -> Unit, ch: @Composable () -> Unit, metrics: List<@Composable () -> Unit>) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.75f), Alignment.CenterStart) { id() }
            Box(Modifier.weight(0.7f), Alignment.CenterStart) { band() }
            Box(Modifier.weight(0.95f), Alignment.CenterStart) { ch() }
            metrics.forEach { m -> Box(Modifier.weight(1.25f).padding(horizontal = 3.dp), Alignment.Center) { m() } }
            repeat(2 - metrics.size) { Spacer(Modifier.weight(1.25f)) }
        }
    }
    @Composable
    fun head(t: String) = Text(t, style = MiuixTheme.textStyles.footnote1, color = grey, maxLines = 1)

    cols({ head(first.idLabel) }, { head("Band") }, { head(first.channelLabel) }, metricLabels.map { l -> @Composable { head(l) } })
    neighbors.forEach { n ->
        cols(
            { Text(n.id, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold) },
            { Text(n.band?.label ?: "–", style = MiuixTheme.textStyles.body2, color = if (n.band == null) grey else MiuixTheme.colorScheme.onBackground) },
            { Text(n.channel.toString(), style = MiuixTheme.textStyles.body2) },
            n.metrics.take(2).map { m -> @Composable { CellValueBar(m.value, m.kind, rat = m.rat) } }
        )
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text, modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

