package dev.qcom.bandmenu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import dev.qcom.bandmenu.BandConstants
import dev.qcom.bandmenu.BandFilterState
import dev.qcom.bandmenu.HardwareBands
import dev.qcom.bandmenu.ModemState
import dev.qcom.bandmenu.NrMode
import dev.qcom.bandmenu.RatType
import dev.qcom.bandmenu.SimBandFilter
import dev.qcom.bandmenu.SimState
import dev.qcom.bandmenu.UsageMode
import dev.qcom.bandmenu.copyForOtherSim
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import kotlinx.coroutines.delay

private class SlotBandState {
    val ratChecked = mutableStateMapOf<RatType, Boolean>()
    val gsmChecked = mutableStateMapOf<Int, Boolean>()
    val wcdmaChecked = mutableStateMapOf<Int, Boolean>()
    val lteChecked = mutableStateMapOf<Int, Boolean>()
    val nrNsaChecked = mutableStateMapOf<Int, Boolean>()
    val nrSaChecked = mutableStateMapOf<Int, Boolean>()
    val nrChecked = mutableStateMapOf<Int, Boolean>()
    var nrMode by mutableStateOf(NrMode.BOTH)
    var usageMode by mutableStateOf(UsageMode.UNKNOWN)
}

@Composable
fun BandLockScreen(
    modemState: ModemState?,
    isLoading: Boolean,
    refreshingSlots: Set<Int>,
    onRefresh: (Int) -> Unit,
    refreshKey0: Int,
    refreshKey1: Int,
    onApply: (Int, SimState) -> Unit,
    onReset: (Int) -> Unit,
    onDetach: () -> Unit = {},
    onAttach: () -> Unit = {},
    onReattach: () -> Unit = {},
    nrIndependentSupported: Boolean? = null,
    simLabels: List<String> = listOf("SIM 1", "SIM 2"),
    bandFilter: BandFilterState = BandFilterState(),
    onSaveFilter: (BandFilterState) -> Unit = {},
    onClearFilter: () -> Unit = {},
    quickSelectHidden: Boolean = false,
    onToggleQuickSelect: () -> Unit = {},
    usageSettingHidden: Boolean = false,
    onToggleUsageSetting: () -> Unit = {},
    nrModeHidden: Boolean = false,
    onToggleNrMode: () -> Unit = {},
    snackbarHostState: SnackbarHostState,
    backdrop: Backdrop? = null
) {
    val density = LocalDensity.current
    val navbarHeightDp = 64.dp
    val navInset = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val navbarSpace = navbarHeightDp + 16.dp + navInset
    val applyResetSpace = 72.dp
    // Match the overlay SmallTopAppBar's own inset source (systemBars top) so
    // the content offset equals the bar's real height in every window mode.
    // statusBars alone is equal in full screen but diverges in a floating /
    // freeform window, which made the bar overlap the SIM tab row.
    val topBarInset = WindowInsets.systemBars.only(WindowInsetsSides.Top)
        .asPaddingValues(density).calculateTopPadding()
    val topBarHeight = topBarInset + TopAppBarDefaults.CollapsedHeight

    val hapticFeedback = LocalHapticFeedback.current

    // Content-only capture layer: the top bar (a sibling of this subtree) samples
    // and blurs it. The bar itself is never part of the captured layer, so the
    // blur cannot self-reference (the crash from the 0fefeb5 attempt).
    val surfaceColor = MiuixTheme.colorScheme.surface
    val screenBackdrop = rememberLayerBackdrop(onDraw = {
        drawRect(surfaceColor)
        drawContent()
    })

    val hardware = modemState?.hardware
    val useIndependentLock = nrIndependentSupported == true

    var selectedSim by remember { mutableIntStateOf(0) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val slotStates = remember { arrayOf(SlotBandState(), SlotBandState()) }

    // HorizontalPager is as tall as its tallest page, which keeps scrollable
    // blank space below a SIM page whose filter hides entire RATs. Track each
    // page's natural height and size the pager to the current page instead.
    val pageHeights = remember { mutableStateMapOf<Int, Int>() }
    val pagerHeight = pageHeights[pagerState.currentPage]?.let { with(density) { it.toDp() } }

    LaunchedEffect(selectedSim) {
        if (pagerState.targetPage != selectedSim) {
            pagerState.animateScrollToPage(selectedSim)
        }
    }
    LaunchedEffect(pagerState.targetPage) {
        selectedSim = pagerState.targetPage
    }

    // SmallTopAppBar owns the top system-bar inset. Applying Scaffold's top
    // padding to this parent would count the status bar twice.
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading || hardware == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                PullToRefresh(
                    isRefreshing = refreshingSlots.contains(selectedSim),
                    onRefresh = { onRefresh(selectedSim) },
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(screenBackdrop),
                    contentPadding = PaddingValues(top = topBarHeight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = topBarHeight)
                            .padding(bottom = navbarSpace + applyResetSpace)
                    ) {
                        TabRowWithContour(
                            tabs = simLabels,
                            selectedTabIndex = selectedSim,
                            onTabSelected = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                selectedSim = it
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            userScrollEnabled = true,
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (pagerHeight != null) {
                                        Modifier.height(pagerHeight)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) { page ->
                            val simState = if (page == 0) modemState!!.sim1 else modemState!!.sim2
                            val refreshKey = if (page == 0) refreshKey0 else refreshKey1
                            Column(
                                modifier = Modifier
                                    // Measure the page content without the pager's height
                                    // constraint so its natural height is tracked even when
                                    // the pager is sized to the other page.
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                    .onSizeChanged { size -> pageHeights[page] = size.height }
                            ) {
                                if (page == 1 && modemState?.sim2Available == false) {
                                    Sim2UnavailableNote()
                                } else SimBandLockPage(
                                    state = slotStates[page],
                                    simState = simState,
                                    hardware = hardware,
                                    useIndependentLock = useIndependentLock,
                                    filter = SimBandFilter.effectiveFilter(bandFilter, hardware, page),
                                    refreshKey = refreshKey,
                                    showQuickSelect = !quickSelectHidden,
                                    showUsageSetting = !usageSettingHidden,
                                    showNrMode = !nrModeHidden
                                )
                            }
                        }
                    }
                }

                if (backdrop != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawPlainBackdrop(
                                backdrop = screenBackdrop,
                                shape = { RectangleShape },
                                effects = { blur(8f.dp.toPx()) },
                                onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.5f)) }
                            )
                    ) {
                        SmallTopAppBar(
                            title = "Bands",
                            color = Color.Transparent
                        )
                    }
                } else {
                    SmallTopAppBar(title = "Bands")
                }

                // Sits in the bar row, left of the centered title, so an active
                // filter no longer adds a second row under the bar.
                if (bandFilter.enabled) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = topBarInset, start = 20.dp)
                            .height(TopAppBarDefaults.CollapsedHeight),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        FilterActiveHint()
                    }
                }

                val menuEntries = buildList {
                    add(DropdownEntry(items = listOf(
                        DropdownItem(
                            text = "Filter shown bands",
                            onClick = { showFilterSheet = true }
                        )
                    )))
                    if (bandFilter.enabled) {
                        add(DropdownEntry(items = listOf(
                            DropdownItem(
                                text = "Clear filter",
                                onClick = { onClearFilter() }
                            )
                        )))
                    }
                    // Same top-to-bottom order as the sections on the page.
                    add(DropdownEntry(items = buildList {
                        if (hardware.nr.isNotEmpty()) {
                            add(DropdownItem(
                                text = if (nrModeHidden) "Show NR mode" else "Hide NR mode",
                                onClick = onToggleNrMode
                            ))
                        }
                        add(DropdownItem(
                            text = if (usageSettingHidden) "Show usage setting" else "Hide usage setting",
                            onClick = onToggleUsageSetting
                        ))
                        add(DropdownItem(
                            text = if (quickSelectHidden) "Show quick select" else "Hide quick select",
                            onClick = onToggleQuickSelect
                        ))
                    }))
                    add(DropdownEntry(items = listOf(
                        DropdownItem("Detach", onClick = onDetach),
                        DropdownItem("Attach", onClick = onAttach),
                        DropdownItem("Re-Attach", onClick = onReattach)
                    )))
                }
                WindowIconDropdownMenu(
                    entries = menuEntries,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(top = topBarInset + 8.dp, end = 8.dp),
                    collapseOnSelection = true
                ) {
                    Icon(
                        imageVector = MiuixIcons.More,
                        contentDescription = "Menu",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }

            if (!(selectedSim == 1 && modemState?.sim2Available == false)) Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navbarSpace + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "Reset",
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        onReset(selectedSim)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                )
                Button(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        val s = slotStates[selectedSim]
                        val f = SimBandFilter.effectiveFilter(bandFilter, hardware, selectedSim)
                        val visibleGsm = SimBandFilter.visibleBands(hardware.gsm, f.gsm)
                        val visibleWcdma = SimBandFilter.visibleBands(hardware.wcdma, f.wcdma)
                        val visibleLte = SimBandFilter.visibleBands(hardware.lte, f.lte)
                        val visibleNsa = SimBandFilter.visibleBands(hardware.nr, f.nrNsa)
                        val visibleSa = SimBandFilter.visibleBands(hardware.nr, f.nrSa)
                        val visibleNr = SimBandFilter.visibleBands(hardware.nr, f.nrNsa + f.nrSa)
                        val nrBands = s.nrChecked.filterValues { it }.keys.intersect(visibleNr)
                        val state = SimState(
                            ratMask = s.ratChecked.filterValues { it }.keys,
                            gsmBands = s.gsmChecked.filterValues { it }.keys.intersect(visibleGsm),
                            wcdmaBands = s.wcdmaChecked.filterValues { it }.keys.intersect(visibleWcdma),
                            lteBands = s.lteChecked.filterValues { it }.keys.intersect(visibleLte),
                            nrNsaBands = if (useIndependentLock) s.nrNsaChecked.filterValues { it }.keys.intersect(visibleNsa) else nrBands,
                            nrSaBands = if (useIndependentLock) s.nrSaChecked.filterValues { it }.keys.intersect(visibleSa) else nrBands,
                            nrMode = s.nrMode,
                            usageMode = s.usageMode
                        )
                        onApply(selectedSim, state)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("Apply")
                }
            }

            BandFilterSheet(
                show = showFilterSheet,
                hardware = hardware,
                bandFilter = bandFilter,
                useIndependentLock = useIndependentLock,
                onDismiss = { showFilterSheet = false },
                onSave = { state ->
                    onSaveFilter(state)
                    showFilterSheet = false
                }
            )
        }
    }
}

