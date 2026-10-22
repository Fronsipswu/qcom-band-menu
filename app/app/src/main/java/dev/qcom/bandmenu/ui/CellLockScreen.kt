package dev.qcom.bandmenu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import dev.qcom.bandmenu.BandFilterState
import dev.qcom.bandmenu.CellLockState
import dev.qcom.bandmenu.HardwareBands
import dev.qcom.bandmenu.ModemState
import dev.qcom.bandmenu.PlmnLockState
import dev.qcom.bandmenu.SimBandFilter
import dev.qcom.bandmenu.network.LockRole
import dev.qcom.bandmenu.network.LockTarget
import dev.qcom.bandmenu.network.NetworkMonitor
import dev.qcom.bandmenu.network.NetworkSnapshot
import dev.qcom.bandmenu.network.Rat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

data class CellLockResult(
    val sim: Int,
    val fieldIndex: Int,
    val type: String,
    val success: Boolean,
    val message: String?
)

private val GreenColor = Color(0xFF4CAF50)
private val RedColor = Color(0xFFF44336)

private data class LabelOverride(val text: String, val color: Color)

@Composable
fun CellLockScreen(
    modemState: ModemState?,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    refreshKey: Int,
    onSimSwitch: (Int) -> Unit,
    onApplyLock: (sim: Int, fieldIndex: Int, input: String) -> Unit,
    onClearAll: (Int) -> Unit,
    onClear5G: (Int) -> Unit,
    onClear4G: (Int) -> Unit,
    onClearPlmn: (Int) -> Unit,
    onDetach: () -> Unit = {},
    onAttach: () -> Unit = {},
    onReattach: () -> Unit = {},
    simLabels: List<String> = listOf("SIM 1", "SIM 2"),
    lockResult: CellLockResult?,
    onLockResultConsumed: () -> Unit,
    snackbarHostState: SnackbarHostState,
    /** The Network page's poller: the Live view shows its rounds. */
    networkMonitor: NetworkMonitor,
    /** This page is the settled one, so Live polling follows it. */
    isActive: Boolean = false,
    /** Bands the app shows (the Bands page's filter, applied to the live lists). */
    bandFilter: BandFilterState = BandFilterState(),
    hardware: HardwareBands = HardwareBands(),
    onSaveBandFilter: (BandFilterState) -> Unit = {},
    onClearBandFilter: () -> Unit = {},
    backdrop: Backdrop? = null
) {
    val density = LocalDensity.current
    val navbarSpace = navbarSpace()
    // Match the overlay SmallTopAppBar's own inset source (systemBars top) so
    // the content offset equals the bar's real height in every window mode.
    // statusBars alone is equal in full screen but diverges in a floating /
    // freeform window, which made the bar overlap the SIM tab row.
    val topBarInset = WindowInsets.systemBars.only(WindowInsetsSides.Top)
        .asPaddingValues(density).calculateTopPadding()
    val topBarHeight = topBarInset + TopAppBarDefaults.CollapsedHeight

    val hapticFeedback = LocalHapticFeedback.current
    val hasNrHardware = modemState?.hardware?.nr?.isNotEmpty() == true

    // Content-only capture layer: the top bar (a sibling of this subtree) samples
    // and blurs it. The bar itself is never part of the captured layer, so the
    // blur cannot self-reference (the crash from the 0fefeb5 attempt).
    val surfaceColor = MiuixTheme.colorScheme.surface
    val screenBackdrop = rememberLayerBackdrop(onDraw = {
        drawRect(surfaceColor)
        drawContent()
    })

    val scope = rememberCoroutineScope()
    var experimentalEnabled by rememberSaveable { mutableStateOf(false) }
    // Live (the cells the modem reports, one tap to lock) or Manual (the text
    // fields). Live is the default: the neighbours are what people come here
    // to lock to.
    var liveMode by rememberSaveable { mutableStateOf(true) }
    var showBandFilter by rememberSaveable { mutableStateOf(false) }
    val live by networkMonitor.state.collectAsState()

    // The Live view is fed by the Network page's poller, which runs while at
    // least one of its viewers is on screen. The SIM tab decides which SIM's
    // cell-lock page it follows (binding to a SIM needs no modem lock).
    DisposableEffect(liveMode, isActive) {
        networkMonitor.setVisible(NetworkMonitor.OWNER_CELL_LOCK, liveMode && isActive)
        onDispose { networkMonitor.setVisible(NetworkMonitor.OWNER_CELL_LOCK, false) }
    }

    // In Live view the result is a snackbar: there are no field labels to
    // colour, and the round that lands next already shows the new cell.
    LaunchedEffect(lockResult, liveMode) {
        val r = lockResult ?: return@LaunchedEffect
        if (!liveMode) return@LaunchedEffect
        onLockResultConsumed()
        snackbarHostState.showSnackbar(
            if (r.success) "${r.type} locked \u2713" else (r.message ?: "Rejected by modem")
        )
    }

    // The pager is the single source of truth for the selected page. Mirroring
    // it into a separate state with a pair of LaunchedEffects let a stale
    // animateScrollToPage cancel the release fling (both run at
    // MutatePriority.Default), and when that effect was itself cancelled by its
    // key changing, the pager stayed stuck between pages.
    // One page when a SIM isn't usable: a swipe goes straight to the next
    // screen. Page p is slot p + simOffset (1 when only SIM 2 is usable).
    val simOffset = modemState?.simOffset ?: 0
    val pagerState = rememberPagerState(pageCount = { modemState?.simPageCount ?: 2 })
    val currentOffset by rememberUpdatedState(simOffset)
    val selectedSim by remember { derivedStateOf { pagerState.targetPage + currentOffset } }
    // The SIM the caller was last told about, so a swipe and a tab tap each
    // report a switch once. Keyed on the slot, so SIM 1 turning off (same
    // page, now SIM 2) also switches.
    var notifiedSim by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(selectedSim) {
        if (notifiedSim != selectedSim) {
            notifiedSim = selectedSim
            onSimSwitch(selectedSim)
        }
    }

    // The Live view follows this screen's SIM tab: its own SIM, not the one
    // the Network page last looked at. Skipped while the SIM isn't usable.
    LaunchedEffect(liveMode, isActive, selectedSim, modemState?.sim2Available) {
        if (liveMode && isActive && (selectedSim == 0 || modemState?.sim2Available != false))
            networkMonitor.selectSim(selectedSim + 1)
    }

    // SmallTopAppBar owns the top system-bar inset. Applying Scaffold's top
    // padding to this parent would count the status bar twice.
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading || modemState == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                PullToRefresh(
                    isRefreshing = isRefreshing || (liveMode && live.refreshing),
                    onRefresh = {
                        onRefresh()
                        // Live also takes a fresh reading now, instead of
                        // waiting for the poller's next round.
                        if (liveMode) networkMonitor.refreshOnce()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(screenBackdrop),
                    contentPadding = PaddingValues(top = topBarHeight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topBarHeight)
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TabRowWithContour(
                                tabs = simLabels,
                                selectedTabIndex = selectedSim,
                                onTabSelected = { index ->
                                    val target = index - simOffset
                                    if (target !in 0 until pagerState.pageCount) return@TabRowWithContour
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                    if (notifiedSim != index) {
                                        notifiedSim = index
                                        onSimSwitch(index)
                                    }
                                    if (pagerState.targetPage != target) {
                                        scope.launch { pagerState.animateScrollToPage(target) }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Live | Manual, beside the SIM tabs: Live shows the
                            // cells the modem reports, Manual the text fields.
                            // TabRowWithContour fills the width it's given, so the
                            // fixed size belongs to this Box: on the row itself it
                            // would claim the SIM tabs' room.
                            Box(modifier = Modifier.width(132.dp)) {
                                TabRowWithContour(
                                    tabs = listOf("Live", "Manual"),
                                    selectedTabIndex = if (liveMode) 0 else 1,
                                    onTabSelected = { index ->
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                        liveMode = index == 0
                                    },
                                    height = 38.dp,
                                    minWidth = 58.dp,
                                    maxWidth = 64.dp,
                                    cornerRadius = 8.dp
                                )
                            }
                        }

                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) { page ->
                            val slot = page + simOffset
                            val cellLock = if (slot == 0) modemState.sim1CellLock else modemState.sim2CellLock
                            if (slot == 1 && !modemState.sim2Available) {
                                Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) { Sim2UnavailableNote() }
                            } else if (liveMode) SimLiveCellPage(
                                simSlot = slot,
                                // This SIM's last reading: the poller follows the
                                // selected tab, so the other page keeps its own.
                                snapshot = live.lastBySim[slot + 1]?.snapshot
                                    ?: live.snapshot?.takeIf { it.sim == slot + 1 },
                                error = live.error,
                                paused = live.frozen,
                                bandFilter = bandFilter,
                                hardware = hardware,
                                onOpenBandFilter = { showBandFilter = true },
                                onToggleLive = { networkMonitor.setFrozen(!live.frozen) },
                                cellLockState = cellLock,
                                onLock = lock@{ target ->
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                    val field = target.lock ?: return@lock
                                    val (index, input) = field.args()
                                    onApplyLock(slot + 1, index, input)
                                },
                                onUnlock = unlock@{ target ->
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                    if (target.rat == Rat.LTE) onClear4G(slot + 1) else onClear5G(slot + 1)
                                },
                                hasNrHardware = hasNrHardware,
                                navbarSpace = navbarSpace
                            ) else SimCellLockPage(
                                simSlot = slot,
                                cellLockState = if (slot == 0) modemState.sim1CellLock else modemState.sim2CellLock,
                                plmnLockState = if (slot == 0) modemState.sim1PlmnLock else modemState.sim2PlmnLock,
                                refreshKey = refreshKey,
                                lockResult = lockResult,
                                onLockResultConsumed = onLockResultConsumed,
                                onApplyLock = onApplyLock,
                                experimentalEnabled = experimentalEnabled,
                                snackbarHostState = snackbarHostState,
                                navbarSpace = navbarSpace,
                                hasNrHardware = hasNrHardware,
                                onClearPlmn = onClearPlmn
                            )
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
                            title = "Cell-Lock",
                            color = Color.Transparent
                        )
                    }
                } else {
                    SmallTopAppBar(
                        title = "Cell-Lock"
                    )
                }

                // 3-dot menu at very top right, above the title bar
                val menuEntries = (if (hasNrHardware) {
                    listOf(
                        DropdownEntry(items = listOf(
                            DropdownItem(
                                text = "Clear ALL",
                                onClick = { onClearAll(selectedSim + 1) }
                            )
                        )),
                        DropdownEntry(items = listOf(
                            DropdownItem(
                                text = "Clear 5G",
                                onClick = {
                                    val nrType = if (selectedSim == 0)
                                        modemState.sim1CellLock.nr.type
                                    else
                                        modemState.sim2CellLock.nr.type
                                    if (nrType == "none") {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("No 5G cell lock active")
                                        }
                                    } else {
                                        onClear5G(selectedSim + 1)
                                    }
                                }
                            ),
                            DropdownItem(
                                text = "Clear 4G",
                                onClick = { onClear4G(selectedSim + 1) }
                            )
                        )),
                        DropdownEntry(items = listOf(
                            DropdownItem(
                                text = "Clear PLMN",
                                onClick = { onClearPlmn(selectedSim + 1) }
                            )
                        )),
                        DropdownEntry(items = listOf(
                            DropdownItem(
                                text = "Enable Experimental",
                                selected = experimentalEnabled,
                                onClick = { experimentalEnabled = !experimentalEnabled }
                            )
                        ))
                    )
                } else {
                    listOf(
                        DropdownEntry(items = listOf(
                            DropdownItem(
                                text = "Clear ALL",
                                onClick = { onClearAll(selectedSim + 1) }
                            )
                        )),
                        DropdownEntry(items = listOf(
                            DropdownItem(
                                text = "Clear 4G",
                                onClick = { onClear4G(selectedSim + 1) }
                            )
                        )),
                        DropdownEntry(items = listOf(
                            DropdownItem(
                                text = "Clear PLMN",
                                onClick = { onClearPlmn(selectedSim + 1) }
                            )
                        ))
                    )
                }) + listOf(
                    DropdownEntry(items = listOf(
                        DropdownItem("Detach", onClick = onDetach),
                        DropdownItem("Attach", onClick = onAttach),
                        DropdownItem("Re-Attach", onClick = onReattach)
                    ))
                )
                LiveBandFilterSheet(
                    show = showBandFilter,
                    slot = selectedSim,
                    hardware = hardware,
                    bandFilter = bandFilter,
                    onDismiss = { showBandFilter = false },
                    onClear = {
                        onClearBandFilter()
                        showBandFilter = false
                    },
                    onSave = {
                        onSaveBandFilter(it)
                        showBandFilter = false
                    }
                )

                Row(
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(top = topBarInset + 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WindowIconDropdownMenu(
                        entries = menuEntries,
                        collapseOnSelection = false
                    ) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = "Menu",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimCellLockPage(
    simSlot: Int,
    cellLockState: CellLockState?,
    plmnLockState: PlmnLockState?,
    refreshKey: Int,
    lockResult: CellLockResult?,
    onLockResultConsumed: () -> Unit,
    onApplyLock: (sim: Int, fieldIndex: Int, input: String) -> Unit,
    experimentalEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    navbarSpace: androidx.compose.ui.unit.Dp,
    hasNrHardware: Boolean,
    onClearPlmn: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current

    var nrArfcnText by rememberSaveable { mutableStateOf("") }
    var nrPciText by rememberSaveable { mutableStateOf("") }
    var nrMultiPciText by rememberSaveable { mutableStateOf("") }
    var nrGnbText by rememberSaveable { mutableStateOf("") }
    var ltePciText by rememberSaveable { mutableStateOf("") }
    var lteMultiPciText by rememberSaveable { mutableStateOf("") }
    var plmnText by rememberSaveable { mutableStateOf("") }
    var syncedStateKey by rememberSaveable { mutableStateOf("") }

    var labelOverrides by remember { mutableStateOf<Map<Int, LabelOverride>>(emptyMap()) }
    val overrideJobs = remember { mutableMapOf<Int, kotlinx.coroutines.Job>() }
    val pageScope = rememberCoroutineScope()

    LaunchedEffect(cellLockState, plmnLockState, refreshKey) {
        val currentKey = "$refreshKey|$cellLockState|$plmnLockState"
        if (currentKey == syncedStateKey) return@LaunchedEffect
        syncedStateKey = currentKey
        val nr = cellLockState?.nr
        val lte = cellLockState?.lte

        nrArfcnText = if (nr?.type == "arfcn" && nr.arfcnLock?.isNotEmpty() == true) {
            "${nr.arfcnLock[0].arfcn} ${nr.arfcnLock[0].scsKhz}"
        } else ""

        nrPciText = if (nr?.type == "pci" && nr.pciLock != null) {
            val band = nr.pciLock.bands.firstOrNull() ?: 0
            "${nr.pciLock.arfcn} ${nr.pciLock.pci} ${nr.pciLock.scsKhz} $band"
        } else ""

        nrMultiPciText = if (nr?.multiPciLock == true) {
            "(active - values not read back)"
        } else ""

        nrGnbText = if (nr?.type == "gnb_allowlist" && nr.gnbAllowlist != null) {
            val idBits = nr.gnbAllowlist.idBits ?: 28
            "$idBits ${nr.gnbAllowlist.gnbIds.joinToString(" ")}"
        } else ""

        ltePciText = if (lte?.valid == true && lte.locks.size == 1) {
            "${lte.locks[0].earfcn} ${lte.locks[0].pci}"
        } else ""

        lteMultiPciText = if (lte?.valid == true && lte.locks.size > 1) {
            "${lte.locks[0].earfcn} ${lte.locks.joinToString(" ") { it.pci.toString() }}"
        } else ""

        plmnText = if (plmnLockState?.lockedPlmn != null) {
            val lp = plmnLockState.lockedPlmn
            val mncStr = lp.mncDisplay.ifBlank { lp.mnc.toString() }
            "${lp.mcc} $mncStr"
        } else if (plmnLockState?.mcc != null && plmnLockState.mnc != null) {
            "${plmnLockState.mcc} ${plmnLockState.mnc}"
        } else ""
    }

    LaunchedEffect(lockResult) {
        if (lockResult != null && lockResult.sim == simSlot + 1) {
            val fieldIndex = lockResult.fieldIndex
            val override = if (lockResult.success) {
                LabelOverride("Success locking ${lockResult.type} \u2713", GreenColor)
            } else {
                val msg = lockResult.message ?: "Rejected by modem"
                LabelOverride("$msg \u2717", RedColor)
            }
            onLockResultConsumed()
            labelOverrides = labelOverrides + (fieldIndex to override)
            overrideJobs[fieldIndex]?.cancel()
            overrideJobs[fieldIndex] = pageScope.launch {
                delay(3000)
                labelOverrides = labelOverrides - fieldIndex
                overrideJobs.remove(fieldIndex)
            }
        }
    }

    val defaultLabels = remember {
        mapOf(
            0 to "NR-ARFCN SCS",
            1 to "NR-ARFCN PCI SCS Band",
            2 to "NR-ARFCN SCS Band PCI1 PCI2...",
            3 to "\"ID bits 22-32\" gNB1 gNB2...",
            4 to "EARFCN PCI",
            5 to "MCC MNC (e.g. 244 01)",
            6 to "EARFCN PCI1 PCI2..."
        )
    }

    val fieldTitles = remember {
        mapOf(
            0 to "NR-ARFCN lock: Put NR-ARFCN and SCS (in kHz)",
            1 to "PCI lock: Put NR-ARFCN PCI SCS and Band",
            2 to "MultiPCI lock: Put NR-ARFCN SCS Band and PCI list",
            3 to "gNB lock: Put ID bits (22-32) and gNB IDs",
            4 to "PCI lock: Put EARFCN and PCI",
            5 to "PLMN lock: Put MCC and MNC (e.g. 244 01)",
            6 to "Multi-cell lock: Put EARFCN and PCI list (0-503)"
        )
    }

    // Phone (TYPE_CLASS_PHONE): starts on a numeric/dial-pad layout but, unlike KeyboardType.Number's
    // digits-only keypad, lets users switch back to the alphanumeric layout on common IMEs (needed for spaces).
    val switchableNumericKeyboard = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done)
    val defaultLabelColor = MiuixTheme.colorScheme.onSecondaryContainer

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(bottom = navbarSpace)
    ) {
        if (hasNrHardware) {
            Spacer(modifier = Modifier.height(16.dp))

            SmallTitle("5G")

            // Field 0: NR-ARFCN
            Text(
                fieldTitles[0]!!,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(6.dp))
            val o0 = labelOverrides[0]
            TextField(
                value = nrArfcnText,
                onValueChange = { nrArfcnText = it },
                label = o0?.text ?: defaultLabels[0]!!,
                colors = TextFieldDefaults.textFieldColors(
                    labelColor = o0?.color ?: defaultLabelColor
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = switchableNumericKeyboard,
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onApplyLock(simSlot + 1, 0, nrArfcnText)
                })
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Field 1: PCI
            Text(
                fieldTitles[1]!!,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(6.dp))
            val o1 = labelOverrides[1]
            TextField(
                value = nrPciText,
                onValueChange = { nrPciText = it },
                label = o1?.text ?: defaultLabels[1]!!,
                colors = TextFieldDefaults.textFieldColors(
                    labelColor = o1?.color ?: defaultLabelColor
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = switchableNumericKeyboard,
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onApplyLock(simSlot + 1, 1, nrPciText)
                })
            )

            if (experimentalEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                // Field 2: MultiPCI
                Text(
                    fieldTitles[2]!!,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.height(6.dp))
                val o2 = labelOverrides[2]
                TextField(
                    value = nrMultiPciText,
                    onValueChange = { nrMultiPciText = it },
                    label = o2?.text ?: defaultLabels[2]!!,
                    colors = TextFieldDefaults.textFieldColors(
                        labelColor = o2?.color ?: defaultLabelColor
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = switchableNumericKeyboard,
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        onApplyLock(simSlot + 1, 2, nrMultiPciText)
                    })
                )
            }

            if (experimentalEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                // Field 3: gNB
                Text(
                    fieldTitles[3]!!,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.height(6.dp))
                val o3 = labelOverrides[3]
                TextField(
                    value = nrGnbText,
                    onValueChange = { nrGnbText = it },
                    label = o3?.text ?: defaultLabels[3]!!,
                    colors = TextFieldDefaults.textFieldColors(
                        labelColor = o3?.color ?: defaultLabelColor
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = switchableNumericKeyboard,
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        onApplyLock(simSlot + 1, 3, nrGnbText)
                    })
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        SmallTitle("4G")

        // Field 4: LTE PCI
        Text(
            fieldTitles[4]!!,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(modifier = Modifier.height(6.dp))
        val o4 = labelOverrides[4]
        TextField(
            value = ltePciText,
            onValueChange = { ltePciText = it },
            label = o4?.text ?: defaultLabels[4]!!,
            colors = TextFieldDefaults.textFieldColors(
                labelColor = o4?.color ?: defaultLabelColor
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = switchableNumericKeyboard,
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                onApplyLock(simSlot + 1, 4, ltePciText)
            })
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            fieldTitles[6]!!,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(modifier = Modifier.height(6.dp))
        val o6 = labelOverrides[6]
        TextField(
            value = lteMultiPciText,
            onValueChange = { lteMultiPciText = it },
            label = o6?.text ?: defaultLabels[6]!!,
            colors = TextFieldDefaults.textFieldColors(
                labelColor = o6?.color ?: defaultLabelColor
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = switchableNumericKeyboard,
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                onApplyLock(simSlot + 1, 6, lteMultiPciText)
            })
        )

        Spacer(modifier = Modifier.height(20.dp))

        SmallTitle("PLMN lock")

        Text(
            fieldTitles[5]!!,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(modifier = Modifier.height(6.dp))
        val o5 = labelOverrides[5]
        TextField(
            value = plmnText,
            onValueChange = { plmnText = it },
            label = o5?.text ?: defaultLabels[5]!!,
            colors = TextFieldDefaults.textFieldColors(
                labelColor = o5?.color ?: defaultLabelColor
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = switchableNumericKeyboard,
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                onApplyLock(simSlot + 1, 5, plmnText)
            })
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Live view ────────────────────────────────────────────────────────────

/** One line per lock that's active now, so Live view always shows the state. */
private fun activeLockLines(state: CellLockState?, hasNrHardware: Boolean): List<String> = buildList {
    if (hasNrHardware) state?.nr?.let { nr ->
        when (nr.type) {
            "pci" -> nr.pciLock?.let { p ->
                add("NR  ${p.arfcn} / PCI ${p.pci} · SCS ${p.scsKhz} kHz" +
                    if (p.bands.isEmpty()) "" else " · n${p.bands.sorted().joinToString("/n")}")
            }
            "arfcn" -> nr.arfcnLock?.firstOrNull()?.let { add("NR  ${it.arfcn} · SCS ${it.scsKhz} kHz") }
            "multi_pci" -> add("NR  multi-PCI lock")
            "gnb_allowlist" -> nr.gnbAllowlist?.let { add("NR  gNB allowlist (${it.gnbIds.size} IDs)") }
        }
    }
    state?.lte?.locks?.takeIf { it.isNotEmpty() }?.let { locks ->
        add(if (locks.size == 1) "LTE ${locks[0].earfcn} / PCI ${locks[0].pci}"
            else "LTE ${locks.size} cells on ${locks[0].earfcn}")
    }
}

/**
 * One SIM's Live view: the cell the modem is on, then its neighbours, each with
 * the lock that pins it. A tap locks; a locked row's tap clears that RAT's lock
 * (the same "Clear 4G"/"Clear 5G" the menu runs).
 */
@Composable
private fun SimLiveCellPage(
    simSlot: Int,
    snapshot: NetworkSnapshot?,
    error: String?,
    paused: Boolean,
    bandFilter: BandFilterState,
    hardware: HardwareBands,
    onOpenBandFilter: () -> Unit,
    onToggleLive: () -> Unit,
    cellLockState: CellLockState?,
    onLock: (LockTarget) -> Unit,
    onUnlock: (LockTarget) -> Unit,
    hasNrHardware: Boolean,
    navbarSpace: Dp
) {
    val grey = MiuixTheme.colorScheme.onSurfaceVariantSummary
    // The app-wide band filter (the Bands page's toggles): a hidden band's
    // neighbours stay out of the list.
    val filter = remember(bandFilter, hardware, simSlot) {
        if (bandFilter.enabled) SimBandFilter.effectiveFilter(bandFilter, hardware, simSlot) else null
    }
    val reported = snapshot?.lockTargets?.filter { it.role == LockRole.NEIGHBOUR }.orEmpty()
    val neighbours = if (filter == null) reported else reported.filter { filter.allows(it.band) }
    val hiddenByFilter = reported.size - neighbours.size
    val activeLocks = activeLockLines(cellLockState, hasNrHardware)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = navbarSpace)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // What the modem is on, and whether the readings are running.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    snapshot?.modeLabel ?: "Waiting\u2026",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold
                )
            }
            snapshot?.plmn?.let {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${it.mcc}-${it.mnc}",
                    style = MiuixTheme.textStyles.body2,
                    color = grey
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // The band toggles sit next to the list they filter.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (bandFilter.enabled) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else MiuixTheme.colorScheme.surfaceContainer
                    )
                    .clickable(onClick = onOpenBandFilter)
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    if (bandFilter.enabled) "Bands \u00b7 filtered" else "Bands",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = if (bandFilter.enabled) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            // Enable/disable the live reading: off stops the modem reads, so
            // the list holds still while a cell is picked (locks still apply).
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (paused) MiuixTheme.colorScheme.surfaceContainer
                        else LockGreen.copy(alpha = 0.18f)
                    )
                    .clickable { onToggleLive() }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (paused) grey.copy(alpha = 0.45f) else LockGreen)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        if (paused) "Disabled" else "Live",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = if (paused) grey else LockGreen
                    )
                }
            }
        }

        if (snapshot == null) {
            // No round yet for this SIM (first open, or a SIM the daemon can't
            // read): say so instead of showing an empty list.
            Spacer(modifier = Modifier.height(12.dp))
            NoticeText(error ?: "Waiting for the first reading from SIM ${simSlot + 1}\u2026")
        } else {
            if (activeLocks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(LockGreen)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                "Locked now",
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Medium,
                                color = LockGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        activeLocks.forEach {
                            Text(it, style = MiuixTheme.textStyles.body2)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallTitle("Neighbour cells (${neighbours.size})")
                Spacer(modifier = Modifier.weight(1f))
                if (onLock != null) Text(
                    "tap to lock",
                    style = MiuixTheme.textStyles.footnote1,
                    color = grey
                )
            }
            if (neighbours.isEmpty()) {
                NoticeText(
                    if (hiddenByFilter > 0) "No neighbour cells on the selected bands."
                    else "No neighbour cells reported."
                )
            } else Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                // The Network page's neighbour table, and its rows lock: tapping
                // a cell pins it, tapping a locked one releases it.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    NeighbourTable(
                        rows = neighbours,
                        lockedOf = { it.isLockedIn(cellLockState) },
                        onToggle = { t ->
                            if (t.isLockedIn(cellLockState)) onUnlock(t) else onLock(t)
                        },
                        roomy = true
                    )
                }
            }
            if (hiddenByFilter > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$hiddenByFilter hidden by the band filter",
                        style = MiuixTheme.textStyles.footnote1,
                        color = grey
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Change",
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenBandFilter)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                if (paused) "Live reading disabled: the modem isn't read until you enable it. Locks still apply."
                else "Tap a row to lock to that cell, or a locked row to release it. " +
                    "The modem is read every second.",
                style = MiuixTheme.textStyles.footnote1,
                color = grey
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun NoticeText(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}


