package dev.qcom.bandmenu.ui

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import dev.qcom.bandmenu.network.DiagNrCarrier
import dev.qcom.bandmenu.network.Field
import dev.qcom.bandmenu.network.Metric
import dev.qcom.bandmenu.network.MetricKind
import dev.qcom.bandmenu.network.Neighbor
import dev.qcom.bandmenu.network.NetworkMonitor
import dev.qcom.bandmenu.network.NetworkSnapshot
import dev.qcom.bandmenu.network.nrFrequencyRange
import dev.qcom.bandmenu.network.nrMaxResourceBlocks
import dev.qcom.bandmenu.network.Rat
import dev.qcom.bandmenu.network.ReplaySession
import dev.qcom.bandmenu.network.ThputPoint
import dev.qcom.bandmenu.network.ServiceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import dev.qcom.bandmenu.network.TrafficTest
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
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
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun NetworkScreen(
    monitor: NetworkMonitor,
    /** True while the pager is settled on this page. */
    isActive: Boolean,
    simLabels: List<String> = listOf("SIM 1", "SIM 2"),
    sim2Available: Boolean = true,
    /** Extra work on pull-to-refresh (MainActivity re-checks SIM 2's card status). */
    onRefresh: () -> Unit = {},
    /** Menu: replay on or off. */
    onReplayToggle: (Boolean) -> Unit = {},
    /** Menu: DIAG on or off. */
    onDiagToggle: (Boolean) -> Unit = {},
    /** Serving-cell cards hidden (menu; persisted by the caller). */
    servingHidden: Boolean = false,
    onServingHiddenToggle: (Boolean) -> Unit = {},
    /** Throughput chart hidden (menu; persisted by the caller). */
    thputHidden: Boolean = false,
    onThputHiddenToggle: (Boolean) -> Unit = {},
    /** NSA: separate LTE and NR tables instead of one ENDC table (menu; persisted by the caller). */
    endcSeparate: Boolean = true,
    onEndcSeparateToggle: (Boolean) -> Unit = {},
    backdrop: Backdrop? = null
) {
    val context = LocalContext.current
    val state by monitor.state.collectAsState()
    val density = LocalDensity.current
    val navbarSpace = navbarSpace()
    val topBarInset = WindowInsets.systemBars.only(WindowInsetsSides.Top).asPaddingValues(density).calculateTopPadding()
    val topBarHeight = topBarInset + TopAppBarDefaults.CollapsedHeight
    val haptic = LocalHapticFeedback.current
    val test = remember { TrafficTest.get(context) }
    val testConfig by test.config.collectAsState()
    val testRun by test.run.collectAsState()
    var showTestSetup by remember { mutableStateOf(false) }

    // Replay of the recorded rounds while frozen: the slider picks the sample
    // shown in place of the live reading; unfreezing drops it.
    var replay by remember { mutableStateOf<ReplaySession?>(null) }
    var replayPos by remember { mutableIntStateOf(0) }
    var replayPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(state.frozen, state.sim, state.replayEnabled) {
        replayPlaying = false
        if (!state.frozen || !state.replayEnabled) { replay = null; return@LaunchedEffect }
        val sim = state.sim
        val opened = withContext(Dispatchers.IO) { monitor.openReplay(sim) }
        replay = opened
        replayPos = ((opened?.size ?: 1) - 1).coerceAtLeast(0)
    }
    LaunchedEffect(replayPlaying) {
        while (replayPlaying) {
            val r = replay ?: break
            if (replayPos >= r.size - 1) break
            delay(1000)
            replayPos = (replayPos + 1).coerceAtMost((replay?.size ?: 1) - 1)
        }
        replayPlaying = false
    }
    // Re-reads the recording (replay keeps recording while frozen), so the
    // timeline grows to now; the selected sample stays selected, or with
    // [toEnd] the newest is.
    val replayScope = rememberCoroutineScope()
    var replayLoad by remember { mutableStateOf<Job?>(null) }
    fun extendReplay(toEnd: Boolean = false) {
        if (replayLoad?.isActive == true) return
        val sim = state.sim
        replayLoad = replayScope.launch {
            val fresh = withContext(Dispatchers.IO) { monitor.openReplay(sim) } ?: return@launch
            if (fresh.size == 0 || !state.frozen || state.sim != sim) return@launch
            val old = replay
            replayPos = if (toEnd || old == null || old.size == 0) fresh.size - 1
                else fresh.indexAt(old.elapsedAt(replayPos.coerceIn(0, old.size - 1)))
            replay = fresh
        }
    }
    // A pull-to-refresh while frozen is recorded too: jump to it.
    LaunchedEffect(state.snapshot?.takenAtElapsedMs) {
        val r = replay ?: return@LaunchedEffect
        val t = state.snapshot?.takenAtElapsedMs ?: return@LaunchedEffect
        if (state.frozen && r.size > 0 && t > r.elapsedAt(r.size - 1)) extendReplay(toEnd = true)
    }
    val replayBar = state.frozen && state.replayEnabled && (replay?.size ?: 0) >= 2
    val replayRound = remember(replay, replayPos, replayBar) {
        if (replayBar) replay?.round(replayPos) else null
    }

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

    // The card in the middle of the view stays put when cards above it change
    // (NSA/SA layouts, the SCG coming and going); see ScrollAnchor.
    val scrollState = rememberScrollState()
    val anchor = remember(scrollState) { ScrollAnchor(scrollState) }
    val topBarPx = with(density) { topBarHeight.toPx() }
    val bottomPx = with(density) { (navbarSpace + if (replayBar) 76.dp else 0.dp).toPx() }

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
                    // Double tap anywhere (but on a control) toggles the padlock.
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            monitor.setFrozen(!monitor.state.value.frozen)
                        })
                    }
                    .onGloballyPositioned { c ->
                        anchor.viewport = c
                        anchor.viewTop = topBarPx
                        anchor.viewBottom = c.size.height - bottomPx
                    }
                    .verticalScroll(scrollState)
                    .onSizeChanged { anchor.contentResized() }
                    .padding(top = topBarHeight)
                    .padding(bottom = navbarSpace + 16.dp + if (replayBar) 76.dp else 0.dp)
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
                Spacer(Modifier.height(6.dp))

                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().then(pagerHeight?.let { Modifier.height(it) } ?: Modifier)
                ) { page ->
                    val sim = page + 1
                    // Only the selected SIM is polled; the other page shows
                    // that SIM's own last reading (or a spinner), never this one's.
                    val pageState = if (sim == state.sim && replayRound != null)
                            state.copy(snapshot = replayRound.snapshot(), operatorName = replayRound.operatorName, error = null)
                        else if (sim == state.sim) state
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
                            else -> {
                                // Throughput chart: the replay's window when scrubbing,
                                // else the live history, centred on the reading shown.
                                val center = snap.takenAtElapsedMs
                                val r = replay
                                val points = if (sim == state.sim && replayRound != null && r != null)
                                    remember(r, center) { r.thputBetween(center - 62_000, center + 62_000) }
                                else state.thputBySim[sim].orEmpty()
                                NetworkContent(snap, pageState, servingHidden, if (thputHidden) emptyList() else points,
                                    endcSeparate, anchor.takeIf { page == pagerState.currentPage })
                            }
                        }
                    }
                }
                // Keeps the anchored card in place when the page shrinks near its end.
                Spacer(Modifier.height(with(density) { anchor.slack.toDp() }))
            }
        }

        // The test script's status line is blue while it runs.
        val testColor = if (testRun.running) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
        if (backdrop != null) {
            Box(
                modifier = Modifier.fillMaxWidth().drawPlainBackdrop(
                    backdrop = screenBackdrop,
                    shape = { RectangleShape },
                    effects = { blur(8f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.5f)) }
                )
            ) { SmallTopAppBar(title = "Network", subtitle = testStatus(testRun), subtitleColor = testColor, color = Color.Transparent) }
        } else {
            SmallTopAppBar(title = "Network", subtitle = testStatus(testRun), subtitleColor = testColor)
        }
        if (replayBar) {
            val r = replay!!
            ReplayBar(
                size = r.size, pos = replayPos, elapsedMs = r.elapsedAt(replayPos.coerceIn(0, r.size - 1)),
                playing = replayPlaying,
                onPos = { replayPlaying = false; replayPos = it.coerceIn(0, r.size - 1) },
                onDragStart = { extendReplay() },
                onPlay = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    if (replayPlaying) replayPlaying = false
                    else { if (replayPos >= r.size - 1) replayPos = 0; replayPlaying = true }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = floatingControlBottom(12.dp))
            )
        }
        // Test script: play/pause at the top left.
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                if (testRun.running) test.stop() else if (!test.start()) showTestSetup = true
            },
            modifier = Modifier.align(Alignment.TopStart).padding(top = topBarInset + 8.dp, start = 8.dp)
        ) {
            Icon(
                imageVector = if (testRun.running) MiuixIcons.Pause else MiuixIcons.Play,
                contentDescription = if (testRun.running) "Stop test script" else "Start test script",
                tint = if (testRun.running) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground
            )
        }
        TestScriptDialog(
            show = showTestSetup,
            config = testConfig,
            onDismiss = { showTestSetup = false },
            onSave = { test.save(it); showTestSetup = false }
        )
        // Padlock (freeze), then the menu.
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = topBarInset + 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    monitor.setFrozen(!state.frozen)
                }
            ) {
                Icon(
                    imageVector = if (state.frozen) MiuixIcons.Lock else MiuixIcons.Unlock,
                    contentDescription = if (state.frozen) "Resume live monitoring" else "Freeze monitoring",
                    tint = if (state.frozen) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground
                )
            }
            WindowIconDropdownMenu(
                // Features, then what the page shows (a divider between).
                entries = listOf(
                    DropdownEntry(items = listOf(
                        DropdownItem(
                            text = if (state.diagEnabled) "Disable DIAG" else "Enable DIAG",
                            onClick = { onDiagToggle(!state.diagEnabled) }
                        ),
                        DropdownItem(
                            text = if (state.replayEnabled) "Disable replay" else "Enable replay",
                            onClick = { onReplayToggle(!state.replayEnabled) }
                        ),
                        DropdownItem(text = "Test script", onClick = { showTestSetup = true })
                    )),
                    DropdownEntry(items = listOf(
                        DropdownItem(
                            text = if (servingHidden) "Show serving cell" else "Hide serving cell",
                            onClick = { onServingHiddenToggle(!servingHidden) }
                        ),
                        DropdownItem(
                            text = if (thputHidden) "Show throughput" else "Hide throughput",
                            onClick = { onThputHiddenToggle(!thputHidden) }
                        ),
                        DropdownItem(
                            text = if (endcSeparate) "Combine ENDC" else "Separate ENDC",
                            onClick = { onEndcSeparateToggle(!endcSeparate) }
                        )
                    ))
                ),
                collapseOnSelection = true
            ) {
                Icon(imageVector = MiuixIcons.More, contentDescription = "Menu", tint = MiuixTheme.colorScheme.onBackground)
            }
        }
    }
}

