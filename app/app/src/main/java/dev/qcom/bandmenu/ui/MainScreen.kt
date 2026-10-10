package dev.qcom.bandmenu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import androidx.compose.foundation.layout.size
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.qcom.bandmenu.ui.component.FloatingBottomBar
import dev.qcom.bandmenu.ui.component.FloatingBottomBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(
    onApply: (Int, dev.qcom.bandmenu.SimState) -> Unit,
    onReset: (Int) -> Unit,
    onDetach: () -> Unit,
    onAttach: () -> Unit,
    onReattach: () -> Unit,
    refreshingSlots: Set<Int>,
    onRefresh: (Int) -> Unit,
    refreshKey0: Int,
    refreshKey1: Int,
    modemState: dev.qcom.bandmenu.ModemState?,
    isLoading: Boolean,
    showRootDeniedDialog: Boolean,
    onRootRetry: () -> Unit,
    onDismissRootDialog: () -> Unit,
    showErrorDialog: Boolean,
    errorDialogTitle: String,
    errorDialogMessage: String,
    onDismissErrorDialog: () -> Unit,
    snackbarHostState: top.yukonga.miuix.kmp.basic.SnackbarHostState,
    snackbarMessage: String?,
    snackbarIsError: Boolean,
    onSnackbarShown: () -> Unit,
    debugEnabled: Boolean,
    onDebugToggle: () -> Unit,
    onCellLockSimSwitch: (Int) -> Unit,
    onCellLockApply: (Int, Int, String) -> Unit,
    onCellLockClearAll: (Int) -> Unit,
    onCellLockClear5G: (Int) -> Unit,
    onCellLockClear4G: (Int) -> Unit,
    onCellLockClearPlmn: (Int) -> Unit,
    cellLockResult: CellLockResult?,
    onCellLockResultConsumed: () -> Unit,
    cellLockRefreshing: Boolean,
    onCellLockRefresh: () -> Unit,
    cellLockRefreshKey: Int,
    nrIndependentSupported: Boolean?,
    simLabels: List<String> = listOf("SIM 1", "SIM 2"),
    bandFilter: dev.qcom.bandmenu.BandFilterState,
    onSaveFilter: (dev.qcom.bandmenu.BandFilterState) -> Unit,
    onClearFilter: () -> Unit,
    quickSelectHidden: Boolean = false,
    onToggleQuickSelect: () -> Unit = {},
    usageSettingHidden: Boolean = false,
    onToggleUsageSetting: () -> Unit = {},
    nrModeHidden: Boolean = false,
    onToggleNrMode: () -> Unit = {}
) {
    // The pager is the single source of truth for the selected page. Mirroring
    // it into a separate state with a pair of LaunchedEffects let a stale
    // animateScrollToPage cancel the release fling (both run at
    // MutatePriority.Default), and when that effect was itself cancelled by its
    // key changing, the pager could stay between pages.
    val pagerState = rememberPagerState(pageCount = { 3 })
    val pagerScope = rememberCoroutineScope()
    val selectedIndex by remember { derivedStateOf { pagerState.targetPage } }
    fun selectPage(index: Int) {
        if (pagerState.targetPage != index) {
            pagerScope.launch { pagerState.animateScrollToPage(index) }
        }
    }

    // An inner SIM pager on its last page hands an edge swipe to this pager
    // through nested scroll, but the release fling stays with the inner pager,
    // which can't move. This pager then gets no fling of its own: it either
    // stopped between pages or snapped back to the nearest page, so only a
    // long, slow drag reached the next page. When a child's horizontal fling
    // starts while this pager is off a page boundary, settle it here (toward
    // the fling, or to the nearest page if slow) and take the velocity.
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val settleAfterChildFling = remember(pagerState, density, isLtr) {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                val offset = pagerState.currentPageOffsetFraction
                if (abs(offset) < 0.001f || abs(available.x) < abs(available.y)) return Velocity.Zero
                val position = pagerState.currentPage + offset
                // Finger moving toward the start of the layout = next page.
                val towardNext = if (isLtr) -available.x else available.x
                val threshold = with(density) { SettleVelocityThreshold.toPx() }
                val target = when {
                    towardNext > threshold -> ceil(position)
                    towardNext < -threshold -> floor(position)
                    else -> round(position)
                }.toInt().coerceIn(0, pagerState.pageCount - 1)
                // Run the settle here, not in a separate coroutine: the fling
                // then carries on only after it, so this pager's own post-fling
                // snap (velocity already taken) finds it on a page and does
                // nothing. A launched animation was cancelled by that snap.
                try {
                    pagerState.animateScrollToPage(target)
                } catch (e: CancellationException) {
                    // A new touch took over; let it.
                }
                return Velocity(available.x, 0f)
            }
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop(onDraw = {
        drawRect(surfaceColor)
        drawContent()
    })

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        val topPadding = PaddingValues(top = innerPadding.calculateTopPadding())

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 2,
                userScrollEnabled = true,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)
            ) { page ->
                Box(modifier = Modifier.fillMaxSize().nestedScroll(settleAfterChildFling)) {
                    when (page) {
                        0 -> BandLockScreen(
                            modemState = modemState,
                            isLoading = isLoading,
                            refreshingSlots = refreshingSlots,
                            onRefresh = onRefresh,
                            refreshKey0 = refreshKey0,
                            refreshKey1 = refreshKey1,
                            onApply = onApply,
                            onReset = onReset,
                            onDetach = onDetach,
                            onAttach = onAttach,
                            onReattach = onReattach,
                            nrIndependentSupported = nrIndependentSupported,
                            simLabels = simLabels,
                            bandFilter = bandFilter,
                            onSaveFilter = onSaveFilter,
                            onClearFilter = onClearFilter,
                            quickSelectHidden = quickSelectHidden,
                            onToggleQuickSelect = onToggleQuickSelect,
                            usageSettingHidden = usageSettingHidden,
                            onToggleUsageSetting = onToggleUsageSetting,
                            nrModeHidden = nrModeHidden,
                            onToggleNrMode = onToggleNrMode,
                            snackbarHostState = snackbarHostState,
                            backdrop = backdrop
                        )
                        1 -> CellLockScreen(
                            modemState = modemState,
                            isLoading = isLoading,
                            isRefreshing = cellLockRefreshing,
                            onRefresh = onCellLockRefresh,
                            refreshKey = cellLockRefreshKey,
                            onSimSwitch = onCellLockSimSwitch,
                            onApplyLock = onCellLockApply,
                            onClearAll = onCellLockClearAll,
                            onClear5G = onCellLockClear5G,
                            onClear4G = onCellLockClear4G,
                            onClearPlmn = onCellLockClearPlmn,
                            onDetach = onDetach,
                            onAttach = onAttach,
                            onReattach = onReattach,
                            lockResult = cellLockResult,
                            simLabels = simLabels,
                            onLockResultConsumed = onCellLockResultConsumed,
                            snackbarHostState = snackbarHostState,
                            backdrop = backdrop
                        )
                        else -> InfoScreen(
                            contentPadding = topPadding,
                            debugEnabled = debugEnabled,
                            onDebugToggle = onDebugToggle,
                            onDetach = onDetach,
                            onAttach = onAttach,
                            onReattach = onReattach
                        )
                    }
                }
            }

            // Navbar overlay (floating on top of content, edge-to-edge)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                FloatingBottomBar(
                    selectedIndex = { selectedIndex },
                    onSelected = { selectPage(it) },
                    backdrop = backdrop,
                    tabsCount = 3
                ) {
                    FloatingBottomBarItem(onClick = { selectPage(0) }) {
                        Icon(imageVector = MiuixIcons.Phone, contentDescription = "Bands",
                            tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
                        Text("Bands", style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectPage(1) }) {
                        Icon(imageVector = MiuixIcons.Search, contentDescription = "Cells",
                            tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
                        Text("Cells", style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectPage(2) }) {
                        Icon(imageVector = MiuixIcons.Info, contentDescription = "Info",
                            tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
                        Text("Info", style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp))
                    }
                }
            }

            // Snackbar overlay (above the floating navbar)
            val navInset = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
            val navbarHeightDp = 64.dp
            // Bands page has Apply/Reset buttons (~72dp) at the bottom, so the snackbar
            // needs extra clearance. Cells and Info pages have no buttons.
            val buttonSpace = if (selectedIndex == 0) 62.dp else 0.dp
            top.yukonga.miuix.kmp.basic.SnackbarHost(
                state = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = navbarHeightDp + 16.dp + navInset + buttonSpace + 18.dp)
            ) { data ->
                top.yukonga.miuix.kmp.basic.Snackbar(
                    data = data,
                    colors = top.yukonga.miuix.kmp.basic.SnackbarDefaults.snackbarColors(
                        containerColor = if (snackbarIsError) androidx.compose.ui.graphics.Color(0xFFF44336) else androidx.compose.ui.graphics.Color(0xFF4CAF50),
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    )
                )
            }

            // Full-screen loading overlay (covers all pages until data is ready)
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    top.yukonga.miuix.kmp.basic.CircularProgressIndicator()
                }
            }

            // Status bar fade overlay
            val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarHeight + 24.dp)
                    .align(Alignment.TopStart)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(surfaceColor, surfaceColor.copy(alpha = 0f))
                        )
                    )
            )

            // Error dialogs
            if (showRootDeniedDialog) {
                top.yukonga.miuix.kmp.window.WindowDialog(
                    show = true,
                    title = "Root Access Required",
                    summary = "This app requires root access to communicate with the Qualcomm modem. Please grant root access and retry.",
                    onDismissRequest = onDismissRootDialog,
                    content = {
                        top.yukonga.miuix.kmp.basic.TextButton(
                            text = "Retry",
                            onClick = onRootRetry,
                            modifier = Modifier.fillMaxWidth(),
                            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                )
            }

            if (showErrorDialog) {
                top.yukonga.miuix.kmp.window.WindowDialog(
                    show = true,
                    title = errorDialogTitle,
                    summary = errorDialogMessage,
                    onDismissRequest = onDismissErrorDialog,
                    content = {
                        top.yukonga.miuix.kmp.basic.TextButton(
                            text = "OK",
                            onClick = onDismissErrorDialog,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }

            // Snackbar
            LaunchedEffect(snackbarMessage) {
                if (snackbarMessage != null) {
                    snackbarHostState.showSnackbar(snackbarMessage)
                    onSnackbarShown()
                }
            }
        }
    }
}

/** Same fling threshold as Compose's pager snapping (PagerDefaults). */
private val SettleVelocityThreshold = 400.dp
