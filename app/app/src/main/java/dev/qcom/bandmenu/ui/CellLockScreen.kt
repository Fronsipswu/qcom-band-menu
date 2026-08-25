package dev.qcom.bandmenu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.qcom.bandmenu.CellLockState
import dev.qcom.bandmenu.ModemState
import dev.qcom.bandmenu.PlmnLockState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    lockResult: CellLockResult?,
    onLockResultConsumed: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    snackbarHostState: SnackbarHostState,
    backdrop: Backdrop? = null
) {
    val density = LocalDensity.current
    val navbarHeightDp = 64.dp
    val navInset = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val navbarSpace = navbarHeightDp + 16.dp + navInset
    val statusBarInset = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()
    val topBarHeight = statusBarInset + TopAppBarDefaults.CollapsedHeight

    val hapticFeedback = LocalHapticFeedback.current
    val hasNrHardware = modemState?.hardware?.nr?.isNotEmpty() == true

    var selectedSim by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var experimentalEnabled by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { 2 })

    LaunchedEffect(selectedSim) {
        if (pagerState.targetPage != selectedSim) {
            pagerState.animateScrollToPage(selectedSim)
        }
    }
    LaunchedEffect(pagerState.targetPage) {
        if (selectedSim != pagerState.targetPage) {
            selectedSim = pagerState.targetPage
            onSimSwitch(pagerState.targetPage)
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
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
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topBarHeight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topBarHeight)
                            .padding(horizontal = 16.dp)
                    ) {
                        TabRowWithContour(
                            tabs = listOf("SIM 1", "SIM 2"),
                            selectedTabIndex = selectedSim,
                            onTabSelected = { index ->
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                selectedSim = index
                                onSimSwitch(index)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) { page ->
                            SimCellLockPage(
                                simSlot = page,
                                cellLockState = if (page == 0) modemState.sim1CellLock else modemState.sim2CellLock,
                                plmnLockState = if (page == 0) modemState.sim1PlmnLock else modemState.sim2PlmnLock,
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
                            .background(Color.Black.copy(alpha = 0.6f))
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
                val menuEntries = if (hasNrHardware) {
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
                }
                WindowIconDropdownMenu(
                    entries = menuEntries,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                    collapseOnSelection = true
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

    var nrArfcnText by remember { mutableStateOf("") }
    var nrPciText by remember { mutableStateOf("") }
    var nrMultiPciText by remember { mutableStateOf("") }
    var nrGnbText by remember { mutableStateOf("") }
    var ltePciText by remember { mutableStateOf("") }
    var lteMultiPciText by remember { mutableStateOf("") }
    var plmnText by remember { mutableStateOf("") }

    var labelOverrides by remember { mutableStateOf<Map<Int, LabelOverride>>(emptyMap()) }

    LaunchedEffect(cellLockState, plmnLockState, refreshKey) {
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

        ltePciText = if (lte?.valid == true && lte.locks.isNotEmpty()) {
            "${lte.locks[0].earfcn} ${lte.locks[0].pci}"
        } else ""

        lteMultiPciText = if (lte?.valid == true && lte.locks.size > 1) {
            "${lte.locks[0].earfcn} ${lte.locks.joinToString(" ") { it.pci.toString() }}"
        } else ""

        plmnText = if (plmnLockState?.lockedPlmn != null) {
            val lp = plmnLockState.lockedPlmn!!
            val mncStr = lp.mncDisplay.ifBlank { lp.mnc.toString() }
            "${lp.mcc} $mncStr"
        } else if (plmnLockState?.mcc != null && plmnLockState.mnc != null) {
            "${plmnLockState.mcc} ${plmnLockState.mnc}"
        } else ""
    }

    LaunchedEffect(lockResult) {
        if (lockResult != null && lockResult.sim == simSlot + 1) {
            val override = if (lockResult.success) {
                LabelOverride("Success locking ${lockResult.type} \u2713", GreenColor)
            } else {
                val msg = lockResult.message ?: "Rejected by modem"
                LabelOverride("$msg \u2717", RedColor)
            }
            labelOverrides = labelOverrides + (lockResult.fieldIndex to override)
            delay(3000)
            labelOverrides = labelOverrides - lockResult.fieldIndex
            onLockResultConsumed()
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

    val numericKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
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
                keyboardOptions = numericKeyboard,
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
                keyboardOptions = numericKeyboard,
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
                    keyboardOptions = numericKeyboard,
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
                    keyboardOptions = numericKeyboard,
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
            keyboardOptions = numericKeyboard,
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
            keyboardOptions = numericKeyboard,
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
            keyboardOptions = numericKeyboard,
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                onApplyLock(simSlot + 1, 5, plmnText)
            })
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