/** Timeline for the frozen reading: play and the slider (fine scrubbing; the sample's time while held). */
@Composable
private fun ReplayBar(
    size: Int, pos: Int, elapsedMs: Long, playing: Boolean,
    onPos: (Int) -> Unit, onPlay: () -> Unit, onDragStart: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(if (playing) MiuixIcons.Pause else MiuixIcons.Play,
                    contentDescription = if (playing) "Pause replay" else "Play replay",
                    tint = if (playing) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
            }
            // The sample's clock time, shown above the thumb while it's held.
            val wall = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - elapsedMs)
            ScrubSlider(
                value = pos, max = size - 1, onValue = onPos,
                bubble = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(wall)),
                onDragStart = onDragStart,
                modifier = Modifier.weight(1f).padding(end = 10.dp)
            )
        }
    }
    }
}

/** Top-bar subtitle while the test script runs (or after it failed to). */
private fun testStatus(r: TrafficTest.Run): String = when {
    r.running -> buildString {
        append(if (r.upload) "Upload" else "Download")
        append(" | ").append(if (r.mbps >= 100) "%.0f".format(r.mbps) else "%.1f".format(r.mbps)).append(" Mbps")
        r.remaining?.let { append(" | %d:%02d".format(it / 60, it % 60)) }
        r.error?.let { append(" | ").append(it) }
    }
    r.error != null -> "Test stopped: ${r.error}"
    else -> ""
}