@Composable
private fun SimBandLockPage(
    state: SlotBandState,
    simState: SimState?,
    hardware: HardwareBands,
    useIndependentLock: Boolean,
    filter: SimBandFilter,
    refreshKey: Int,
    showQuickSelect: Boolean,
    showUsageSetting: Boolean,
    showNrMode: Boolean
) {
    val hapticFeedback = LocalHapticFeedback.current

    // Remembered so the sets and sorted lists keep their identity across
    // recompositions: BandCheckboxGrid then skips, and a checkbox tap only
    // recomposes the grid whose map changed instead of the whole page.
    val visibleGsm = remember(hardware, filter) { SimBandFilter.visibleBands(hardware.gsm, filter.gsm) }
    val visibleWcdma = remember(hardware, filter) { SimBandFilter.visibleBands(hardware.wcdma, filter.wcdma) }
    val visibleLte = remember(hardware, filter) { SimBandFilter.visibleBands(hardware.lte, filter.lte) }
    val visibleNsa = remember(hardware, filter) { SimBandFilter.visibleBands(hardware.nr, filter.nrNsa) }
    val visibleSa = remember(hardware, filter) { SimBandFilter.visibleBands(hardware.nr, filter.nrSa) }
    val visibleNr = remember(hardware, filter) { SimBandFilter.visibleBands(hardware.nr, filter.nrNsa + filter.nrSa) }
    val sortedGsm = remember(visibleGsm) { visibleGsm.sorted() }
    val sortedWcdma = remember(visibleWcdma) { visibleWcdma.sorted() }
    val sortedLte = remember(visibleLte) { visibleLte.sorted() }
    val sortedNsa = remember(visibleNsa) { visibleNsa.sorted() }
    val sortedSa = remember(visibleSa) { visibleSa.sorted() }
    val sortedNr = remember(visibleNr) { visibleNr.sorted() }

    LaunchedEffect(simState, refreshKey) {
        simState?.let { s ->
            state.ratChecked.clear()
            BandConstants.ALL_RAT_TYPES.forEach { rt ->
                state.ratChecked[rt] = s.ratMask.contains(rt)
            }
            state.gsmChecked.clear()
            s.gsmBands.forEach { state.gsmChecked[it] = true }
            state.wcdmaChecked.clear()
            s.wcdmaBands.forEach { state.wcdmaChecked[it] = true }
            state.lteChecked.clear()
            s.lteBands.forEach { state.lteChecked[it] = true }
            state.nrNsaChecked.clear()
            s.nrNsaBands.forEach { state.nrNsaChecked[it] = true }
            state.nrSaChecked.clear()
            s.nrSaBands.forEach { state.nrSaChecked[it] = true }
            state.nrChecked.clear()
            (s.nrNsaBands + s.nrSaBands).forEach { state.nrChecked[it] = true }
            state.nrMode = s.nrMode
            state.usageMode = s.usageMode
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        val supportedRats = BandConstants.ALL_RAT_TYPES.filter { rt ->
            when (rt) {
                RatType.GSM -> hardware.gsm.isNotEmpty()
                RatType.WCDMA -> hardware.wcdma.isNotEmpty()
                RatType.LTE -> hardware.lte.isNotEmpty()
                RatType.NR -> hardware.nr.isNotEmpty()
            }
        }
        val isAuto = supportedRats.all { state.ratChecked[it] == true }
        val ratSummary = if (isAuto && supportedRats.isNotEmpty()) "AUTO (All RATs)"
            else if (state.ratChecked.values.all { it != true } || supportedRats.isEmpty()) "None"
            else supportedRats.filter { state.ratChecked[it] == true }.joinToString(", ") { it.name }
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            WindowDropdownPreference(
                entries = listOf(
                    DropdownEntry(items = listOf(
                        DropdownItem(
                            text = "AUTO (All RATs)",
                            selected = isAuto,
                            onClick = {
                                val newAuto = !isAuto
                                supportedRats.forEach { state.ratChecked[it] = newAuto }
                            }
                        )
                    )),
                    DropdownEntry(items = supportedRats.map { rt ->
                        DropdownItem(
                            text = rt.name,
                            selected = state.ratChecked[rt] == true,
                            onClick = {
                                state.ratChecked[rt] = !(state.ratChecked[rt] == true)
                            }
                        )
                    })
                ),
                title = "RAT lock",
                summary = ratSummary,
                showValue = false,
                collapseOnSelection = false
            )
        }

        if (showNrMode && hardware.nr.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            SmallTitle("NR mode")
            val nrModeIndex = when (state.nrMode) {
                NrMode.BOTH, NrMode.UNKNOWN -> 0
                NrMode.SA -> 1
                NrMode.NSA -> 2
            }
            val nrModeEnabled = useIndependentLock
            TabRowWithContour(
                tabs = listOf("SA/NSA", "SA", "NSA"),
                selectedTabIndex = nrModeIndex,
                onTabSelected = { index ->
                    if (nrModeEnabled) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        state.nrMode = when (index) {
                            0 -> NrMode.BOTH
                            1 -> NrMode.SA
                            2 -> NrMode.NSA
                            else -> NrMode.BOTH
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (nrModeEnabled) Modifier else Modifier.alpha(0.4f))
            )
        }

        if (showUsageSetting) {
            Spacer(modifier = Modifier.height(16.dp))
            SmallTitle("Usage setting")
            val usageEnabled = state.usageMode != UsageMode.UNKNOWN
            TabRowWithContour(
                tabs = listOf("Voice-centric", "Data-centric"),
                selectedTabIndex = if (state.usageMode == UsageMode.DATA) 1 else 0,
                onTabSelected = { index ->
                    if (usageEnabled) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        state.usageMode = if (index == 1) UsageMode.DATA else UsageMode.VOICE
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (usageEnabled) Modifier else Modifier.alpha(0.4f))
            )
        }

        // Separates the RAT settings from Quick selections; without that card
        // the 8 dp spacer before the band sections is gap enough.
        if (showQuickSelect) Spacer(modifier = Modifier.height(16.dp))

        // Derived, so the page recomposes only when a flag flips, not on every
        // map write. Event handlers must read a flag once before mutating the
        // maps: a derived flag re-evaluates as soon as the first band changes.
        val allNrEnabled by remember(state, visibleNr, useIndependentLock) {
            derivedStateOf {
                if (useIndependentLock)
                    visibleNr.all { state.nrNsaChecked[it] == true } && visibleNr.all { state.nrSaChecked[it] == true }
                else
                    visibleNr.all { state.nrChecked[it] == true }
            }
        }
        val allNsaEnabled by remember(state, visibleNsa, useIndependentLock) {
            derivedStateOf {
                if (useIndependentLock) visibleNsa.all { state.nrNsaChecked[it] == true } else allNrEnabled
            }
        }
        val allSaEnabled by remember(state, visibleSa, useIndependentLock) {
            derivedStateOf {
                if (useIndependentLock) visibleSa.all { state.nrSaChecked[it] == true } else allNrEnabled
            }
        }
        val allLteEnabled by remember(state, visibleLte) {
            derivedStateOf { visibleLte.all { state.lteChecked[it] == true } }
        }
        val allWcdmaEnabled by remember(state, visibleWcdma) {
            derivedStateOf { visibleWcdma.all { state.wcdmaChecked[it] == true } }
        }
        val allGsmEnabled by remember(state, visibleGsm) {
            derivedStateOf { visibleGsm.all { state.gsmChecked[it] == true } }
        }
        val all5gEnabled = allNrEnabled
        val allBandsEnabled = allGsmEnabled && allWcdmaEnabled && allLteEnabled && allNrEnabled

        var recentlyClicked by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
        LaunchedEffect(recentlyClicked) {
            if (recentlyClicked != null) {
                delay(2000)
                recentlyClicked = null
            }
        }
        fun emojiFor(index: Int): String {
            val rc = recentlyClicked ?: return ""
            if (rc.first != index) return ""
            return if (rc.second) " ✓" else " ✗"
        }

        val hasNrHardware = hardware.nr.isNotEmpty()
        val quickItems = if (useIndependentLock) {
            if (hasNrHardware) {
                listOf(
                    "All bands (all RATs)" to 0,
                    "All 5G bands (NSA+SA)" to 1,
                    "All NR-SA bands" to 2,
                    "All NR-NSA bands" to 3,
                    "All LTE bands" to 4,
                    "All WCDMA bands" to 5,
                    "All GSM bands" to 6
                )
            } else {
                listOf(
                    "All bands (all RATs)" to 0,
                    "All LTE bands" to 4,
                    "All WCDMA bands" to 5,
                    "All GSM bands" to 6
                )
            }
        } else {
            if (hasNrHardware) {
                listOf(
                    "All bands (all RATs)" to 0,
                    "All NR bands" to 1,
                    "All LTE bands" to 4,
                    "All WCDMA bands" to 5,
                    "All GSM bands" to 6
                )
            } else {
                listOf(
                    "All bands (all RATs)" to 0,
                    "All LTE bands" to 4,
                    "All WCDMA bands" to 5,
                    "All GSM bands" to 6
                )
            }
        }

        val quickGroups = listOf(
            quickItems.filter { it.second == 0 },
            quickItems.filter { it.second in 1..3 },
            quickItems.filter { it.second in 4..6 }
        ).filter { it.isNotEmpty() }

        if (showQuickSelect) Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            WindowDropdownPreference(
                entries = quickGroups.map { group ->
                    DropdownEntry(items = group.map { (label, idx) ->
                        DropdownItem(text = "$label${emojiFor(idx)}", onClick = {
                            when (idx) {
                                0 -> {
                                    val newState = !allBandsEnabled
                                    visibleGsm.forEach { state.gsmChecked[it] = newState }
                                    visibleWcdma.forEach { state.wcdmaChecked[it] = newState }
                                    visibleLte.forEach { state.lteChecked[it] = newState }
                                    if (useIndependentLock) {
                                        visibleNr.forEach {
                                            state.nrNsaChecked[it] = newState; state.nrSaChecked[it] = newState
                                        }
                                    } else {
                                        visibleNr.forEach { state.nrChecked[it] = newState }
                                    }
                                    recentlyClicked = idx to newState
                                }
                                1 -> {
                                    val newState = !all5gEnabled
                                    if (useIndependentLock) {
                                        visibleNr.forEach {
                                            state.nrNsaChecked[it] = newState; state.nrSaChecked[it] = newState
                                        }
                                    } else {
                                        visibleNr.forEach { state.nrChecked[it] = newState }
                                    }
                                    recentlyClicked = idx to newState
                                }
                                2 -> {
                                    val newState = !allSaEnabled
                                    visibleSa.forEach { state.nrSaChecked[it] = newState }
                                    recentlyClicked = idx to newState
                                }
                                3 -> {
                                    val newState = !allNsaEnabled
                                    visibleNsa.forEach { state.nrNsaChecked[it] = newState }
                                    recentlyClicked = idx to newState
                                }
                                4 -> {
                                    val newState = !allLteEnabled
                                    visibleLte.forEach { state.lteChecked[it] = newState }
                                    recentlyClicked = idx to newState
                                }
                                5 -> {
                                    val newState = !allWcdmaEnabled
                                    visibleWcdma.forEach { state.wcdmaChecked[it] = newState }
                                    recentlyClicked = idx to newState
                                }
                                6 -> {
                                    val newState = !allGsmEnabled
                                    visibleGsm.forEach { state.gsmChecked[it] = newState }
                                    recentlyClicked = idx to newState
                                }
                            }
                        })
                    })
                },
                title = "Quick selections",
                summary = "Toggle band groups",
                showValue = false,
                collapseOnSelection = false
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (visibleNr.isNotEmpty()) {
            if (useIndependentLock) {
                if (visibleSa.isNotEmpty()) {
                    val modified = !allSaEnabled
                    BandSectionTitle(
                        text = "NR-SA",
                        hapticFeedback = hapticFeedback,
                        onToggleAll = { val v = !allSaEnabled; visibleSa.forEach { state.nrSaChecked[it] = v } }
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (modified)
                            CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer)
                        else CardDefaults.defaultColors()
                    ) {
                        BandCheckboxGrid(sortedSa, state.nrSaChecked, "n")
                    }
                }
                if (visibleNsa.isNotEmpty()) {
                    val modified = !allNsaEnabled
                    BandSectionTitle(
                        text = "NR-NSA",
                        hapticFeedback = hapticFeedback,
                        onToggleAll = { val v = !allNsaEnabled; visibleNsa.forEach { state.nrNsaChecked[it] = v } }
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (modified)
                            CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer)
                        else CardDefaults.defaultColors()
                    ) {
                        BandCheckboxGrid(sortedNsa, state.nrNsaChecked, "n")
                    }
                }
            } else {
                val modified = !allNrEnabled
                BandSectionTitle(
                    text = "NR",
                    hapticFeedback = hapticFeedback,
                    onToggleAll = { val v = !allNrEnabled; visibleNr.forEach { state.nrChecked[it] = v } }
                )
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = if (modified)
                        CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer)
                    else CardDefaults.defaultColors()
                ) {
                    BandCheckboxGrid(sortedNr, state.nrChecked, "n")
                }
            }
        }
        if (visibleLte.isNotEmpty()) {
            val modified = !allLteEnabled
            BandSectionTitle(
                text = "LTE",
                hapticFeedback = hapticFeedback,
                onToggleAll = { val v = !allLteEnabled; visibleLte.forEach { state.lteChecked[it] = v } }
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = if (modified)
                    CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer)
                else CardDefaults.defaultColors()
            ) {
                BandCheckboxGrid(sortedLte, state.lteChecked, "B")
            }
        }
        if (visibleWcdma.isNotEmpty()) {
            val modified = !allWcdmaEnabled
            BandSectionTitle(
                text = "WCDMA",
                hapticFeedback = hapticFeedback,
                onToggleAll = { val v = !allWcdmaEnabled; visibleWcdma.forEach { state.wcdmaChecked[it] = v } }
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = if (modified)
                    CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer)
                else CardDefaults.defaultColors()
            ) {
                BandCheckboxGrid(sortedWcdma, state.wcdmaChecked, "B")
            }
        }
        if (visibleGsm.isNotEmpty()) {
            val modified = !allGsmEnabled
            BandSectionTitle(
                text = "GSM",
                hapticFeedback = hapticFeedback,
                onToggleAll = { val v = !allGsmEnabled; visibleGsm.forEach { state.gsmChecked[it] = v } }
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = if (modified)
                    CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer)
                else CardDefaults.defaultColors()
            ) {
                BandCheckboxGrid(sortedGsm, state.gsmChecked, "")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private val BandCellVerticalPadding = 4.dp

/**
 * One band in a checkbox grid. The whole cell (circle and label) is the
 * tap target, not only the 26 dp circle; the Checkbox itself is display-only.
 */
@Composable
private fun BandCheckboxCell(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = {
                    onToggle()
                    haptic.performHapticFeedback(
                        if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                    )
                }
            )
            .padding(vertical = BandCellVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = null
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun BandCheckboxGrid(
    bands: List<Int>,
    checked: MutableMap<Int, Boolean>,
    prefix: String
) {
    // Row spacing comes from the cells' own vertical padding, so the gap
    // between rows is part of the tap target rather than a dead zone.
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp - BandCellVerticalPadding)) {
        bands.chunked(4).forEach { group ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                group.forEach { band ->
                    val isChecked = checked[band] == true
                    BandCheckboxCell(
                        label = "$prefix$band",
                        checked = isChecked,
                        onToggle = { checked[band] = !isChecked },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - group.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BandFilterSheet(
    show: Boolean,
    hardware: HardwareBands,
    bandFilter: BandFilterState,
    useIndependentLock: Boolean,
    onDismiss: () -> Unit,
    onSave: (BandFilterState) -> Unit
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    var sim1Selection by remember { mutableStateOf(SimBandFilter()) }
    var sim2Selection by remember { mutableStateOf(SimBandFilter()) }
    val sheetPagerState = rememberPagerState(pageCount = { 2 })
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(tabIndex) {
        if (sheetPagerState.targetPage != tabIndex) {
            sheetPagerState.animateScrollToPage(tabIndex)
        }
    }
    LaunchedEffect(sheetPagerState.targetPage) {
        tabIndex = sheetPagerState.targetPage
    }

    LaunchedEffect(show) {
        if (show) {
            tabIndex = 0
            fun prefill(f: SimBandFilter): SimBandFilter {
                // "Clear filter" only disables the filter and keeps the saved
                // selection, so reopening the sheet shows the last ticked bands
                // and Save brings that filter straight back. Fall back to all
                // hardware bands only when nothing was ever saved for this SIM.
                val hasSaved = f.gsm.isNotEmpty() || f.wcdma.isNotEmpty() || f.lte.isNotEmpty() ||
                    f.nrNsa.isNotEmpty() || f.nrSa.isNotEmpty()
                if (!bandFilter.enabled && !hasSaved) {
                    return SimBandFilter(hardware.gsm, hardware.wcdma, hardware.lte, hardware.nr, hardware.nr)
                }
                return SimBandFilter(
                    gsm = f.gsm,
                    wcdma = f.wcdma,
                    lte = f.lte,
                    nrNsa = f.nrNsa,
                    nrSa = f.nrSa
                )
            }
            sim1Selection = prefill(bandFilter.sim1)
            sim2Selection = prefill(bandFilter.sim2)
        }
    }

    val selection = if (tabIndex == 0) sim1Selection else sim2Selection
    val selectedCount = selection.gsm.size + selection.wcdma.size + selection.lte.size +
        (if (useIndependentLock) selection.nrNsa.size + selection.nrSa.size else selection.nrNsa.size)

    WindowBottomSheet(
        title = "Filter shown bands",
        show = show,
        onDismissRequest = onDismiss,
        startAction = {
            val dismissState = LocalDismissState.current
            IconButton(onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                dismissState?.invoke()
            }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "Cancel",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
        },
        endAction = {
            val dismissState = LocalDismissState.current
            val saveEnabled = selectedCount > 0
            val saveTint by animateColorAsState(
                targetValue = if (saveEnabled) MiuixTheme.colorScheme.onBackground
                else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                label = "saveTint"
            )
            IconButton(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSave(BandFilterState(enabled = true, sim1 = sim1Selection, sim2 = sim2Selection))
                    dismissState?.invoke()
                },
                enabled = saveEnabled
            ) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = "Save",
                    tint = saveTint
                )
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
                .scrollEndHaptic()
                .overScrollVertical()
        ) {
            item {
                TabRowWithContour(
                    tabs = listOf("SIM 1", "SIM 2"),
                    selectedTabIndex = tabIndex,
                    onTabSelected = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        tabIndex = it
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                CopySelectionRow(
                    label = if (tabIndex == 0) "Copy selection to SIM 2" else "Copy selection to SIM 1",
                    hapticFeedback = hapticFeedback
                ) {
                    if (tabIndex == 0) {
                        sim2Selection = sim1Selection.copyForOtherSim()
                    } else {
                        sim1Selection = sim2Selection.copyForOtherSim()
                    }
                }
            }
            item {
                HorizontalPager(
                    state = sheetPagerState,
                    beyondViewportPageCount = 1,
                    userScrollEnabled = true,
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    SheetSimSections(
                        hardware = hardware,
                        selection = if (page == 0) sim1Selection else sim2Selection,
                        useIndependentLock = useIndependentLock,
                        hapticFeedback = hapticFeedback,
                        onUpdate = { next -> if (page == 0) sim1Selection = next else sim2Selection = next }
                    )
                }
            }
            item {
                Spacer(
                    Modifier.padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
        }
    }
}

@Composable
private fun SheetSimSections(
    hardware: HardwareBands,
    selection: SimBandFilter,
    useIndependentLock: Boolean,
    hapticFeedback: HapticFeedback,
    onUpdate: (SimBandFilter) -> Unit
) {
    Column {
        if (hardware.nr.isNotEmpty()) {
            if (useIndependentLock) {
                FilterSectionTitle(
                    text = "NR-SA",
                    allSelected = selection.nrSa.containsAll(hardware.nr),
                    hapticFeedback = hapticFeedback
                ) {
                    if (selection.nrSa.containsAll(hardware.nr)) {
                        onUpdate(selection.copy(nrSa = emptySet()))
                    } else {
                        onUpdate(selection.copy(nrSa = hardware.nr.toSet()))
                    }
                }
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FilterCheckboxGrid(hardware.nr.sorted(), selection.nrSa, "n") { band ->
                        val s = selection.nrSa.toMutableSet()
                        if (!s.add(band)) s.remove(band)
                        onUpdate(selection.copy(nrSa = s))
                    }
                }
                FilterSectionTitle(
                    text = "NR-NSA",
                    allSelected = selection.nrNsa.containsAll(hardware.nr),
                    hapticFeedback = hapticFeedback
                ) {
                    if (selection.nrNsa.containsAll(hardware.nr)) {
                        onUpdate(selection.copy(nrNsa = emptySet()))
                    } else {
                        onUpdate(selection.copy(nrNsa = hardware.nr.toSet()))
                    }
                }
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FilterCheckboxGrid(hardware.nr.sorted(), selection.nrNsa, "n") { band ->
                        val s = selection.nrNsa.toMutableSet()
                        if (!s.add(band)) s.remove(band)
                        onUpdate(selection.copy(nrNsa = s))
                    }
                }
            } else {
                FilterSectionTitle(
                    text = "NR",
                    allSelected = selection.nrNsa.containsAll(hardware.nr),
                    hapticFeedback = hapticFeedback
                ) {
                    if (selection.nrNsa.containsAll(hardware.nr)) {
                        onUpdate(selection.copy(nrNsa = emptySet(), nrSa = emptySet()))
                    } else {
                        val s = hardware.nr.toSet()
                        onUpdate(selection.copy(nrNsa = s, nrSa = s))
                    }
                }
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FilterCheckboxGrid(hardware.nr.sorted(), selection.nrNsa, "n") { band ->
                        val s = selection.nrNsa.toMutableSet()
                        if (!s.add(band)) s.remove(band)
                        onUpdate(selection.copy(nrNsa = s, nrSa = s))
                    }
                }
            }
        }
        if (hardware.lte.isNotEmpty()) {
            FilterSectionTitle(
                text = "LTE",
                allSelected = selection.lte.containsAll(hardware.lte),
                hapticFeedback = hapticFeedback
            ) {
                if (selection.lte.containsAll(hardware.lte)) {
                    onUpdate(selection.copy(lte = emptySet()))
                } else {
                    onUpdate(selection.copy(lte = hardware.lte.toSet()))
                }
            }
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                FilterCheckboxGrid(hardware.lte.sorted(), selection.lte, "B") { band ->
                    val s = selection.lte.toMutableSet()
                    if (!s.add(band)) s.remove(band)
                    onUpdate(selection.copy(lte = s))
                }
            }
        }
        if (hardware.wcdma.isNotEmpty()) {
            FilterSectionTitle(
                text = "WCDMA",
                allSelected = selection.wcdma.containsAll(hardware.wcdma),
                hapticFeedback = hapticFeedback
            ) {
                if (selection.wcdma.containsAll(hardware.wcdma)) {
                    onUpdate(selection.copy(wcdma = emptySet()))
                } else {
                    onUpdate(selection.copy(wcdma = hardware.wcdma.toSet()))
                }
            }
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                FilterCheckboxGrid(hardware.wcdma.sorted(), selection.wcdma, "B") { band ->
                    val s = selection.wcdma.toMutableSet()
                    if (!s.add(band)) s.remove(band)
                    onUpdate(selection.copy(wcdma = s))
                }
            }
        }
        if (hardware.gsm.isNotEmpty()) {
            FilterSectionTitle(
                text = "GSM",
                allSelected = selection.gsm.containsAll(hardware.gsm),
                hapticFeedback = hapticFeedback
            ) {
                if (selection.gsm.containsAll(hardware.gsm)) {
                    onUpdate(selection.copy(gsm = emptySet()))
                } else {
                    onUpdate(selection.copy(gsm = hardware.gsm.toSet()))
                }
            }
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                FilterCheckboxGrid(hardware.gsm.sorted(), selection.gsm, "") { band ->
                    val s = selection.gsm.toMutableSet()
                    if (!s.add(band)) s.remove(band)
                    onUpdate(selection.copy(gsm = s))
                }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(
    text: String,
    allSelected: Boolean,
    hapticFeedback: HapticFeedback,
    onToggleAll: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                onToggleAll()
            }
    ) {
        SmallTitle(text)
    }
}

@Composable
private fun BandSectionTitle(
    text: String,
    hapticFeedback: HapticFeedback,
    onToggleAll: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth().clickable {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            onToggleAll()
        }
    ) { SmallTitle(text) }
}

@Composable
private fun CopySelectionRow(
    label: String,
    hapticFeedback: HapticFeedback,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopy()
            }
            .padding(horizontal = 28.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = MiuixIcons.Copy,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.primary
        )
    }
}

/** Shown in place of a SIM 2 page when the modem refused SIM 2. */
@Composable
internal fun Sim2UnavailableNote() {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SIM 2 is not available",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "The modem refused to select SIM 2. This is expected on single-SIM " +
                    "phones and when dual SIM (DSDS) is turned off. SIM 1 works normally.",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun FilterActiveHint() {
    Text(
        text = "Filter active",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.primary,
    )
}

@Composable
private fun FilterCheckboxGrid(
    bands: List<Int>,
    selected: Set<Int>,
    prefix: String,
    onToggle: (Int) -> Unit
) {
    // Row spacing comes from the cells' own vertical padding, so the gap
    // between rows is part of the tap target rather than a dead zone.
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp - BandCellVerticalPadding)) {
        bands.chunked(4).forEach { group ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                group.forEach { band ->
                    BandCheckboxCell(
                        label = "$prefix$band",
                        checked = band in selected,
                        onToggle = { onToggle(band) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - group.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
