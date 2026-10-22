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
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import dev.qcom.bandmenu.network.formatNrNci
import dev.qcom.bandmenu.network.Metric
import dev.qcom.bandmenu.network.MetricKind
import dev.qcom.bandmenu.network.Neighbor
import dev.qcom.bandmenu.network.NetworkMonitor
import dev.qcom.bandmenu.network.NetworkSnapshot
import dev.qcom.bandmenu.network.nrFrequencyRange
import dev.qcom.bandmenu.network.nrMaxResourceBlocks
import dev.qcom.bandmenu.network.Rat
import dev.qcom.bandmenu.network.ReplaySession
import dev.qcom.bandmenu.network.RxChain
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
import top.yukonga.miuix.kmp.basic.CardDefaults
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
    /** SIM 1's card status: off or absent shows a note instead of its readings
     *  (only reachable when neither SIM is usable; otherwise its page is hidden). */
    sim1Status: dev.qcom.bandmenu.network.SimSlotStatus = dev.qcom.bandmenu.network.SimSlotStatus.UNKNOWN,
    /** Page p shows SIM p + 1 + simOffset (1 when only SIM 2 is usable). */
    simOffset: Int = 0,
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
    keepScreenOn: Boolean = false,
    onKeepScreenOnToggle: (Boolean) -> Unit = {},
    /** NSA: separate LTE and NR tables instead of one ENDC table (menu; persisted by the caller). */
    endcSeparate: Boolean = true,
    onEndcSeparateToggle: (Boolean) -> Unit = {},
    /** NR SA gNB ID bit length per SIM (22..32 even; Cell ID is 36 - gnbBits). */
    gnbBitsSim1: Int = 22,
    gnbBitsSim2: Int = 22,
    onGnbBitsCycle: (Int) -> Unit = {},
    backdrop: Backdrop? = null
) {
    val context = LocalContext.current
    val state by monitor.state.collectAsState()
    val density = LocalDensity.current
    val navbarSpace = navbarSpace()
    val topBarInset = WindowInsets.systemBars.only(WindowInsetsSides.Top).asPaddingValues(density).calculateTopPadding()
    val haptic = LocalHapticFeedback.current
    val test = remember { TrafficTest.get(context) }
    val testConfig by test.config.collectAsState()
    val testRun by test.run.collectAsState()
    // The test script's status line below the title, only while there is one
    // (a run, or the reason it stopped): otherwise the bar is as tall as the
    // other pages'. It opens and closes smoothly and the page's top follows,
    // so the tab row and the refresh indicator are never covered. One line of
    // the subtitle style: a longer status is cut off, never taller.
    val status = testStatus(testRun)
    val statusMeasurer = rememberTextMeasurer()
    val body2 = MiuixTheme.textStyles.body2
    val statusLineHeight = with(density) {
        remember(statusMeasurer, body2) { statusMeasurer.measure("0", body2).size.height.toDp() } + 4.dp
    }
    val statusHeight by animateDpAsState(if (status.isNotEmpty()) statusLineHeight else 0.dp, label = "testStatus")
    val topBarHeight = topBarInset + TopAppBarDefaults.CollapsedHeight + statusHeight
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
        val opened = withContext(Dispatchers.IO) {
            monitor.openReplay(sim)?.also { s ->
                if (s.size >= 2) {
                    val last = s.size - 1
                    s.round(last)?.snapshot()
                    val t = s.elapsedAt(last)
                    s.thputBetween(t - THPUT_BEFORE_MS, t + THPUT_AFTER_MS)
                }
            }
        }
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
        val old = replay
        val curPos = replayPos
        replayLoad = replayScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val s = monitor.openReplay(sim) ?: return@withContext null
                if (old != null) {
                    if (!toEnd && s.size == old.size &&
                        (s.size == 0 || s.elapsedAt(s.size - 1) == old.elapsedAt(old.size - 1))) {
                        return@withContext null
                    }
                    s.inheritCache(old)
                }
                if (s.size >= 2) {
                    val target = if (toEnd || old == null || old.size == 0) s.size - 1
                        else s.indexAt(old.elapsedAt(curPos.coerceIn(0, old.size - 1)))
                    s.round(target)?.snapshot()
                    val t = s.elapsedAt(target)
                    s.thputBetween(t - THPUT_BEFORE_MS, t + THPUT_AFTER_MS)
                }
                s
            } ?: return@launch
            if (fresh.size == 0 || !state.frozen || state.sim != sim) return@launch
            val current = replay
            replayPos = if (toEnd || current == null || current.size == 0) fresh.size - 1
                else fresh.indexAt(current.elapsedAt(replayPos.coerceIn(0, current.size - 1)))
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
    // With one usable SIM there's one page, so a swipe goes straight on to
    // the neighbouring screen. Page p is SIM p + 1 + simOffset.
    val onePage = simOffset == 1 || !sim2Available
    val pagerState = rememberPagerState(initialPage = if (onePage) 0 else state.sim - 1,
        pageCount = { if (simOffset == 1 || !sim2Available) 1 else 2 })
    val pagerScope = rememberCoroutineScope()
    val currentOffset by rememberUpdatedState(simOffset)
    LaunchedEffect(pagerState) {
        // Follows the SIM shown, so SIM 1 turning off (same page, now SIM 2)
        // switches polling too.
        snapshotFlow { pagerState.targetPage + currentOffset }.collect { monitor.selectSim(it + 1) }
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
                        // The visible band in the anchor's coordinates, which
                        // start below the top bar's padding (see below).
                        anchor.viewTop = 0f
                        anchor.viewBottom = c.size.height - bottomPx - topBarPx
                    }
                    .verticalScroll(scrollState)
                    .padding(top = topBarHeight)
                    // Cards are measured below the top bar's padding: the bar
                    // growing or shrinking (the test status line) moves the
                    // page with it, as at the top, instead of the anchor
                    // scrolling against the padding a frame late (a jump).
                    .onGloballyPositioned {
                        anchor.content = it
                        anchor.contentResized()
                    }
                    .padding(bottom = navbarSpace + 16.dp + if (replayBar) 76.dp else 0.dp)
            ) {
                TabRowWithContour(
                    tabs = simLabels,
                    selectedTabIndex = pagerState.targetPage + simOffset,
                    onTabSelected = {
                        val target = it - simOffset
                        if (target !in 0 until pagerState.pageCount) return@TabRowWithContour
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (pagerState.targetPage != target) pagerScope.launch { pagerState.animateScrollToPage(target) }
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
                    val sim = page + 1 + simOffset
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
                            sim == 1 && sim1Status == dev.qcom.bandmenu.network.SimSlotStatus.DISABLED -> NoticeCard("SIM 1 is turned off.")
                            sim == 1 && sim1Status == dev.qcom.bandmenu.network.SimSlotStatus.NO_SIM -> NoticeCard("No SIM in slot 1.")
                            snap == null && pageState.error == null -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                            snap == null -> NoticeCard(pageState.error!!)
                            else -> {
                                // Throughput chart: the replay's window when scrubbing,
                                // else the live history, around the reading shown.
                                val center = snap.takenAtElapsedMs
                                val r = replay
                                val points = if (sim == state.sim && replayRound != null && r != null)
                                    remember(r, center) { r.thputBetween(center - THPUT_BEFORE_MS, center + THPUT_AFTER_MS) }
                                else state.thputBySim[sim].orEmpty()
                                val pageGnbBits = if (sim == 2) gnbBitsSim2 else gnbBitsSim1
                                androidx.compose.runtime.CompositionLocalProvider(
                                    LocalSnapSignalBars provides (sim == state.sim && replayRound != null)
                                ) {
                                    NetworkContent(snap, pageState, servingHidden, if (thputHidden) emptyList() else points,
                                        endcSeparate, pageGnbBits, anchor.takeIf { page == pagerState.currentPage })
                                }
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
        val statusContent: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth().height(statusHeight).clipToBounds(), contentAlignment = Alignment.Center) {
                if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 16.dp),
                    style = MiuixTheme.textStyles.body2, color = testColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            ) { SmallTopAppBar(title = "Network", color = Color.Transparent, bottomContent = statusContent) }
        } else {
            SmallTopAppBar(title = "Network", bottomContent = statusContent)
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
            val activeSim = (pagerState.targetPage + simOffset + 1).coerceIn(1, 2)
            val activeGnbBits = if (activeSim == 2) gnbBitsSim2 else gnbBitsSim1
            WindowIconDropdownMenu(
                // Features, then what the page shows (a divider between).
                entries = listOf(
                    DropdownEntry(items = listOf(
                        DropdownItem(
                            text = "Enable DIAG", selected = state.diagEnabled,
                            onClick = { onDiagToggle(!state.diagEnabled) }
                        ),
                        DropdownItem(
                            text = "Enable replay", selected = state.replayEnabled,
                            onClick = { onReplayToggle(!state.replayEnabled) }
                        ),
                        DropdownItem(text = "Test script", onClick = { showTestSetup = true }),
                        DropdownItem(
                            text = "Keep screen on", selected = keepScreenOn,
                            onClick = { onKeepScreenOnToggle(!keepScreenOn) }
                        )
                    )),
                    DropdownEntry(items = listOf(
                        DropdownItem(
                            text = "Hide serving cell", selected = servingHidden,
                            onClick = { onServingHiddenToggle(!servingHidden) }
                        ),
                        DropdownItem(
                            text = "Hide throughput", selected = thputHidden,
                            onClick = { onThputHiddenToggle(!thputHidden) }
                        ),
                        DropdownItem(
                            text = "Combine ENDC", selected = !endcSeparate,
                            onClick = { onEndcSeparateToggle(!endcSeparate) }
                        ),
                        DropdownItem(
                            text = "gNB $activeGnbBits / Cell ${36 - activeGnbBits}",
                            onClick = { onGnbBitsCycle(activeSim) }
                        )
                    ))
                ),
                collapseOnSelection = false
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
    // Same shade as the signal-bar track on a card, so the bar stands out over cards behind it.
    val bg = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f).compositeOver(MiuixTheme.colorScheme.surfaceContainer)
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Card(
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures {} },
        colors = CardDefaults.defaultColors(color = bg)
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(if (playing) MiuixIcons.Pause else MiuixIcons.Play,
                    contentDescription = if (playing) "Pause replay" else "Play replay",
                    tint = if (playing) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
            }
            // The sample's clock time, shown above the thumb while it's held.
            val wall = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - elapsedMs)
            val bubble = remember(wall / 1000) { timeFormat.format(java.util.Date(wall)) }
            ScrubSlider(
                value = pos, max = size - 1, onValue = onPos,
                bubble = bubble,
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
    endcSeparate: Boolean = true, gnbBits: Int = 22, anchor: ScrollAnchor? = null
) {
    val p = "${snap.sim}:"
    // The summary and the serving cell (the LTE MCG in NSA) share one card;
    // any further cell (the NR SCG in NSA) keeps its own card below. The menu
    // can hide them all.
    if (!servingHidden) {
        Column(Modifier.scrollAnchor(anchor, "${p}summary")) { SummaryCard(snap, state, snap.cells.firstOrNull(), gnbBits) }
    }
    state.error?.let { NoticeCard("Last read failed: $it. Showing the previous reading.") }

    if (!servingHidden) {
        snap.cells.drop(1).forEach { Column(Modifier.scrollAnchor(anchor, "${p}cell:${it.title}")) { CellSection(it) } }
        if (snap.cells.isEmpty() && snap.mode != ServiceMode.NO_SERVICE &&
            snap.mode != ServiceMode.LIMITED && snap.mode != ServiceMode.UNKNOWN) {
            NoticeCard("No serving-cell details were reported.")
        }
    }
    // NSA: one ENDC table unless the menu separates LTE and NR. LTE CA and
    // combined ENDC share one key so the table keeps its place when NSA comes and goes.
    val groups = snap.endc?.takeIf { !endcSeparate }?.let(::listOf) ?: snap.carrierGroups
    // With the serving cell hidden, the tables go without their headings too.
    groups.forEach { g ->
        val groupKey = if (g.title == "NR aggregation") "${p}nr" else "${p}lte"
        Column(Modifier.scrollAnchor(anchor, groupKey)) { CarrierSection(g, title = !servingHidden) }
    }
    state.diagError?.let { NoticeCard(if (state.diagEnabled) "DIAG read failed: $it" else "DIAG stopped: $it") }
    // DIAG only: QMI has no throughput (hidden on WCDMA).
    if (state.diagEnabled && thput.isNotEmpty() && snap.mode != ServiceMode.WCDMA) {
        Column(Modifier.scrollAnchor(anchor, "${p}thput")) {
            ThroughputCard(thput, snap.takenAtElapsedMs)
        }
    }
    // Below the chart (kept when the menu hides the chart).
    if (state.diagEnabled && snap.mode != ServiceMode.WCDMA) {
        val uplink = uplinkCarriers(snap)
        if (uplink.isNotEmpty()) Column(Modifier.scrollAnchor(anchor, "${p}uplink")) { UplinkCard(uplink) }
    }
    if (snap.antennas.isNotEmpty()) Column(Modifier.scrollAnchor(anchor, "${p}antennas")) {
        Expandable("Antenna measurements") {
            // UE transmit power (TX_RX_INFO, only while in traffic), on top: once per
            // RAT, from its first group (QMI's serving cell, or DIAG's first carrier).
            snap.antennas.filter { it.rat == Rat.LTE || it.rat == Rat.WCDMA }.distinctBy { it.rat }.forEach {
                SignalBar(Metric("TX PWR", MetricKind.TX_POWER, it.rat, it.txPower, "dBm"),
                    Modifier.padding(vertical = 3.dp), labelWidth = 64.dp)
            }
            // QMI's chains have a heading only when LTE and NR are shown together
            // (NSA); DIAG's carriers (PCell, SCells) always have theirs.
            val showHeadings = snap.antennas.size > 1
            snap.antennas.forEach {
                if (it.carrier != null) CarrierAntennaRows(it) else AntennaRows(it, showHeadings)
            }
        }
    }
    // Kept whenever there's a serving cell, even when no neighbours are
    // reported this round, so it doesn't come and go. Collapsed by default.
    if (snap.cells.isNotEmpty()) Column(Modifier.scrollAnchor(anchor, "${p}neighbours")) {
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
private fun SummaryCard(snap: NetworkSnapshot, state: NetworkMonitor.State, serving: CellCard?, gnbBits: Int = 22) {
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
            // Operator, PLMN and roaming: the top row (GSM: after the cell's first
            // row).
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
            val fields = serving?.fields.orEmpty().map { f ->
                if (f.label == "gNB / Cell" && serving?.nci != null) Field(f.label, formatNrNci(serving.nci, gnbBits)) else f
            }
            if (fields.isNotEmpty() || operatorRow.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FieldGrid(fields, extraRow = operatorRow, extraRowAfter = if (snap.mode == ServiceMode.GSM) 1 else 0, serving = true)
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

/** Section titles start at the cards' inner text edge (16 dp), not
 *  SmallTitle's default 28 dp, so the page has one left line. */
private val SectionTitleMargin = PaddingValues(16.dp, 8.dp)

@Composable
private fun CellSection(cell: CellCard) {
    SmallTitle(cell.title, modifier = Modifier.padding(top = 2.dp), insideMargin = SectionTitleMargin)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            cell.metrics.forEach { SignalBar(it, Modifier.padding(vertical = 3.dp)) }
            if (cell.metrics.isNotEmpty() && cell.fields.isNotEmpty()) Spacer(Modifier.height(12.dp))
            FieldGrid(cell.fields, serving = true)
        }
    }
}

/**
 * Compact three-column label/value grid. A field spans 1, 2 or 3 columns by
 * value length (ECI, DL/UL and WCDMA Cell ID need more), packed in order.
 */
@Composable
private fun FieldGrid(fields: List<Field>, extraRow: List<Field> = emptyList(), extraRowAfter: Int = 1, serving: Boolean = true) = BoxWithConstraints {
    fun span(f: Field) = when {
        f.label == "gNB / Cell" || f.value.length <= 11 -> 1
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
    // Column widths. The serving card's third column is narrower, which moves
    // the second and third columns right so the right side looks less empty.
    val columns = if (serving) listOf(1.08f, 1.08f, 0.84f) else listOf(1f, 1f, 1f)
    val totalWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
    val measurer = rememberTextMeasurer()
    // The serving card runs its fields slightly smaller than the other cards.
    val label = MiuixTheme.textStyles.footnote2.let { if (serving) it.copy(fontSize = it.fontSize * 0.92f) else it }
    val baseValue = MiuixTheme.textStyles.body2.let { if (serving) it.copy(fontSize = it.fontSize * 0.92f) else it }
    Column {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                var col = 0
                row.forEach { f ->
                    val weight = columns.subList(col, col + span(f)).sum()
                    col += span(f)
                    val colWidthPx = totalWidthPx * (weight / 3f)
                    val textWidth = remember(f.value, baseValue) { measurer.measure(f.value, baseValue).size.width }
                    val fit = if (textWidth > 0 && colWidthPx > 0f) (colWidthPx - 1f) / textWidth else 1f
                    val value = if (fit >= 1f) baseValue else baseValue.copy(fontSize = baseValue.fontSize * fit)
                    Column(Modifier.weight(weight)) {
                        Text(f.label, style = label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, softWrap = false)
                        // The Band value takes its band's display colour, as in the CA tables.
                        val tint = if (f.label == "Band") bandColor(f.value) else null
                        Text(f.value, style = value, color = tint ?: Color.Unspecified, maxLines = 1, softWrap = false)
                    }
                }
                val free = columns.drop(col).sum()
                if (free > 0f) Spacer(Modifier.weight(free))
            }
        }
    }
}

@Composable
private fun CarrierSection(group: CarrierGroup, title: Boolean = true) {
    if (title) SmallTitle(group.title, modifier = Modifier.padding(top = 2.dp), insideMargin = SectionTitleMargin)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (group.summary.isNotEmpty()) {
                Text(group.summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(4.dp))
            }
            if (group.table) CaTable(group.carriers, group.diag, group.nrTable, group.endc) else group.carriers.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.role, modifier = Modifier.width(76.dp), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 1)
                    c.band?.let { Text(it, modifier = Modifier.width(48.dp), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, color = bandColor(it) ?: MiuixTheme.colorScheme.onBackground) }
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
private fun CaTable(carriers: List<Carrier>, diag: Boolean, nr: Boolean = false, endc: Boolean = false, uplink: Boolean = false) = BoxWithConstraints {
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
        Text(v ?: "–", style = cellStyle, fontWeight = if (bold) TableEmphasis else null,
            color = if (v == null || v == "–") MiuixTheme.colorScheme.onSurfaceVariantSummary else color, maxLines = 1, softWrap = false)

    // The primary cells' roles (P; MCG and SCG in ENDC) are blue; the secondaries stay grey.
    row("", header = true) {
        text(it.role, color = if (it.role == "P" || it.role == "MCG" || it.role == "SCG") MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    if (uplink) { UplinkRows(::row, ::text, barMax, cellStyle); return@Column }
    if (nr) { NrRows(::row, ::text, barMax, cellStyle); return@Column }
    row("Band") { text(it.band, bold = true, color = bandColor(it.band) ?: MiuixTheme.colorScheme.onBackground) }
    row("BW") { text(it.bandwidthMhz?.let { b -> formatPlain(b) }) }
    // ENDC: EARFCN and NR-ARFCN share the row.
    row(if (endc) "ARFCN" else "EARFCN") { text(it.channel) }
    row("PCI") { text(it.pci?.toString()) }
    row("RSRP") { CellValueBar(it.rsrp, MetricKind.RSRP, barMax, style = cellStyle, trackWhenEmpty = true, gradientFill = true) }
    row("RSRQ") { CellValueBar(it.rsrq, MetricKind.RSRQ, barMax, style = cellStyle, trackWhenEmpty = true, gradientFill = true) }
    row("SINR") { CellValueBar(it.sinr, MetricKind.SINR, barMax, style = cellStyle, trackWhenEmpty = true, gradientFill = true) }
    // PDSCH rows from DIAG, over the last round (about a second). A carrier
    // with no grants this round has "–" for the per-grant values and 0.0 Mbps
    // PHY DL; PHY UL is "–" when the carrier sent nothing.
    // ENDC's NR columns fill the same rows: Tx/Rx is CSI-RS ports × Rx (as
    // the NR table's Port/Rx), Rank the mean layers.
    if (diag) {
        row("Tx/Rx") {
            // Tx held while the carrier idles (PortHold): "4×-" without an RX count.
            text(it.diag?.let { d -> if (d.txAntennas == null && d.rxAntennas == null) null else "${d.txAntennas ?: "-"}×${d.rxAntennas ?: "-"}" }
                ?: it.nr?.let { n -> if (n.csiPorts == null && n.rxAntennas == null) null else "${n.csiPorts ?: "-"}×${n.rxAntennas ?: "-"}" })
        }
        // No TxMode row in ENDC (NR has none).
        if (!endc) row("TxMode") { text(it.diag?.transmissionMode?.let { m -> "TM$m" }) }
        // LTE as NR: the mean scheduled layers; B126's rank when no TBs came.
        row("Rank") {
            text((it.diag?.let { d -> d.layers ?: d.rank?.toDouble() } ?: it.nr?.layers)?.let { l -> String.format(Locale.US, "%.1f", l) })
        }
        row("RBs") {
            val (value, fill) = rbCell(it)
            CellGradientBar(value, fill, barMax, cellStyle, trackWhenEmpty = true)
        }
        row("MCS") {
            val (value, fill) = mcsCell(it)
            CellGradientBar(value, fill, barMax, cellStyle, trackWhenEmpty = true)
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
    r("Band") { t(it.band, bold = true, color = bandColor(it.band) ?: fg) }
    r("BW") { t(it.bandwidthMhz?.let { b -> formatPlain(b) }) }
    r("NARFCN") { t(it.channel) }
    r("PCI") { t(it.pci?.toString()) }
    r("RSRP") { CellValueBar(it.rsrp, MetricKind.RSRP, barMax, Rat.NR, cellStyle, trackWhenEmpty = true, gradientFill = true) }
    r("RSRQ") { CellValueBar(it.rsrq, MetricKind.RSRQ, barMax, Rat.NR, cellStyle, trackWhenEmpty = true, gradientFill = true) }
    r("SINR") { CellValueBar(it.sinr, MetricKind.SINR, barMax, Rat.NR, cellStyle, trackWhenEmpty = true, gradientFill = true) }
    r("CSINR") { CellValueBar(it.nr?.csiSinr, MetricKind.SINR, barMax, Rat.NR, cellStyle, trackWhenEmpty = true, gradientFill = true) }
    // CSI-RS ports the network configured × the device's Rx antennas, e.g. "8×4".
    r("Port/Rx") { t(it.nr?.let { n -> if (n.csiPorts == null && n.rxAntennas == null) null else "${n.csiPorts ?: "-"}×${n.rxAntennas ?: "-"}" }) }
    r("Rank") { t(it.nr?.layers?.let { l -> String.format(Locale.US, "%.1f", l) }) }
    r("RBs") {
        val (value, fill) = rbCell(it)
        CellGradientBar(value, fill, barMax, cellStyle, trackWhenEmpty = true)
    }
    r("MCS") {
        val (value, fill) = mcsCell(it)
        CellGradientBar(value, fill, barMax, cellStyle, trackWhenEmpty = true)
    }
    // QAM order only ("256"); QPSK as is.
    r("Mod") { t(it.nr?.modulation?.removeSuffix("QAM")) }
    // Values coloured as the chart's lines.
    r("PHY DL") { t(it.nr?.let { d -> String.format(Locale.US, "%.1f", d.mbps) }, bold = true, color = DownlinkColor) }
    r("PHY UL") { t(it.nr?.ulMbps?.let { u -> String.format(Locale.US, "%.1f", u) }, bold = true, color = UplinkColor) }
}

/** The uplink card: the CA table's columns with the PUSCH rows. */
@Composable
private fun UplinkCard(carriers: List<Carrier>) {
    SmallTitle("Uplink", modifier = Modifier.padding(top = 2.dp), insideMargin = SectionTitleMargin)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            CaTable(carriers, diag = true, uplink = true)
        }
    }
}

/**
 * The uplink card's rows (DIAG), over the last round: LTE from B139 (RBs,
 * modulation), B16D (MCS) and B16E (power); NR from B883 and B884. Layers
 * and TX mode are NR only.
 */
@Composable
private fun UplinkRows(
    row: @Composable (String, Boolean, @Composable (Carrier) -> Unit) -> Unit,
    text: @Composable (String?, Boolean, Color) -> Unit,
    barMax: Dp,
    cellStyle: TextStyle
) {
    val fg = MiuixTheme.colorScheme.onBackground
    @Composable fun r(label: String, cell: @Composable (Carrier) -> Unit) = row(label, false, cell)
    @Composable fun t(v: String?, bold: Boolean = false, color: Color = fg) = text(v, bold, color)
    r("Band") { t(it.band, bold = true, color = bandColor(it.band) ?: fg) }
    r("RBs") {
        val (value, fill) = ulRbCell(it)
        CellGradientBar(value, fill, barMax, cellStyle, trackWhenEmpty = true)
    }
    r("MCS") {
        val (value, fill) = ulMcsCell(it)
        CellGradientBar(value, fill, barMax, cellStyle, trackWhenEmpty = true)
    }
    r("Mod") { t((it.diag?.ulModulation ?: it.nr?.ulModulation)?.removeSuffix("QAM")) }
    r("Layers") { t(it.nr?.ulLayers?.let { l -> String.format(Locale.US, "%.1f", l) }) }
    r("TX mode") { t(it.nr?.ulTxMode) }
    r("TX PWR") {
        CellValueBar(it.diag?.ulPower ?: it.nr?.ulPower, MetricKind.TX_POWER, barMax,
            if (it.nr != null) Rat.NR else Rat.LTE, cellStyle, trackWhenEmpty = true, gradientFill = true)
    }
    r("PHY UL") { t((it.diag?.ulMbps ?: it.nr?.ulMbps)?.let { u -> String.format(Locale.US, "%.1f", u) }, bold = true, color = UplinkColor) }
}

/**
 * The uplink card's columns: the carriers of the CA, NR or ENDC table (one
 * table for NSA, whatever the menu shows), the primaries (P, MCG, SCG)
 * always and an SCell only in a round it sent something. Empty without DIAG.
 */
internal fun uplinkCarriers(snap: NetworkSnapshot): List<Carrier> {
    val carriers = snap.endc?.carriers ?: snap.carrierGroups.filter { it.table }.flatMap { it.carriers }
    if (carriers.none { it.diag != null || it.nr != null }) return emptyList()
    return carriers.filter {
        it.role == "P" || it.role == "MCG" || it.role == "SCG" || it.diag?.hasUplink == true || it.nr?.hasUplink == true
    }
}

/** The uplink MCS cell of [c], as [mcsCell]. */
internal fun ulMcsCell(c: Carrier): Pair<String?, Float?> {
    val v = c.diag?.ulMcs ?: c.nr?.ulMcs ?: return null to null
    return Math.round(v).toString() to fillFraction(v, 31.0)
}

/** The uplink RBs cell of [c], as [rbCell]. */
internal fun ulRbCell(c: Carrier): Pair<String?, Float?> {
    val v = c.diag?.ulRbs ?: c.nr?.ulRbs ?: return null to null
    return Math.round(v).toString() to rbFraction(c.nr, v)
}

/**
 * The CA table's cell text style: body2 with equal-width digits, scaled down
 * when a column (minus its padding) is narrower than the widest value would be
 * at body2. The widest candidates are a signal value, a rate and a channel of
 * the longest channel's length, bold; the channel is measured as a template,
 * so the size doesn't change with the channel's digits (a band change).
 */
@Composable
private fun fittedCellStyle(carriers: List<Carrier>, columnWidth: Dp): TextStyle {
    val base = MiuixTheme.textStyles.body2.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val available = with(LocalDensity.current) { (columnWidth - 8.dp).toPx() }
    val digits = maxOf(6, carriers.maxOfOrNull { it.channel.length } ?: 0)
    val widest = remember(digits, base) {
        val bold = base.copy(fontWeight = FontWeight.SemiBold)
        listOf("-140.0", "1000.0", "8".repeat(digits)).maxOf { measurer.measure(it, bold).size.width }
    }
    val fit = if (widest > 0) available / widest else 1f
    return if (fit >= 1f) base else base.copy(fontSize = base.fontSize * fit)
}

/** Per-band display colour for a single LTE ("B3") or NR ("n41") band label. */
private fun bandColor(band: String?): Color? {
    val n = band?.trimStart('B', 'n')?.toIntOrNull() ?: return null
    return BAND_COLORS[n]?.let(::Color)
}

private val BAND_COLORS = mapOf(
    1 to 0xFFEE8700, 2 to 0xFF3F70EE, 3 to 0xFF81A83D, 4 to 0xFFFF01FC, 5 to 0xFFDFCA02, 6 to 0xFFBF9309,
    7 to 0xFFCB0481, 8 to 0xFFA96B2F, 9 to 0xFF5FEE1D, 10 to 0xFF077E1E, 11 to 0xFF49CDF0, 12 to 0xFF8B1CC3,
    13 to 0xFFCD6B95, 14 to 0xFF0FBB67, 15 to 0xFF510A3A, 16 to 0xFF93590C, 17 to 0xFFD5A8DE, 18 to 0xFF17F8B0,
    19 to 0xFF594783, 20 to 0xFF3DF7F0, 21 to 0xFF476668, 22 to 0xFF89B53A, 23 to 0xFFCB040D, 24 to 0xFF0D54DF,
    25 to 0xFF4FA3B1, 26 to 0xFF91F283, 27 to 0xFFD34156, 28 to 0xFF1CAF8C, 29 to 0xFF57E0FA, 30 to 0xFF03B00D,
    31 to 0xFF45FFDF, 32 to 0xFF9400BF, 33 to 0xFFC99D84, 34 to 0xFF0BED56, 35 to 0xFF4D3C29, 36 to 0xFF8F8BFB,
    37 to 0xFFD1DACD, 38 to 0xFF798CED, 39 to 0xFF557972, 40 to 0xFF117FDC, 41 to 0xFF439857, 42 to 0xFF85E729,
    43 to 0xFFC736FC, 44 to 0xFF0986CE, 45 to 0xFF4BD5A0, 46 to 0xFF0C00F2, 47 to 0xFFCF7345, 48 to 0xFF11C317,
    49 to 0xFF5312EA, 50 to 0xFFFFE1FC, 51 to 0xFF4131CF, 52 to 0xFF8380A1, 53 to 0xFFC5CF73, 54 to 0xFF071F46,
    55 to 0xFF496E18, 56 to 0xFF8BBDEA, 57 to 0xFFCD0CBD, 58 to 0xFF0F5C8F, 59 to 0xFF51AB61, 60 to 0xFFFD7A74,
    61 to 0xFF3FCA46, 62 to 0xFF811919, 63 to 0xFFC368EB, 64 to 0xFF05B8BD, 65 to 0xFF470790, 66 to 0xFFD18799,
    67 to 0xFFCBA534, 68 to 0xFF0DF506, 69 to 0xFF4F44D9, 70 to 0xFFFB13EC, 71 to 0xFF3D63BE, 72 to 0xFF7FB290,
    73 to 0xFFC10163, 74 to 0xFF035135, 75 to 0xFF45A007, 76 to 0xFF87EFD9, 77 to 0xFFC93EAC, 78 to 0xFFDD0000,
    79 to 0xFF4DDD50, 80 to 0xFFF9AC63, 81 to 0xFF3BFC35, 82 to 0xFF7D4B08, 83 to 0xFFBF9ADA, 84 to 0xFF01EAAC,
    85 to 0xFF43397F, 86 to 0xFF858851, 87 to 0xFFC7D723, 88 to 0xFF0927F6, 89 to 0xFF4B76C8, 90 to 0xFFF745DB,
    91 to 0xFF3995AD, 92 to 0xFF7BE47F, 93 to 0xFFBD3352, 94 to 0xFFFF8224, 95 to 0xFF41D2F6, 96 to 0xFF8321C9,
    97 to 0xFFC5709B, 98 to 0xFF07C06D, 99 to 0xFF490F40, 100 to 0xFFDFC84B, 101 to 0xFFFF5DC3, 102 to 0xFF1FF33A,
    103 to 0xFF3F88B2, 104 to 0xFF5F1D2A, 105 to 0xFF7FB2A1, 106 to 0xFF9F4719, 107 to 0xFFBFDC90, 108 to 0xFFDF7108,
    109 to 0xFFFF0680, 110 to 0xFFBFD7C6, 125 to 0xFF3FD097, 126 to 0xFF5F650F, 127 to 0xFF7FFA86, 250 to 0xFF5FE099,
    251 to 0xFF7F7511, 252 to 0xFF9F0A89, 253 to 0xFFBF9F00, 254 to 0xFFDF3478, 255 to 0xFFFFC9EF, 256 to 0xFF1F5F67,
    257 to 0xFFFF8B66, 258 to 0xFFFF8B66, 259 to 0xFF7F1ECE, 260 to 0xFF3FEF14, 261 to 0xFF5F848C, 262 to 0xFF7F1904,
    263 to 0xFF9FAE7B
)

/** CA / NR table values: the bar values, and the emphasised rows (Band,
 *  PHY DL / UL). Both normal weight; were Medium and SemiBold. */
private val TableValueWeight = FontWeight.Normal
private val TableEmphasis = FontWeight.Normal

/** A value with a level-coloured bar behind it. With [gradientFill] the fill
 *  fades from the full level colour toward a lighter tip — brighter than the
 *  MCS/RBs bars', whose colours read muted at their strength; the neighbour
 *  table's stays solid. */
@Composable
private fun CellValueBar(
    v: Double?, kind: MetricKind, maxWidth: Dp = Dp.Unspecified, rat: Rat = Rat.LTE,
    style: TextStyle = MiuixTheme.textStyles.body2, trackWhenEmpty: Boolean = false,
    gradientFill: Boolean = false
) {
    if (v == null) {
        // With [trackWhenEmpty] the empty track stays visible, so a bar cell
        // looks the same whether or not the value is there. Either way the
        // same vertical padding as the filled bar, so the row keeps its height.
        if (trackWhenEmpty) {
            EmptyTrack(maxWidth, style)
            return
        }
        Box(Modifier.padding(vertical = 1.dp)) {
            Text("–", style = style, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, softWrap = false)
        }
        return
    }
    val scale = signalScale(kind, rat)
    val color = signalLevelColor(scale.level(v))
    val fraction = scale.fraction(v)
    Box(
        Modifier.widthIn(max = maxWidth).fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .drawBehind {
                val w = size.width * fraction
                if (gradientFill)
                    drawRect(Brush.horizontalGradient(
                        listOf(color, color.copy(alpha = 0.65f)), endX = w),
                        size = Size(w, size.height))
                else
                    drawRect(color, size = Size(w, size.height))
            }
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(formatMetric(v), style = style, fontWeight = TableValueWeight, color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1, softWrap = false)
    }
}

/** The empty track pill with a grey dash: a bar cell with no value, kept at
 *  the filled bar's size. */
@Composable
private fun EmptyTrack(maxWidth: Dp, style: TextStyle) {
    Box(
        Modifier.widthIn(max = maxWidth).fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("–", style = style, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1, softWrap = false)
    }
}

/** A value with a fading blue bar behind it (the MCS/RBs rows): primary at
 * the fill's left end — a little softened, full primary read as too dark —
 * fading out toward the fill's right edge, over [fraction] of the cell's
 * width. A null [fraction] shows the plain value, like a cell with no bar;
 * a null [text] with [trackWhenEmpty] shows the empty track. */
@Composable
private fun CellGradientBar(
    text: String?, fraction: Float?, maxWidth: Dp, style: TextStyle, trackWhenEmpty: Boolean = false
) {
    if (text == null || fraction == null) {
        if (trackWhenEmpty && text == null) {
            EmptyTrack(maxWidth, style)
            return
        }
        // Same vertical padding as the bar below, so a row keeps its height
        // when a value (and with it the bar) appears or disappears.
        Box(Modifier.padding(vertical = 1.dp)) {
            Text(text ?: "–", style = style,
                color = if (text == null) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onBackground,
                maxLines = 1, softWrap = false)
        }
        return
    }
    val blue = MiuixTheme.colorScheme.primary
    Box(
        Modifier.widthIn(max = maxWidth).fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .drawBehind {
                val w = size.width * fraction
                drawRect(Brush.horizontalGradient(
                    listOf(blue.copy(alpha = 0.8f), blue.copy(alpha = 0.45f)), endX = w),
                    size = Size(w, size.height))
            }
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = style, fontWeight = TableValueWeight, color = MiuixTheme.colorScheme.onBackground,
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

/**
 * One DIAG carrier's RX chains: the carrier (RAT and role, band, channel and PCI), then a row per
 * chain with its RSRP, RSRQ and SINR (LTE, B193) or RSRP, SS-SINR and CSI-SINR (NR: B97F,
 * B8D8/B8DD), drawn as in the carrier tables.
 */
@Composable
private fun CarrierAntennaRows(a: AntennaGroup) = BoxWithConstraints {
    val grey = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val nr = a.rat == Rat.NR
    val labels = if (nr) listOf("RSRP", "SINR", "CSINR") else listOf("RSRP", "RSRQ", "SINR")
    val kinds =
        if (nr) listOf(MetricKind.RSRP, MetricKind.SINR, MetricKind.SINR)
        else listOf(MetricKind.RSRP, MetricKind.RSRQ, MetricKind.SINR)
    fun values(c: RxChain) = if (nr) listOf(c.rsrp, c.sinr, c.csiSinr) else listOf(c.rsrp, c.rsrq, c.sinr)
    // The label column is the carrier tables' width, so the chains line up with their rows.
    val colWidth = (maxWidth - 64.dp) / labels.size
    val barMax = colWidth - 6.dp
    val style = MiuixTheme.textStyles.body2.copy(fontFeatureSettings = "tnum")
    Column {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${a.rat.label} ${a.carrier}",
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            a.band?.let {
                Text(
                    "  $it",
                    style = MiuixTheme.textStyles.footnote1,
                    color = bandColor(it) ?: grey,
                    maxLines = 1,
                )
            }
            val detail = listOfNotNull(a.channel, a.pci?.let { "PCI $it" }).joinToString(" | ")
            if (detail.isNotEmpty())
                Text(
                    (if (a.band != null) " | " else "  ") + detail,
                    style = MiuixTheme.textStyles.footnote1,
                    color = grey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
        @Composable
        fun row(label: String, cell: @Composable (Int) -> Unit) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    modifier = Modifier.width(64.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = grey,
                )
                labels.indices.forEach { k ->
                    Box(Modifier.width(colWidth).padding(horizontal = 3.dp), Alignment.Center) { cell(k) }
                }
            }
        }
        row("") { k -> Text(labels[k], style = MiuixTheme.textStyles.footnote1, color = grey, maxLines = 1) }
        a.chains.forEach { c ->
            val v = values(c)
            row("RX${c.chain}") { k ->
                CellValueBar(v[k], kinds[k], barMax, a.rat, style, trackWhenEmpty = true, gradientFill = true)
            }
        }
    }
}

@Composable
private fun NeighborRows(neighbors: List<Neighbor>) {
    // Table: PCI | Band | EARFCN | RSRP bar | RSRQ bar, headers once,
    // rows already sorted, best RSRQ first. Band is derived from the channel.
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