@Composable
private fun NetworkContent(
    snap: NetworkSnapshot, state: NetworkMonitor.State, servingHidden: Boolean, thput: List<ThputPoint>,
    endcSeparate: Boolean = true, anchor: ScrollAnchor? = null
) {
    // The summary and the serving cell (the LTE MCG in NSA) share one card;
    // any further cell (the NR SCG in NSA) keeps its own card below. The menu
    // can hide them all.
    if (!servingHidden) {
        Column(Modifier.scrollAnchor(anchor, "summary")) { SummaryCard(snap, state, snap.cells.firstOrNull()) }
    }
    state.error?.let { NoticeCard("Last read failed: $it. Showing the previous reading.") }

    if (!servingHidden) {
        snap.cells.drop(1).forEach { Column(Modifier.scrollAnchor(anchor, "cell " + it.title)) { CellSection(it) } }
        if (snap.cells.isEmpty() && snap.mode != ServiceMode.NO_SERVICE && snap.mode != ServiceMode.LIMITED) {
            NoticeCard("No serving-cell details were reported.")
        }
    }
    // NSA: one ENDC table unless the menu separates LTE and NR. Anchored by
    // position, so the LTE table turning ENDC and back keeps its place.
    val groups = snap.endc?.takeIf { !endcSeparate }?.let(::listOf) ?: snap.carrierGroups
    // With the serving cell hidden, the tables go without their headings too.
    groups.forEachIndexed { i, g -> Column(Modifier.scrollAnchor(anchor, "group $i")) { CarrierSection(g, title = !servingHidden) } }
    state.diagError?.let { NoticeCard(if (state.diagEnabled) "DIAG read failed: $it" else "DIAG stopped: $it") }
    // DIAG only: QMI has no throughput. NR scale for NSA/SA, LTE scale otherwise.
    if (state.diagEnabled && thput.isNotEmpty()) {
        val nr = snap.mode == ServiceMode.NR_NSA || snap.mode == ServiceMode.NR_SA
        Column(Modifier.scrollAnchor(anchor, "thput")) {
            ThroughputCard(thput, snap.takenAtElapsedMs, if (nr) listOf(100, 200, 400, 800) else listOf(50, 100, 200, 300))
        }
    }
    if (snap.antennas.isNotEmpty()) Column(Modifier.scrollAnchor(anchor, "antennas")) {
        Expandable("Antenna measurements") {
            // UE transmit power (TX_RX_INFO, only while in traffic), on top.
            snap.antennas.filter { it.rat == Rat.LTE || it.rat == Rat.WCDMA }.forEach {
                SignalBar(Metric("TX PWR", MetricKind.TX_POWER, it.rat, it.txPower, "dBm"),
                    Modifier.padding(vertical = 3.dp), labelWidth = 64.dp)
            }
            // Headings only when LTE and NR chains are shown together (NSA).
            val showHeadings = snap.antennas.size > 1
            snap.antennas.forEach { AntennaRows(it, showHeadings) }
        }
    }
    // Kept whenever there's a serving cell, even when no neighbours are
    // reported this round, so it doesn't come and go. Collapsed by default.
    if (snap.cells.isNotEmpty()) Column(Modifier.scrollAnchor(anchor, "neighbours")) {
        Expandable("Neighbour cells (${snap.neighbors.size})") {
            if (snap.neighbors.isEmpty()) {
                Text("No neighbour cells reported.", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else NeighborRows(snap.neighbors)
        }
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
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // The mode itself is the pill (blue when in service, grey otherwise),
            // with the RRC chip beside it. The pill takes the chip's height
            // when that's taller, so the two line up.
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
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Bold,
                        color = if (inService) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground
                    )
                }
                val chips = snap.rrc.map { "${it.label}: ${it.value}" to (it.value == "Connected") }
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chips.forEach { (text, highlight) -> Chip(text, highlight, compact = true) }
                    }
                }
            }
            // Operator, PLMN and roaming: a row of their own after the cell's
            // first row (band, bandwidth, channel); in NR SA after the second
            // (PCI, SCS, frequency) too.
            val operatorRow = listOf(
                Field("Operator", state.operatorName ?: "–"),
                Field("PLMN", snap.plmn?.toString() ?: "–"),
                Field("Roaming", when (snap.roaming) { true -> "Yes"; false -> "No"; null -> "–" })
            ).takeIf { state.operatorName != null || snap.plmn != null || snap.roaming != null }.orEmpty()
            // Serving cell: its bars, then its fields.
            if (serving != null && serving.metrics.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                serving.metrics.forEach { SignalBar(it, Modifier.padding(vertical = 3.dp)) }
            }
            val fields = serving?.fields.orEmpty()
            if (fields.isNotEmpty() || operatorRow.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FieldGrid(fields, extraRow = operatorRow, extraRowAfter = if (snap.mode == ServiceMode.NR_SA) 2 else 1)
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
            // Compact (the RRC chips beside the mode pill): scaled with the pill.
            text, style = if (compact) MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp) else MiuixTheme.textStyles.footnote1,
            color = if (highlight) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun CellSection(cell: CellCard) {
    SmallTitle(cell.title, modifier = Modifier.padding(top = 2.dp))
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            cell.metrics.forEach { SignalBar(it, Modifier.padding(vertical = 3.dp)) }
            if (cell.metrics.isNotEmpty() && cell.fields.isNotEmpty()) Spacer(Modifier.height(12.dp))
            FieldGrid(cell.fields)
        }
    }
}

