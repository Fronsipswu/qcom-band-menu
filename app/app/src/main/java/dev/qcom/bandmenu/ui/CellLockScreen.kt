package dev.qcom.bandmenu.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import dev.qcom.bandmenu.CellLockState
import dev.qcom.bandmenu.ModemState
import dev.qcom.bandmenu.PlmnLockState
import dev.qcom.bandmenu.network.LockRole
import dev.qcom.bandmenu.network.LockTarget
import dev.qcom.bandmenu.network.servingLockTargets
import dev.qcom.bandmenu.network.SeenNrArfcn
import dev.qcom.bandmenu.network.SeenEarfcn
import dev.qcom.bandmenu.network.nrBandLabelForArfcn
import dev.qcom.bandmenu.network.nrBandsForArfcn
import dev.qcom.bandmenu.network.nrDefaultScsKhz
import dev.qcom.bandmenu.network.nrLockBand
import dev.qcom.bandmenu.network.nrLockScs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import androidx.compose.ui.draw.rotate
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Unlock
import dev.qcom.bandmenu.network.NetworkMonitor
import dev.qcom.bandmenu.network.NetworkSnapshot
import dev.qcom.bandmenu.network.Rat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    /** The Network page's poller: the cell table shows its rounds. */
    networkMonitor: NetworkMonitor,
    /** This page is the settled one, so the table's polling follows it. */
    isActive: Boolean = false,
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
    val live by networkMonitor.state.collectAsState()
    // The cell table at the bottom of each SIM page shows the Network page's
    // poller rounds. This page's padlock holds it still: polling for this
    // page stops and the table keeps the reading it had (the Network page's
    // own padlock is separate).
    var frozen by rememberSaveable { mutableStateOf(false) }
    var frozenSnaps by remember { mutableStateOf<Map<Int, NetworkSnapshot>>(emptyMap()) }
    fun liveSnapshot(slot: Int): NetworkSnapshot? =
        live.lastBySim[slot + 1]?.snapshot ?: live.snapshot?.takeIf { it.sim == slot + 1 }

    // The poller runs while at least one of its viewers is on screen.
    DisposableEffect(isActive, frozen) {
        networkMonitor.setVisible(NetworkMonitor.OWNER_CELL_LOCK, isActive && !frozen)
        onDispose { networkMonitor.setVisible(NetworkMonitor.OWNER_CELL_LOCK, false) }
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

    // The table follows this screen's SIM tab (the Network page takes its own
    // SIM back when it's shown again). Skipped while the SIM isn't usable.
    LaunchedEffect(isActive, frozen, selectedSim, modemState?.sim2Available) {
        if (isActive && !frozen && (selectedSim == 0 || modemState?.sim2Available != false))
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
                    isRefreshing = isRefreshing || (!frozen && live.refreshing),
                    onRefresh = {
                        onRefresh()
                        // The table takes a fresh reading too, unless held.
                        if (!frozen) networkMonitor.refreshOnce()
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
                            } else SimCellLockPage(
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
                                onClearPlmn = onClearPlmn,
                                seenNrArfcns = live.nrArfcnsBySim[slot + 1].orEmpty(),
                                nrHardwareBands = modemState.hardware.nr,
                                seenEarfcns = live.earfcnsBySim[slot + 1].orEmpty(),
                                cellTable = {
                                    CellLockTable(
                                        simSlot = slot,
                                        snapshot = if (frozen) frozenSnaps[slot] else liveSnapshot(slot),
                                        error = live.error,
                                        frozen = frozen,
                                        onToggleFrozen = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                            if (!frozen) frozenSnaps = (0..1).mapNotNull { s -> liveSnapshot(s)?.let { s to it } }.toMap()
                                            frozen = !frozen
                                        },
                                        cellLockState = cellLock,
                                        onLock = lock@{ target ->
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                            val field = target.lock ?: return@lock
                                            val (index, input) = field.args()
                                            onApplyLock(slot + 1, index, input)
                                        },
                                        onUnlock = { target ->
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                            if (target.rat == Rat.LTE) onClear4G(slot + 1) else onClear5G(slot + 1)
                                        }
                                    )
                                }
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
    onClearPlmn: (Int) -> Unit,
    /** The NR-ARFCNs this SIM's readings reported (the NR-ARFCN field's list). */
    seenNrArfcns: List<SeenNrArfcn> = emptyList(),
    /** The EARFCNs this SIM's readings reported (the EARFCN field's list). */
    seenEarfcns: List<SeenEarfcn> = emptyList(),
    /** NR bands the modem supports (narrow an NR-ARFCN's band candidates). */
    nrHardwareBands: Set<Int> = emptySet(),
    /** The cell table, below the fields. */
    cellTable: @Composable () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current

    // NR lock: NR-ARFCN and SCS (the NR-ARFCN lock), plus PCI and band (the
    // full cell lock, sent once a PCI is entered). Each value is typed or
    // picked; the SCS and the band fill themselves from the NR-ARFCN, the
    // band only with a PCI (nrBandAuto: the page filled it in, so it follows
    // the NR-ARFCN and goes with the PCI). nrSent is the lock last sent: the
    // check button stays grey until a value or the lock state changes.
    var nrArfcnValue by rememberSaveable { mutableStateOf("") }
    var nrScs by rememberSaveable { mutableIntStateOf(30) }
    var nrPciValue by rememberSaveable { mutableStateOf("") }
    var nrBandValue by rememberSaveable { mutableStateOf("") }
    var nrBandAuto by rememberSaveable { mutableStateOf(false) }
    var nrSent by rememberSaveable { mutableStateOf<String?>(null) }
    var nrMultiPciText by rememberSaveable { mutableStateOf("") }
    var nrGnbText by rememberSaveable { mutableStateOf("") }
    // LTE lock: the EARFCN and its PCIs (typed or picked; one PCI sends the
    // cell lock, more the multi-cell lock), and the lock last sent.
    var lteEarfcnValue by rememberSaveable { mutableStateOf("") }
    var ltePciValue by rememberSaveable { mutableStateOf("") }
    var lteSent by rememberSaveable { mutableStateOf<String?>(null) }
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

        // An NR lock fills the fields; without one they keep what was
        // entered, so a cleared lock can be applied again.
        if (nr?.type == "arfcn" && nr.arfcnLock?.isNotEmpty() == true) {
            nrArfcnValue = nr.arfcnLock[0].arfcn.toString()
            nrScs = nr.arfcnLock[0].scsKhz
            nrPciValue = ""
            nrBandValue = ""
            nrBandAuto = false
        } else if (nr?.type == "pci" && nr.pciLock != null) {
            nrArfcnValue = nr.pciLock.arfcn.toString()
            nrScs = nr.pciLock.scsKhz
            nrPciValue = nr.pciLock.pci.toString()
            nrBandValue = nr.pciLock.bands.firstOrNull()?.toString().orEmpty()
            nrBandAuto = false
        }
        nrSent = null

        nrMultiPciText = if (nr?.multiPciLock == true) {
            "(active - values not read back)"
        } else ""

        nrGnbText = if (nr?.type == "gnb_allowlist" && nr.gnbAllowlist != null) {
            val idBits = nr.gnbAllowlist.idBits ?: 28
            "$idBits ${nr.gnbAllowlist.gnbIds.joinToString(" ")}"
        } else ""

        // An LTE lock fills the fields; without one they keep what was entered.
        if (lte?.valid == true && lte.locks.isNotEmpty()) {
            lteEarfcnValue = lte.locks[0].earfcn.toString()
            ltePciValue = lte.locks.joinToString(" ") { it.pci.toString() }
        }
        lteSent = null

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
            // Rejected: the check button is live again for another try.
            if ((fieldIndex == 0 || fieldIndex == 1) && !lockResult.success) nrSent = null
            if ((fieldIndex == 4 || fieldIndex == 6) && !lockResult.success) lteSent = null
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
            0 to "NR-ARFCN",
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
            0 to "NR-ARFCN lock",
            1 to "PCI + Band lock (OPTIONAL)",
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

            SmallTitle("5G", insideMargin = FieldTitleMargin)

            // Fields 0 and 1: the NR-ARFCN lock, or with a PCI the full cell lock.
            val o0 = labelOverrides[0]
            val o1 = labelOverrides[1]
            val arfcn = nrArfcnValue.trim().toLongOrNull()
            val withPci = nrPciValue.isNotBlank()
            val pci = nrPciValue.trim().toIntOrNull()?.takeIf { it in 0..1007 }
            val band = nrBandValue.trim().removePrefix("n").toIntOrNull()?.takeIf { it > 0 }
            val nrInput = if (withPci) "$arfcn $pci $nrScs $band" else "$arfcn $nrScs"
            val nrLocked = cellLockState?.nr?.let { nr ->
                when (nr.type) {
                    "arfcn" -> nr.arfcnLock?.firstOrNull()?.let { "${it.arfcn} ${it.scsKhz}" }
                    "pci" -> nr.pciLock?.let { "${it.arfcn} ${it.pci} ${it.scsKhz} ${it.bands.firstOrNull()}" }
                    else -> null
                }
            }
            val nrCanApply = arfcn != null && (!withPci || (pci != null && band != null)) &&
                nrInput != nrSent && nrInput != nrLocked
            // Each row's check button sends its own lock: the top one only
            // without a PCI, the bottom one only with.
            fun autoBand(a: Long?) = a?.let { nrLockBand(it, seenNrArfcns, nrHardwareBands) }?.toString().orEmpty()
            fun applyNr() {
                if (!nrCanApply) return
                keyboard?.hide()
                nrSent = nrInput
                onApplyLock(simSlot + 1, if (withPci) 1 else 0, nrInput)
            }
            NrLockFields(
                arfcn = nrArfcnValue,
                onArfcnChange = { v ->
                    nrArfcnValue = v
                    val a = v.trim().toLongOrNull()
                    a?.let { nrLockScs(it, seenNrArfcns, nrHardwareBands) }?.let { nrScs = it }
                    if (nrPciValue.isNotBlank() && (nrBandAuto || nrBandValue.isBlank())) {
                        nrBandValue = autoBand(a)
                        nrBandAuto = true
                    }
                },
                scsKhz = nrScs,
                onScsChange = { nrScs = it },
                pci = nrPciValue,
                onPciChange = { v ->
                    nrPciValue = v
                    if (v.isBlank()) {
                        if (nrBandAuto) { nrBandValue = ""; nrBandAuto = false }
                    } else if (nrBandValue.isBlank() || nrBandAuto) {
                        nrBandValue = autoBand(arfcn)
                        nrBandAuto = true
                    }
                },
                band = nrBandValue,
                onBandChange = { v ->
                    nrBandValue = v
                    nrBandAuto = false
                    // A band picked by hand for an ambiguous NR-ARFCN sets its SCS
                    // (unless DIAG saw the cell's own).
                    val b = v.trim().removePrefix("n").toIntOrNull()
                    if (b != null && seenNrArfcns.none { it.arfcn == arfcn && it.scsKhz != null }) nrScs = nrDefaultScsKhz(b)
                },
                seen = seenNrArfcns,
                hardwareBands = nrHardwareBands,
                arfcnLabel = o0?.text ?: defaultLabels[0]!!,
                arfcnLabelColor = o0?.color ?: defaultLabelColor,
                pciTitle = fieldTitles[1]!!,
                pciLabel = o1?.text ?: "PCI",
                pciLabelColor = o1?.color ?: defaultLabelColor,
                keyboardOptions = switchableNumericKeyboard,
                canApplyArfcn = nrCanApply && !withPci,
                canApplyCell = nrCanApply && withPci,
                onApply = { applyNr() }
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

        // The section title over the EARFCN box, a note over the PCI box (the
        // columns as LteLockFields' row), on one line.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            SmallTitle("4G", modifier = Modifier.weight(1f), insideMargin = FieldTitleMargin)
            Spacer(Modifier.width(8.dp))
            // From the PCI box's text on, over its check button too (the box alone is narrow).
            Text(
                "(Multi-PCI allowed)", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1,
                modifier = Modifier.width(NR_SIDE_WIDTH + 60.dp).padding(start = 14.dp, bottom = 8.dp)
            )
        }

        // Fields 4 and 6: one PCI is the cell lock, more the multi-cell lock.
        val o4 = labelOverrides[4] ?: labelOverrides[6]
        val earfcn = lteEarfcnValue.trim().toLongOrNull()
        val pciTokens = ltePciValue.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ltePcis = pciTokens.mapNotNull { it.toIntOrNull()?.takeIf { p -> p in 0..503 } }
        val lteInput = "$earfcn ${ltePcis.joinToString(" ")}"
        // Compared as a set: the same PCIs in another order are the same lock.
        fun lteKey(e: Long?, pcis: List<Int>) = "$e ${pcis.sorted().joinToString(" ")}"
        val lteLocked = cellLockState?.lte?.takeIf { it.valid && it.locks.isNotEmpty() }
            ?.let { l -> lteKey(l.locks[0].earfcn.toLong(), l.locks.map { it.pci }) }
        val lteCanApply = earfcn != null && ltePcis.isNotEmpty() && ltePcis.size == pciTokens.size &&
            lteKey(earfcn, ltePcis) != lteSent && lteKey(earfcn, ltePcis) != lteLocked
        fun applyLte() {
            if (!lteCanApply) return
            keyboard?.hide()
            lteSent = lteKey(earfcn, ltePcis)
            onApplyLock(simSlot + 1, if (ltePcis.size == 1) 4 else 6, lteInput)
        }
        LteLockFields(
            earfcn = lteEarfcnValue,
            onEarfcnChange = { lteEarfcnValue = it },
            pcis = ltePciValue,
            onPcisChange = { ltePciValue = it },
            seen = seenEarfcns,
            pciLabel = o4?.text ?: "PCI",
            pciLabelColor = o4?.color ?: defaultLabelColor,
            keyboardOptions = switchableNumericKeyboard,
            canApply = lteCanApply,
            onApply = { applyLte() }
        )

        Spacer(modifier = Modifier.height(20.dp))

        SmallTitle("PLMN lock", insideMargin = FieldTitleMargin)

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

        cellTable()

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── NR-ARFCN lock row ────────────────────────────────────────────────────

private val NR_SCS_KHZ = listOf(15, 30, 60, 120, 240)

/** A down chevron (miuix's forward chevron turned a quarter; its ExpandMore
 *  is a full-screen glyph, not an arrow). */
@Composable
private fun DropdownChevron(description: String, modifier: Modifier = Modifier) = Icon(
    MiuixIcons.ChevronForward,
    contentDescription = description,
    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    modifier = modifier.size(18.dp).rotate(90f)
)

/** Section titles flush with the fields' own titles (the default margin
 *  indents them). */
private val FieldTitleMargin = PaddingValues(horizontal = 0.dp, vertical = 8.dp)

/** The SCS box and the band field: narrow, the same width so they line up. */
private val NR_SIDE_WIDTH = 104.dp

/**
 * The NR lock fields: NR-ARFCN | SCS | check, then (titled [pciTitle]) PCI |
 * Band | check. NR-ARFCN, PCI and band are typed or picked (their arrows: the
 * NR-ARFCNs seen this session, the PCIs seen on the NR-ARFCN, the bands it can
 * be in); the SCS is picked only. The top check button sends the NR-ARFCN
 * lock, the bottom one the cell lock; each is grey while it has nothing new to
 * send ([canApplyArfcn], [canApplyCell]).
 */
@Composable
private fun NrLockFields(
    arfcn: String,
    onArfcnChange: (String) -> Unit,
    scsKhz: Int,
    onScsChange: (Int) -> Unit,
    pci: String,
    onPciChange: (String) -> Unit,
    band: String,
    onBandChange: (String) -> Unit,
    seen: List<SeenNrArfcn>,
    hardwareBands: Set<Int>,
    arfcnLabel: String,
    arfcnLabelColor: Color,
    pciTitle: String,
    pciLabel: String,
    pciLabelColor: Color,
    keyboardOptions: KeyboardOptions,
    canApplyArfcn: Boolean,
    canApplyCell: Boolean,
    onApply: () -> Unit
) {
    val arfcnN = arfcn.trim().toLongOrNull()
    val defaultLabelColor = MiuixTheme.colorScheme.onSecondaryContainer
    val arfcnItems = seen.sortedBy { it.arfcn }.map { s ->
        DropdownOption(s.arfcn.toString(), s.arfcn.toString(), tag = s.band?.let { "n$it" } ?: nrBandLabelForArfcn(s.arfcn, hardwareBands))
    }
    val pciItems = seen.firstOrNull { it.arfcn == arfcnN }?.pcis.orEmpty().sorted().map { DropdownOption(it.toString(), it.toString()) }
    val bandItems = (arfcnN?.let { nrBandsForArfcn(it, hardwareBands) } ?: hardwareBands.sorted())
        .map { DropdownOption(it.toString(), "", tag = "n$it") }
    Column {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            DropdownField(
                value = arfcn, onValueChange = onArfcnChange, label = arfcnLabel, labelColor = arfcnLabelColor,
                items = arfcnItems, emptyText = "None seen yet", description = "Seen NR-ARFCNs",
                keyboardOptions = keyboardOptions, onDone = onApply, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            ScsBox(scsKhz, onScsChange, Modifier.width(NR_SIDE_WIDTH).fillMaxHeight())
            Spacer(Modifier.width(8.dp))
            ApplyButton(canApplyArfcn, onApply, "Apply NR-ARFCN lock")
        }
        Spacer(Modifier.height(12.dp))
        Text(pciTitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            DropdownField(
                value = pci, onValueChange = onPciChange, label = pciLabel, labelColor = pciLabelColor,
                items = pciItems,
                emptyText = if (arfcnN == null) "Pick an NR-ARFCN first" else "None seen on this NR-ARFCN",
                description = "Seen PCIs", keyboardOptions = keyboardOptions, onDone = onApply,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            DropdownField(
                value = band, onValueChange = onBandChange, label = "Band", labelColor = defaultLabelColor,
                items = bandItems, emptyText = "No NR band", description = "Bands",
                keyboardOptions = keyboardOptions, onDone = onApply, popupAlignment = PopupPositionProvider.Align.End,
                modifier = Modifier.width(NR_SIDE_WIDTH)
            )
            Spacer(Modifier.width(8.dp))
            ApplyButton(canApplyCell, onApply, "Apply NR cell lock")
        }
    }
}

/**
 * The LTE lock fields: EARFCN | PCI | check, in the NR rows' columns. Both
 * are typed or picked (the EARFCNs seen this session, the PCIs seen on the
 * EARFCN); the PCI list takes several (space-separated, or ticked in the
 * list): one is the cell lock, more the multi-cell lock.
 */
@Composable
private fun LteLockFields(
    earfcn: String,
    onEarfcnChange: (String) -> Unit,
    pcis: String,
    onPcisChange: (String) -> Unit,
    seen: List<SeenEarfcn>,
    pciLabel: String,
    pciLabelColor: Color,
    keyboardOptions: KeyboardOptions,
    canApply: Boolean,
    onApply: () -> Unit
) {
    val earfcnN = earfcn.trim().toLongOrNull()
    val labelColor = MiuixTheme.colorScheme.onSecondaryContainer
    val earfcnItems = seen.sortedBy { it.earfcn }.map { s ->
        DropdownOption(s.earfcn.toString(), s.earfcn.toString(), tag = s.band?.let { "B$it" })
    }
    val pciItems = seen.firstOrNull { it.earfcn == earfcnN }?.pcis.orEmpty().sorted().map { DropdownOption(it.toString(), it.toString()) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        DropdownField(
            value = earfcn, onValueChange = onEarfcnChange, label = "EARFCN", labelColor = labelColor,
            items = earfcnItems, emptyText = "None seen yet", description = "Seen EARFCNs",
            keyboardOptions = keyboardOptions, onDone = onApply, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        DropdownField(
            value = pcis, onValueChange = onPcisChange, label = pciLabel, labelColor = pciLabelColor,
            items = pciItems,
            emptyText = if (earfcnN == null) "Pick an EARFCN first" else "None seen on this EARFCN",
            description = "Seen PCIs", keyboardOptions = keyboardOptions, onDone = onApply,
            multi = true, popupAlignment = PopupPositionProvider.Align.End, modifier = Modifier.width(NR_SIDE_WIDTH)
        )
        Spacer(Modifier.width(8.dp))
        ApplyButton(canApply, onApply, "Apply LTE lock")
    }
}

/** A lock row's check button: blue when it has something to send, grey (the
 *  fields' own background) when not. */
@Composable
private fun ApplyButton(enabled: Boolean, onClick: () -> Unit, description: String) {
    Box(
        Modifier.width(52.dp).fillMaxHeight().clip(RoundedCornerShape(TextFieldDefaults.CornerRadius))
            .background(if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            MiuixIcons.Ok,
            contentDescription = description,
            tint = if (enabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** A dropdown entry: the [value] it puts in the field, its [text], and an
 *  optional band [tag] before it in the band's colour (as the Network page
 *  shows bands). */
private data class DropdownOption(val value: String, val text: String, val tag: String? = null)

/** A text field that can also be filled from a list (its arrow): [items],
 *  after "None", which clears the field. With [multi]
 *  the field holds space-separated values and the list ticks them on and off
 *  (it stays open). A notch smaller than the page's other fields. */
@Composable
private fun DropdownField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    labelColor: Color,
    items: List<DropdownOption>,
    emptyText: String,
    description: String,
    keyboardOptions: KeyboardOptions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    multi: Boolean = false,
    popupAlignment: PopupPositionProvider.Align = PopupPositionProvider.Align.Start
) {
    var show by remember { mutableStateOf(false) }
    val tokens = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    Box(modifier) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            colors = TextFieldDefaults.textFieldColors(labelColor = labelColor),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            insideMargin = DpSize(14.dp, 12.dp),
            textStyle = MiuixTheme.textStyles.body1,
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            trailingIcon = {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { show = true }
                        .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                ) { DropdownChevron(description) }
            }
        )
        WindowListPopup(show = show, alignment = popupAlignment, onDismissRequest = { show = false }) {
            val dismiss = LocalDismissState.current
            val options = listOf(DropdownOption("", "None")) + items
            val size = options.size + if (items.isEmpty()) 1 else 0
            ListPopupColumn {
                options.forEachIndexed { i, (v, text, tag) ->
                    DropdownImpl(
                        item = DropdownItem(text = text, icon = tag?.let { t -> { m -> BandTag(t, m) } }),
                        optionSize = size,
                        isSelected = if (v.isEmpty()) tokens.isEmpty() else v in tokens && (multi || tokens.size == 1),
                        index = i,
                        onSelectedIndexChange = {
                            if (multi && v.isNotEmpty()) {
                                onValueChange((if (v in tokens) tokens - v else tokens + v).joinToString(" "))
                            } else {
                                onValueChange(v)
                                dismiss?.invoke()
                            }
                        }
                    )
                }
                if (items.isEmpty()) {
                    DropdownImpl(text = emptyText, optionSize = size, isSelected = false, index = options.size,
                        enabled = false, onSelectedIndexChange = {})
                }
            }
        }
    }
}

/** A dropdown entry's band label, coloured as on the Network page. */
@Composable
private fun BandTag(band: String, modifier: Modifier) = Box(modifier, contentAlignment = Alignment.CenterStart) {
    Text(
        band,
        fontSize = MiuixTheme.textStyles.body1.fontSize,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        color = bandColor(band) ?: MiuixTheme.colorScheme.onBackground
    )
}

/** The SCS: picked from [NR_SCS_KHZ] only, with a small "SCS" label like a
 *  filled text field's. */
@Composable
private fun ScsBox(scsKhz: Int, onScsChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(TextFieldDefaults.CornerRadius))
                .background(MiuixTheme.colorScheme.secondaryContainer)
                .clickable { show = true }
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("SCS", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSecondaryContainer)
                Text("$scsKhz", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onBackground)
            }
            DropdownChevron("SCS")
        }
        WindowListPopup(show = show, alignment = PopupPositionProvider.Align.End, onDismissRequest = { show = false }) {
            val dismiss = LocalDismissState.current
            ListPopupColumn {
                NR_SCS_KHZ.forEachIndexed { i, scs ->
                    DropdownImpl(
                        text = "$scs kHz",
                        optionSize = NR_SCS_KHZ.size,
                        isSelected = scs == scsKhz,
                        index = i,
                        onSelectedIndexChange = {
                            onScsChange(scs)
                            dismiss?.invoke()
                        }
                    )
                }
            }
        }
    }
}

