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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    bandFilter: dev.qcom.bandmenu.BandFilterState,
    onSaveFilter: (dev.qcom.bandmenu.BandFilterState) -> Unit,
    onClearFilter: () -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 3 })

    LaunchedEffect(selectedIndex) {
        if (pagerState.targetPage != selectedIndex) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    LaunchedEffect(pagerState.targetPage) {
        selectedIndex = pagerState.targetPage
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
                        nrIndependentSupported = nrIndependentSupported,
                        bandFilter = bandFilter,
                        onSaveFilter = onSaveFilter,
                        onClearFilter = onClearFilter,
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
                        lockResult = cellLockResult,
                        onLockResultConsumed = onCellLockResultConsumed,
                        snackbarHostState = snackbarHostState,
                        backdrop = backdrop
                    )
                    else -> InfoScreen(
                        contentPadding = topPadding,
                        debugEnabled = debugEnabled,
                        onDebugToggle = onDebugToggle
                    )
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
                    onSelected = { selectedIndex = it },
                    backdrop = backdrop,
                    tabsCount = 3
                ) {
                    FloatingBottomBarItem(onClick = { selectedIndex = 0 }) {
                        Icon(imageVector = MiuixIcons.Phone, contentDescription = "Bands",
                            tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
                        Text("Bands", style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 1 }) {
                        Icon(imageVector = MiuixIcons.Search, contentDescription = "Cells",
                            tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
                        Text("Cells", style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 2 }) {
                        Icon(imageVector = MiuixIcons.Info, contentDescription = "Info",
                            tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
                        Text("Info", style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp))
                    }
                }
            }

            // Snackbar overlay (above the floating navbar)
            val density = LocalDensity.current
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