/**
 * Compact three-column label/value grid. A field spans 1, 2 or 3 columns by
 * value length (ECI, DL/UL and WCDMA Cell ID need more), packed in order.
 */
@Composable
private fun FieldGrid(fields: List<Field>, extraRow: List<Field> = emptyList(), extraRowAfter: Int = 1) {
    fun span(f: Field) = when {
        f.value.length <= 11 -> 1
        f.value.length <= 24 -> 2
        else -> 3
    }
    fun pack(list: List<Field>) = buildList<List<Field>> {
        var row = mutableListOf<Field>()
        var used = 0
        for (f in list) {
            if (used + span(f) > 3) { add(row); row = mutableListOf(); used = 0 }
            row += f; used += span(f)
        }
        if (row.isNotEmpty()) add(row)
    }
    // [extraRow] is packed on its own and goes after the first [extraRowAfter] rows.
    val main = pack(fields)
    val rows = main.take(extraRowAfter) + pack(extraRow) + main.drop(extraRowAfter)
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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
private fun CarrierSection(group: CarrierGroup, title: Boolean = true) {
    if (title) SmallTitle(group.title, modifier = Modifier.padding(top = 2.dp))
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (group.summary.isNotEmpty()) {
                Text(group.summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(4.dp))
            }
            if (group.table) CaTable(group.carriers, group.diag, group.nrTable, group.endc) else group.carriers.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
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

/** CA table: one column per carrier, one row per parameter. */
@Composable
private fun CaTable(carriers: List<Carrier>, diag: Boolean, nr: Boolean = false, endc: Boolean = false) = BoxWithConstraints {
    // Up to four carriers (P, S1, S2, S3) each column is fixed at the width
    // four columns share, and the columns sit from the left, so a lone PCell
    // isn't centred in empty space. From five carriers (S4) the columns share
    // the width, which shrinks them further.
    val colWidth = (maxWidth - 64.dp) / 4
    val fixedCols = carriers.size <= 4
    val barMax = colWidth - 6.dp
    // Cell text shrinks when the columns get too narrow for the widest value
    // at the normal size (many carriers), instead of wrapping onto two lines.
    val cellStyle = fittedCellStyle(carriers, if (fixedCols) colWidth else (maxWidth - 64.dp) / carriers.size)
    Column {
    val line = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    @Composable
    fun row(label: String, header: Boolean = false, cell: @Composable (Carrier) -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).drawBehind {
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            }.padding(vertical = 3.dp),
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
        Text(v ?: "–", style = cellStyle, fontWeight = if (bold) FontWeight.SemiBold else null,
            color = if (v == null) MiuixTheme.colorScheme.onSurfaceVariantSummary else color, maxLines = 1, softWrap = false)

    // The primary cells' roles (P; MCG and SCG in ENDC) are blue; the secondaries stay grey.
    row("", header = true) {
        text(it.role, color = if (it.role == "P" || it.role == "MCG" || it.role == "SCG") MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    if (nr) { NrRows(::row, ::text, barMax, cellStyle); return@Column }
    row("Band") { text(it.band, bold = true) }
    row("BW") { text(it.bandwidthMhz?.let { b -> formatPlain(b) }) }
    // ENDC: EARFCN and NR-ARFCN share the row.
    row(if (endc) "ARFCN" else "EARFCN") { text(it.channel) }
    row("PCI") { text(it.pci?.toString()) }
    row("RSRP") { CellValueBar(it.rsrp, MetricKind.RSRP, barMax, style = cellStyle) }
    row("RSRQ") { CellValueBar(it.rsrq, MetricKind.RSRQ, barMax, style = cellStyle) }
    row("SINR") { CellValueBar(it.sinr, MetricKind.SINR, barMax, style = cellStyle) }
    // PDSCH rows from DIAG, over the last round (about a second). A carrier
    // with no grants this round has "–" for the per-grant values and 0.0 Mbps
    // PHY DL; PHY UL is "–" when the carrier sent nothing.
    // ENDC's NR columns fill the same rows: Tx/Rx is CSI-RS ports × Rx (as
    // the NR table's Port/Rx), Rank the mean layers.
    if (diag) {
        row("Tx/Rx") {
            text(it.diag?.let { d -> if (d.txAntennas != null && d.rxAntennas != null) "${d.txAntennas}×${d.rxAntennas}" else null }
                ?: it.nr?.let { n -> if (n.csiPorts == null && n.rxAntennas == null) null else "${n.csiPorts ?: "-"}×${n.rxAntennas ?: "-"}" })
        }
        // No TxMode row in ENDC (NR has none).
        if (!endc) row("TxMode") { text(it.diag?.transmissionMode?.let { m -> "TM$m" }) }
        row("Rank") { text(it.diag?.rank?.toString() ?: it.nr?.layers?.let { l -> String.format(Locale.US, "%.1f", l) }) }
        row("MCS") {
            val (value, fill) = mcsCell(it)
            CellGradientBar(value, fill, barMax, cellStyle)
        }
        row("RBs") {
            val (value, fill) = rbCell(it)
            CellGradientBar(value, fill, barMax, cellStyle)
        }
        row("Mod") { text((it.diag?.modulation ?: it.nr?.modulation)?.removeSuffix("QAM")) }
        // Values coloured as the chart's lines.
        row("PHY DL") { text((it.diag?.mbps ?: it.nr?.mbps)?.let { m -> String.format(Locale.US, "%.1f", m) }, bold = true, color = DownlinkColor) }
        row("PHY UL") { text((it.diag?.ulMbps ?: it.nr?.ulMbps)?.let { u -> String.format(Locale.US, "%.1f", u) }, bold = true, color = UplinkColor) }
    }
} }

/**
 * The NR table (DIAG only), one column per carrier:
 * identity (B825), signal (B97F RSRP/RSRQ; B8D8/B8DD SS-SINR from SSB and
 * CSI-SINR from TRS records) and PDSCH over the last round (B887). Rank is
 * the mean of scheduled layers. Rx, CQI (B8A7) and BLER are still parsed
 * but not shown.
 */
@Composable
private fun NrRows(
    row: @Composable (String, Boolean, @Composable (Carrier) -> Unit) -> Unit,
    text: @Composable (String?, Boolean, Color) -> Unit,
    barMax: Dp,
    cellStyle: TextStyle
) {
    val fg = MiuixTheme.colorScheme.onBackground
    @Composable fun r(label: String, cell: @Composable (Carrier) -> Unit) = row(label, false, cell)
    @Composable fun t(v: String?, bold: Boolean = false, color: Color = fg) = text(v, bold, color)
    r("Band") { t(it.band, bold = true) }
    r("BW") { t(it.bandwidthMhz?.let { b -> formatPlain(b) }) }
    r("NARFCN") { t(it.channel) }
    r("PCI") { t(it.pci?.toString()) }
    r("SCS") { t(it.nr?.scsKhz?.toString()) }
    r("RSRP") { CellValueBar(it.rsrp, MetricKind.RSRP, barMax, Rat.NR, cellStyle) }
    r("RSRQ") { CellValueBar(it.rsrq, MetricKind.RSRQ, barMax, Rat.NR, cellStyle) }
    r("SINR") { CellValueBar(it.sinr, MetricKind.SINR, barMax, Rat.NR, cellStyle) }
    r("CSINR") { CellValueBar(it.nr?.csiSinr, MetricKind.SINR, barMax, Rat.NR, cellStyle) }
    // CSI-RS ports the network configured × the device's Rx antennas, e.g. "8×4".
    r("Port/Rx") { t(it.nr?.let { n -> if (n.csiPorts == null && n.rxAntennas == null) null else "${n.csiPorts ?: "-"}×${n.rxAntennas ?: "-"}" }) }
    r("Rank") { t(it.nr?.layers?.let { l -> String.format(Locale.US, "%.1f", l) }) }
    r("MCS") {
        val (value, fill) = mcsCell(it)
        CellGradientBar(value, fill, barMax, cellStyle)
    }
    r("RBs") {
        val (value, fill) = rbCell(it)
        CellGradientBar(value, fill, barMax, cellStyle)
    }
    // QAM order only ("256"); QPSK as is.
    r("Mod") { t(it.nr?.modulation?.removeSuffix("QAM")) }
    // Values coloured as the chart's lines.
    r("PHY DL") { t(it.nr?.let { d -> String.format(Locale.US, "%.1f", d.mbps) }, bold = true, color = DownlinkColor) }
    r("PHY UL") { t(it.nr?.ulMbps?.let { u -> String.format(Locale.US, "%.1f", u) }, bold = true, color = UplinkColor) }
}

/**
 * The CA table's cell text style: body2, scaled down when a column (minus its
 * padding) is narrower than the widest value would be at body2. The widest
 * candidates are a signal value, a rate and each carrier's channel, bold.
 */
@Composable
private fun fittedCellStyle(carriers: List<Carrier>, columnWidth: Dp): TextStyle {
    val base = MiuixTheme.textStyles.body2
    val measurer = rememberTextMeasurer()
    val available = with(LocalDensity.current) { (columnWidth - 8.dp).toPx() }
    val widest = remember(carriers.map { it.channel }, base) {
        val bold = base.copy(fontWeight = FontWeight.SemiBold)
        (listOf("-140.0", "1000.0") + carriers.map { it.channel }).maxOf { measurer.measure(it, bold).size.width }
    }
    val fit = if (widest > 0) available / widest else 1f
    return if (fit >= 1f) base else base.copy(fontSize = base.fontSize * fit)
}

/** A value with a level-coloured bar behind it. */
@Composable
private fun CellValueBar(
    v: Double?, kind: MetricKind, maxWidth: Dp = Dp.Unspecified, rat: Rat = Rat.LTE,
    style: TextStyle = MiuixTheme.textStyles.body2
) {
    if (v == null) {
        Text("–", style = style, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        return
    }
    val scale = signalScale(kind, rat)
    val color = signalLevelColor(scale.level(v))
    val fraction = scale.fraction(v)
    Box(
        Modifier.widthIn(max = maxWidth).fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .drawBehind { drawRect(color, size = Size(size.width * fraction, size.height)) }
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(formatMetric(v), style = style, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1, softWrap = false)
    }
}

/** A value with a fading blue bar behind it (the MCS/RBs rows): solid
 * primary at the left edge, fading out along the fill, [fraction] of the
 * cell's width. A null [fraction] shows the plain value, like a cell with
 * no bar. */
@Composable
private fun CellGradientBar(text: String?, fraction: Float?, maxWidth: Dp, style: TextStyle) {
    if (text == null || fraction == null) {
        Text(text ?: "–", style = style,
            color = if (text == null) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onBackground,
            maxLines = 1, softWrap = false)
        return
    }
    val blue = MiuixTheme.colorScheme.primary
    Box(
        Modifier.widthIn(max = maxWidth).fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .drawBehind {
                val w = size.width * fraction
                drawRect(Brush.horizontalGradient(listOf(blue, blue.copy(alpha = 0.12f)), endX = w),
                    size = Size(w, size.height))
            }
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = style, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1, softWrap = false)
    }
}

/** The MCS cell of [c]: the rounded mean, and its share of the 0..31 range. */
internal fun mcsCell(c: Carrier): Pair<String?, Float?> {
    val v = c.diag?.mcs ?: c.nr?.mcs ?: return null to null
    return Math.round(v).toString() to fillFraction(v, 31.0)
}

/** The RBs cell of [c]: the rounded mean, and its share of the cell's maximum
 * RBs — the NR tables from the cell's FR/SCS/bandwidth (and no bar until those
 * are reported), an LTE cell always over the fixed 100. */
internal fun rbCell(c: Carrier): Pair<String?, Float?> {
    val v = c.diag?.rbs ?: c.nr?.rbs ?: return null to null
    return Math.round(v).toString() to rbFraction(c.nr, v)
}

private fun rbFraction(nr: DiagNrCarrier?, rbs: Double): Float? {
    if (nr == null) return fillFraction(rbs, 100.0)
    val scsKhz = nr.scsKhz ?: return null
    val bwMhz = nr.bandwidthMhz ?: return null
    val max = nrMaxResourceBlocks(nrFrequencyRange(nr.band, nr.arfcn), scsKhz, bwMhz) ?: return null
    return fillFraction(rbs, max.toDouble())
}

private fun fillFraction(v: Double, max: Double): Float = (v / max).coerceIn(0.0, 1.0).toFloat()

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title.substringBefore(" (")) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
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
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { content() }
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
            Text("RX${c.chain}: not tuned", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 3.dp))
            return@forEach
        }
        // TX_RX_INFO has only RSRP (LTE/NR), RSCP (WCDMA) or RX power per
        // chain; per-antenna RSRQ/SINR don't exist in QMI. Labelled RX0, RX1 and so on
        // by chain number, whatever the metric.
        val n = "RX${c.chain}"
        val m = when {
            c.rsrp != null -> Metric(n, MetricKind.RSRP, a.rat, c.rsrp, "dBm")
            c.rscp != null -> Metric(n, MetricKind.RSCP, a.rat, c.rscp, "dBm")
            else -> Metric(n, MetricKind.RSSI, a.rat, c.rxPower, "dBm")
        }
        SignalBar(m, Modifier.padding(vertical = 3.dp), labelWidth = 64.dp)
    }
}

@Composable
private fun NeighborRows(neighbors: List<Neighbor>) {
    // Table: PCI | Band | EARFCN | RSRP bar | RSRQ bar, headers once,
    // rows already sorted by band. Band is derived from the channel.
    val first = neighbors.first()
    val metricLabels = first.metrics.take(2).map { it.label }
    val grey = MiuixTheme.colorScheme.onSurfaceVariantSummary

    @Composable
    fun cols(id: @Composable () -> Unit, band: @Composable () -> Unit, ch: @Composable () -> Unit, metrics: List<@Composable () -> Unit>) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