// ── Cell table ───────────────────────────────────────────────────────────

/**
 * The cells the modem reports, one tap to lock: the serving cells first (the
 * primary, then its SCells, PCI and channel in blue), then the neighbours.
 * A locked row's tap clears that RAT's lock (the menu's "Clear 4G"/"Clear 5G").
 */
@Composable
private fun CellLockTable(
    simSlot: Int,
    snapshot: NetworkSnapshot?,
    error: String?,
    /** The padlock: the table holds its reading (this page only). */
    frozen: Boolean,
    onToggleFrozen: () -> Unit,
    cellLockState: CellLockState?,
    onLock: (LockTarget) -> Unit,
    onUnlock: (LockTarget) -> Unit
) {
    Spacer(modifier = Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallTitle("Tap to lock cell", modifier = Modifier.weight(1f), insideMargin = SectionTitleMargin)
        IconButton(onClick = onToggleFrozen) {
            Icon(
                imageVector = if (frozen) MiuixIcons.Lock else MiuixIcons.Unlock,
                contentDescription = if (frozen) "Resume the cell table" else "Freeze the cell table",
                tint = if (frozen) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground
            )
        }
    }
    if (snapshot == null) {
        // No round yet for this SIM (first open, held before one came, or a
        // SIM the daemon can't read).
        NoticeText(error ?: "Waiting for the first reading from SIM ${simSlot + 1}\u2026")
        return
    }
    // NR neighbours come one row per beam, strongest first: here one row per
    // cell, its strongest beam's values, without the Beam column. A serving
    // cell's other beams are neighbour rows too; its serving row stands for it.
    val serving = servingLockTargets(snapshot)
    val neighbours = snapshot.lockTargets.filter { it.role == LockRole.NEIGHBOUR }
        .distinctBy { Triple(it.rat, it.channel, if (it.beam != null) it.id else it.key) }
        .filter { n -> n.beam == null || serving.none { it.rat == n.rat && it.channel == n.channel && it.id == n.id } }
        .map { if (it.beam != null) it.copy(beam = null) else it }
    val rows = serving + neighbours
    if (rows.isEmpty()) {
        NoticeText("No cells reported.")
        return
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            NeighbourTable(
                rows = rows,
                lockedOf = { it.isLockedIn(cellLockState) },
                onToggle = { t -> if (t.isLockedIn(cellLockState)) onUnlock(t) else onLock(t) },
                roomy = true
            )
        }
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
