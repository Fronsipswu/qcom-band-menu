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
    /** The cell table, below the fields. */
    cellTable: @Composable () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current

    // NR-ARFCN lock: the NR-ARFCN (typed or picked), the SCS, and the input
    // last sent (its check button stays grey until a value changes or the
    // lock state does).
    var nrArfcnValue by rememberSaveable { mutableStateOf("") }
    var nrScs by rememberSaveable { mutableIntStateOf(30) }
    var nrArfcnSent by rememberSaveable { mutableStateOf<String?>(null) }
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

        // An NR-ARFCN lock fills the fields; without one they keep what was
        // entered, so a cleared lock can be applied again.
        if (nr?.type == "arfcn" && nr.arfcnLock?.isNotEmpty() == true) {
            nrArfcnValue = nr.arfcnLock[0].arfcn.toString()
            nrScs = nr.arfcnLock[0].scsKhz
        }
        nrArfcnSent = null

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
            // Rejected: the check button is live again for another try.
            if (fieldIndex == 0 && !lockResult.success) nrArfcnSent = null
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
            val nrArfcnInput = "${nrArfcnValue.trim()} $nrScs"
            val nrArfcnLocked = cellLockState?.nr?.takeIf { it.type == "arfcn" }?.arfcnLock?.firstOrNull()
                ?.let { "${it.arfcn} ${it.scsKhz}" }
            val nrArfcnCanApply = nrArfcnValue.trim().toLongOrNull() != null &&
                nrArfcnInput != nrArfcnSent && nrArfcnInput != nrArfcnLocked
            fun applyNrArfcn() {
                if (!nrArfcnCanApply) return
                keyboard?.hide()
                nrArfcnSent = nrArfcnInput
                onApplyLock(simSlot + 1, 0, nrArfcnInput)
            }
            NrArfcnLockRow(
                arfcn = nrArfcnValue,
                onArfcnChange = { nrArfcnValue = it },
                scsKhz = nrScs,
                onScsChange = { nrScs = it },
                seen = seenNrArfcns,
                label = o0?.text ?: defaultLabels[0]!!,
                labelColor = o0?.color ?: defaultLabelColor,
                keyboardOptions = switchableNumericKeyboard,
                canApply = nrArfcnCanApply,
                onApply = { applyNrArfcn() }
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

/**
 * NR-ARFCN | SCS | apply. The NR-ARFCN is typed, or picked from the ones the
 * modem reported this session (its arrow; picking one also takes its SCS when
 * known); the SCS is picked only. The check button sends the lock and is grey
 * while there is nothing new to send ([canApply]).
 */
@Composable
private fun NrArfcnLockRow(
    arfcn: String,
    onArfcnChange: (String) -> Unit,
    scsKhz: Int,
    onScsChange: (Int) -> Unit,
    seen: List<SeenNrArfcn>,
    label: String,
    labelColor: Color,
    keyboardOptions: KeyboardOptions,
    canApply: Boolean,
    onApply: () -> Unit
) {
    var showArfcns by remember { mutableStateOf(false) }
    var showScs by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TextFieldDefaults.CornerRadius)
    // A notch smaller than the other fields: three boxes share the row.
    val textStyle = MiuixTheme.textStyles.body1
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextField(
                value = arfcn,
                onValueChange = onArfcnChange,
                label = label,
                colors = TextFieldDefaults.textFieldColors(labelColor = labelColor),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                insideMargin = DpSize(14.dp, 12.dp),
                textStyle = textStyle,
                keyboardOptions = keyboardOptions,
                keyboardActions = KeyboardActions(onDone = { onApply() }),
                trailingIcon = {
                    IconButton(onClick = { showArfcns = true }) {
                        DropdownChevron("Seen NR-ARFCNs")
                    }
                }
            )
            WindowListPopup(
                show = showArfcns,
                alignment = PopupPositionProvider.Align.Start,
                onDismissRequest = { showArfcns = false }
            ) {
                val dismiss = LocalDismissState.current
                ListPopupColumn {
                    if (seen.isEmpty()) {
                        DropdownImpl(text = "None seen yet", optionSize = 1, isSelected = false, index = 0,
                            enabled = false, onSelectedIndexChange = {})
                    }
                    seen.forEachIndexed { i, s ->
                        DropdownImpl(
                            text = listOfNotNull(s.arfcn.toString(), s.band?.let { "n$it" }).joinToString("  ·  "),
                            optionSize = seen.size,
                            isSelected = arfcn.trim() == s.arfcn.toString(),
                            index = i,
                            onSelectedIndexChange = {
                                onArfcnChange(s.arfcn.toString())
                                s.scsKhz?.takeIf { it in NR_SCS_KHZ }?.let(onScsChange)
                                dismiss?.invoke()
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            Row(
                Modifier.fillMaxHeight().clip(shape)
                    .background(MiuixTheme.colorScheme.secondaryContainer)
                    .clickable { showScs = true }
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$scsKhz", style = textStyle, color = MiuixTheme.colorScheme.onBackground)
                DropdownChevron("SCS", Modifier.padding(start = 6.dp))
            }
            WindowListPopup(
                show = showScs,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { showScs = false }
            ) {
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
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(shape)
                // Grey (the fields' own background) while there's nothing new to send.
                .background(if (canApply) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
                .clickable(enabled = canApply, onClick = onApply),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                MiuixIcons.Ok,
                contentDescription = "Apply NR-ARFCN lock",
                tint = if (canApply) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp)
            )
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