/**
 * The band toggles for the live lists: the Bands page's filter, for this SIM.
 * Unchecked bands stay out of the neighbour lists on both pages; saving with
 * every band ticked, or "Clear filter", shows everything again.
 */
@Composable
private fun LiveBandFilterSheet(
    show: Boolean,
    slot: Int,
    hardware: HardwareBands,
    bandFilter: BandFilterState,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSave: (BandFilterState) -> Unit
) {
    val grey = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val saved = if (slot == 0) bandFilter.sim1 else bandFilter.sim2
    val haptic = LocalHapticFeedback.current
    var lte by remember { mutableStateOf(emptySet<Int>()) }
    var nr by remember { mutableStateOf(emptySet<Int>()) }
    var wcdma by remember { mutableStateOf(emptySet<Int>()) }
    var gsm by remember { mutableStateOf(emptySet<Int>()) }

    // Every band ticked, so the filter would hide nothing.
    val everything = (hardware.lte.isEmpty() || lte.containsAll(hardware.lte)) &&
        (hardware.nr.isEmpty() || nr.containsAll(hardware.nr)) &&
        (hardware.wcdma.isEmpty() || wcdma.containsAll(hardware.wcdma)) &&
        (hardware.gsm.isEmpty() || gsm.containsAll(hardware.gsm))

    LaunchedEffect(show, slot, hardware, bandFilter) {
        if (!show) return@LaunchedEffect
        // Opened the first time (nothing saved for this SIM): start from every
        // band the modem has, so ticking is "hide this one".
        val neverSaved = saved.lte.isEmpty() && saved.nrNsa.isEmpty() && saved.nrSa.isEmpty() &&
            saved.wcdma.isEmpty() && saved.gsm.isEmpty()
        lte = if (neverSaved) hardware.lte else saved.lte
        nr = if (neverSaved) hardware.nr else saved.nrNsa + saved.nrSa
        wcdma = if (neverSaved) hardware.wcdma else saved.wcdma
        gsm = if (neverSaved) hardware.gsm else saved.gsm
    }

    WindowBottomSheet(
        title = "Bands shown",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Live lists show only the selected bands. This is the same filter as the " +
                    "Bands page's, and it applies to every page.",
                style = MiuixTheme.textStyles.footnote1,
                color = grey
            )
            if (hardware.nr.isNotEmpty()) {
                SmallTitle("NR")
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FilterCheckboxGrid(hardware.nr.sorted(), nr, "n") { band ->
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        nr = if (band in nr) nr - band else nr + band
                    }
                }
            }
            if (hardware.lte.isNotEmpty()) {
                SmallTitle("LTE")
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FilterCheckboxGrid(hardware.lte.sorted(), lte, "B") { band ->
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        lte = if (band in lte) lte - band else lte + band
                    }
                }
            }
            if (hardware.wcdma.isNotEmpty()) {
                SmallTitle("WCDMA")
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FilterCheckboxGrid(hardware.wcdma.sorted(), wcdma, "B") { band ->
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        wcdma = if (band in wcdma) wcdma - band else wcdma + band
                    }
                }
            }
            if (hardware.gsm.isNotEmpty()) {
                SmallTitle("GSM")
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FilterCheckboxGrid(hardware.gsm.sorted(), gsm, "") { band ->
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        gsm = if (band in gsm) gsm - band else gsm + band
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "Clear filter",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onClear()
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = if (everything) "Show all" else "Save",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        val selection = SimBandFilter(gsm = gsm, wcdma = wcdma, lte = lte, nrNsa = nr, nrSa = nr)
                        // Everything ticked hides nothing: clear instead, so a
                        // band the modem starts reporting later still shows.
                        if (everything) onClear()
                        else onSave(
                            if (slot == 0) BandFilterState(enabled = true, sim1 = selection, sim2 = bandFilter.sim2)
                            else BandFilterState(enabled = true, sim1 = bandFilter.sim1, sim2 = selection)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
