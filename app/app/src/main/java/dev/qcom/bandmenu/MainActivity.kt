package dev.qcom.bandmenu

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.qcom.bandmenu.ui.MainScreen
import dev.qcom.bandmenu.network.DiagClient
import dev.qcom.bandmenu.network.SimSlotStatus
import dev.qcom.bandmenu.ui.CellLockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import java.io.File

private val ComponentActivity.bandDataStore by preferencesDataStore(name = "band_prefs")

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QcomBand"
        private const val SIM2_UNAVAILABLE_MESSAGE =
            "SIM 2 is not available (single-SIM mode or DSDS off)"
    }

    // Held by a ViewModel so a configuration change (dark mode, font scale,
    // window resize...) keeps the daemon and its connection instead of
    // killing and relaunching it. Stopped in DaemonHolder.onCleared().
    private val daemonHolder by lazy {
        ViewModelProvider(this, viewModelFactory {
            initializer { DaemonHolder(DaemonManager(applicationContext), DiagClient(applicationContext), java.io.File(cacheDir, "netreplay")) }
        })[DaemonHolder::class.java]
    }
    private val daemonManager get() = daemonHolder.daemonManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Intercept back gesture: stop the daemon synchronously before
        // letting the activity finish. This guarantees the daemon is killed
        // even though onDestroy is not reliably called by the system.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Thread {
                    daemonHolder.networkMonitor.stopDiagBlocking()
                    daemonManager.stopBlocking()
                    runOnUiThread { finish() }
                }.start()
            }
        })

        setContent {
            MiuixTheme(colors = darkColorScheme()) {
                val snackbarHostState = remember { SnackbarHostState() }

                var modemState by remember { mutableStateOf<ModemState?>(null) }
                var isLoading by remember { mutableStateOf(true) }
                var showRootDenied by remember { mutableStateOf(false) }
                var showErrorDialog by remember { mutableStateOf(false) }
                var errorTitle by remember { mutableStateOf("") }
                var errorMessage by remember { mutableStateOf("") }
                var snackbarMessage by remember { mutableStateOf<String?>(null) }
                var snackbarIsError by remember { mutableStateOf(false) }
                var refreshingSlots by remember { mutableStateOf(emptySet<Int>()) }
                var refreshKey0 by remember { mutableIntStateOf(0) }
                var refreshKey1 by remember { mutableIntStateOf(0) }
                var debugEnabled by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val modemLock = daemonHolder.modemLock

                var cellLockResult by remember { mutableStateOf<CellLockResult?>(null) }
                var cellLockRefreshing by remember { mutableStateOf(false) }
                var cellLockRefreshKey by remember { mutableIntStateOf(0) }
                var cellLockSelectedSim by remember { mutableIntStateOf(0) }
                var nrIndependentSupported by remember { mutableStateOf<Boolean?>(null) }
                var bandFilter by remember { mutableStateOf(BandFilterState()) }
                var quickSelectHidden by remember { mutableStateOf(false) }
                var usageSettingHidden by remember { mutableStateOf(false) }
                var nrModeHidden by remember { mutableStateOf(false) }
                var servingHidden by remember { mutableStateOf(false) }
                var thputHidden by remember { mutableStateOf(false) }
                var endcSeparate by remember { mutableStateOf(true) }
                var navbarHidden by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    BandPreferences.getDebugLogging(bandDataStore).collectLatest { enabled ->
                        debugEnabled = enabled
                        AppLog.debugEnabled = enabled
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getBandFilter(bandDataStore).collectLatest { state ->
                        bandFilter = state
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getQuickSelectHidden(bandDataStore).collectLatest { hidden ->
                        quickSelectHidden = hidden
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getUsageSettingHidden(bandDataStore).collectLatest { hidden ->
                        usageSettingHidden = hidden
                    }
                }

                // Replay (Network page menu): persisted; on, a foreground service
                // keeps the poller recording with the app in the background.
                LaunchedEffect(Unit) {
                    BandPreferences.getReplayEnabled(bandDataStore).collectLatest { on ->
                        daemonHolder.networkMonitor.setReplayEnabled(on)
                        if (on) ReplayService.start(applicationContext) else ReplayService.stop(applicationContext)
                    }
                }
                // DIAG (Network page menu): persisted. The menu toggles the
                // monitor itself; this applies the saved setting at start.
                LaunchedEffect(Unit) {
                    BandPreferences.getDiagEnabled(bandDataStore).collectLatest { on ->
                        val monitor = daemonHolder.networkMonitor
                        if (monitor.state.value.diagEnabled != on) monitor.setDiagEnabled(on)
                    }
                }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

                LaunchedEffect(Unit) {
                    BandPreferences.getServingHidden(bandDataStore).collectLatest { hidden ->
                        servingHidden = hidden
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getThputHidden(bandDataStore).collectLatest { hidden ->
                        thputHidden = hidden
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getEndcSeparate(bandDataStore).collectLatest { separate ->
                        endcSeparate = separate
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getNavbarHidden(bandDataStore).collectLatest { hidden ->
                        navbarHidden = hidden
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getNrModeHidden(bandDataStore).collectLatest { hidden ->
                        nrModeHidden = hidden
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getNrIndependentSupported(bandDataStore).collectLatest { supported ->
                        if (supported != null && nrIndependentSupported == null) {
                            nrIndependentSupported = supported
                            AppLog.i(TAG, "NR independent supported (cached): $supported")
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    daemonManager.onConnectionEvent = { restored ->
                        Handler(Looper.getMainLooper()).post {
                            if (restored) {
                                snackbarIsError = false
                                snackbarMessage = "Daemon connection restored"
                            } else {
                                snackbarIsError = true
                                snackbarMessage = "Daemon connection lost — retrying..."
                            }
                        }
                    }
                    daemonManager.start(
                        onDenied = {
                            showRootDenied = true
                            isLoading = false
                        },
                        onLaunchFailed = { msg ->
                            errorTitle = "Daemon Launch Failed"
                            errorMessage = msg
                            showErrorDialog = true
                            isLoading = false
                        }
                    )
                }

                // Full init runs only while no modem state is loaded. A reconnect
                // (isReady false -> true inside sendRequest) happens while a user
                // operation holds modemLock; re-running init then would slip a
                // sim_set between that operation's commands and send the rest to
                // the other SIM. Init also takes the lock, so the two never overlap.
                // Bumped to run init again (SIM 2 turned on/off or inserted/removed).
                var initGeneration by remember { mutableIntStateOf(0) }
                LaunchedEffect(daemonManager.isReady.value, initGeneration) {
                    if (daemonManager.isReady.value && modemState == null) {
                        scope.launch {
                            modemLock.withLock {
                                isLoading = true
                                var initModemState: ModemState? = null
                                var initErrorTitle: String? = null
                                var initError: String? = null
                                var initNrIndependentSupported: Boolean? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        // sim_set binds the SIM, then the daemon runs the GET and
                                        // both cell-lock reads, so each reply is that SIM's full
                                        // state (hardware and NR capability come with every reply).
                                        // SIM 2 first, then SIM 1: two requests, and the daemon
                                        // ends bound to SIM 1, which detach/attach act on.
                                        // I8: Check ok on each response
                                        // SIM 2 is optional: on a single-SIM phone, or with DSDS
                                        // off, the modem refuses to bind subscription 2. That
                                        // marks SIM 2 unavailable instead of failing init, so
                                        // SIM 1 still works. Only a SIM 1 failure is fatal.
                                        // QMI only (no telephony API): UIM card status tells a SIM 2
                                        // that's turned off or taken out apart from "no service"
                                        // (both still bind with DSDS on). UNKNOWN (one-slot phone,
                                        // unreadable) falls back to the bind: with DSDS off or a
                                        // single SIM, the modem refuses subscription 2.
                                        val (sim1Status, sim2Status) = daemonHolder.networkMonitor.simStatuses()
                                        var sim2Available = sim2Status != SimSlotStatus.DISABLED && sim2Status != SimSlotStatus.NO_SIM
                                        var sim2Parsed: DaemonResponse? = null
                                        if (!sim2Available) {
                                            AppLog.i(TAG, "Init: SIM 2 card status $sim2Status, skipping SIM 2")
                                        } else {
                                            val r = JsonStateParser.parseResponse(daemonManager.simSet(2))
                                            if (r.ok) {
                                                sim2Parsed = r
                                            } else {
                                                sim2Available = false
                                                AppLog.w(TAG, "Init: SIM 2 unavailable (${r.error?.message ?: "unknown"}), continuing with SIM 1")
                                            }
                                        }
                                        val sim2State = sim2Parsed?.simState ?: SimState()
                                        val sim2CellLock = sim2Parsed?.cellLockState ?: CellLockState()
                                        val sim2PlmnLock = sim2Parsed?.plmnLockState ?: PlmnLockState()

                                        val sim1Parsed = JsonStateParser.parseResponse(daemonManager.simSet(1))
                                        if (!sim1Parsed.ok) {
                                            initErrorTitle = "Initialization Failed"
                                            initError = "Failed to select SIM 1: ${sim1Parsed.error?.message ?: "unknown"}"
                                            return@withContext
                                        }
                                        val sim1State = sim1Parsed.simState ?: SimState()
                                        val hardware = sim1Parsed.hardware ?: HardwareBands()
                                        val sim1CellLock = sim1Parsed.cellLockState ?: CellLockState()
                                        val sim1PlmnLock = sim1Parsed.plmnLockState ?: PlmnLockState()

                                        // Read NR independent capability from daemon response
                                        val nrCap = sim1Parsed.nrIndependentCapability
                                        if (nrCap != null && nrCap.checked && nrCap.independentLockSupported != null) {
                                            if (nrIndependentSupported == null) {
                                                initNrIndependentSupported = nrCap.independentLockSupported
                                                AppLog.i(TAG, "NR independent supported (daemon): ${nrCap.independentLockSupported}")
                                                // Persist to DataStore
                                                launch {
                                                    BandPreferences.setNrIndependentSupported(
                                                        bandDataStore, nrCap.independentLockSupported
                                                    )
                                                }
                                            }
                                        }

                                        initModemState = ModemState(
                                            sim1State, sim2State, hardware, true,
                                            sim1CellLock, sim2CellLock,
                                            sim1PlmnLock, sim2PlmnLock,
                                            initNrIndependentSupported ?: nrIndependentSupported,
                                            sim2Available = sim2Available,
                                            sim2Status = sim2Status,
                                            sim1Status = sim1Status
                                        )

                                        // I6: Enable verbose logging on startup if debug is enabled
                                        if (debugEnabled) {
                                            daemonManager.verboseSet(true)
                                        }

                                        AppLog.i(TAG, "Init: success")
                                    } catch (e: Exception) {
                                        AppLog.e(TAG, "Init: failed", e)
                                        initErrorTitle = "Initialization Failed"
                                        initError = "Failed to query modem state: ${e.message}"
                                    }
                                }
                                // C4: Back on Main dispatcher - update Compose state
                                if (initModemState != null) {
                                    modemState = initModemState
                                }
                                if (initNrIndependentSupported != null && nrIndependentSupported == null) {
                                    nrIndependentSupported = initNrIndependentSupported
                                }
                                if (initError != null) {
                                    errorTitle = initErrorTitle ?: "Initialization Failed"
                                    errorMessage = initError
                                    showErrorDialog = true
                                }
                                isLoading = false
                            }
                        }
                    }
                }

                // Either SIM can be turned off or taken out at any time. Re-read card
                // status on resume and on every pull-to-refresh. SIM 2: if it became
                // usable or unusable, init again so its state is (un)loaded and the SIM
                // pagers gain or drop their second page. SIM 1 stays bound (the modem
                // keeps subscription 1), so only its status (tab label, Network note)
                // changes. One read-only request. Every check reads card status afresh,
                // so an unreadable status at startup (UNKNOWN) doesn't stop later checks.
                fun recheckSim2() {
                    if (modemState == null || isLoading) return
                    scope.launch {
                        val (status1, status) = withContext(Dispatchers.IO) { daemonHolder.networkMonitor.simStatuses() }
                        if (status1 != SimSlotStatus.UNKNOWN && modemState != null && status1 != modemState?.sim1Status) {
                            AppLog.i(TAG, "SIM 1 card status ${modemState?.sim1Status} -> $status1")
                            modemState = modemState?.copy(sim1Status = status1)
                        }
                        val current = modemState ?: return@launch
                        // Unreadable this time: can't tell, try again on the next check.
                        // Unchanged: nothing to do (a SIM 2 that's ready but that the
                        // modem won't bind would otherwise re-init on every check).
                        if (status == SimSlotStatus.UNKNOWN || status == current.sim2Status) return@launch
                        if ((status == SimSlotStatus.ACTIVE) != current.sim2Available) {
                            AppLog.i(TAG, "SIM 2 card status ${current.sim2Status} -> $status, re-initialising")
                            modemState = null
                            initGeneration++
                        } else {
                            modemState = current.copy(sim2Status = status)
                        }
                    }
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) recheckSim2()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // SIM tab labels come from QMI (GET_PLMN_NAME / GET_HOME_NETWORK via
                // the daemon's read-only monitoring client), not the telephony API,
                // so no phone-state permission is needed.
                var simLabels by remember { mutableStateOf(listOf("SIM 1", "SIM 2")) }
                LaunchedEffect(modemState) {
                    val state = modemState ?: return@LaunchedEffect
                    val monitor = daemonHolder.networkMonitor
                    monitor.nrHardwareBands = state.hardware.nr
                    simLabels = withContext(Dispatchers.IO) {
                        simTabLabels(listOf(
                            when (state.sim1Status) {
                                SimSlotStatus.DISABLED -> "Off"
                                SimSlotStatus.NO_SIM -> "No SIM"
                                else -> monitor.operatorNameForLabel(1)
                            },
                            if (state.sim2Available) monitor.operatorNameForLabel(2)
                            else when (state.sim2Status) {
                                SimSlotStatus.DISABLED -> "Off"
                                SimSlotStatus.NO_SIM -> "No SIM"
                                else -> null
                            }
                        ))
                    }
                }

                // Commands for a SIM 2 the modem refused would fail anyway (or, for
                // refresh, read SIM 1); explain instead of sending them.
                fun sim2Unavailable(sim: Int): Boolean {
                    if (sim != 2 || modemState?.sim2Available != false) return false
                    snackbarIsError = true
                    snackbarMessage = SIM2_UNAVAILABLE_MESSAGE
                    return true
                }

                MainScreen(
                    networkMonitor = daemonHolder.networkMonitor,
                    onNetworkRefresh = { recheckSim2() },
                    servingHidden = servingHidden,
                    thputHidden = thputHidden,
                    onThputHiddenToggle = { hidden ->
                        thputHidden = hidden
                        scope.launch { BandPreferences.setThputHidden(bandDataStore, hidden) }
                    },
                    onServingHiddenToggle = { hidden ->
                        servingHidden = hidden
                        scope.launch { BandPreferences.setServingHidden(bandDataStore, hidden) }
                    },
                    endcSeparate = endcSeparate,
                    onEndcSeparateToggle = { separate ->
                        endcSeparate = separate
                        scope.launch { BandPreferences.setEndcSeparate(bandDataStore, separate) }
                    },
                    onDiagToggle = { on ->
                        daemonHolder.networkMonitor.setDiagEnabled(on)
                        scope.launch { BandPreferences.setDiagEnabled(bandDataStore, on) }
                    },
                    onReplayToggle = { on ->
                        if (on && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        scope.launch { BandPreferences.setReplayEnabled(bandDataStore, on) }
                    },
                    onCellLockSimSwitch = simSwitch@{ slot ->
                        cellLockSelectedSim = slot
                        if (slot == 1 && modemState?.sim2Available == false) return@simSwitch
                        scope.launch {
                            modemLock.withLock {
                                val sim = slot + 1
                                var newCellLock: CellLockState? = null
                                var newPlmnLock: PlmnLockState? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        val resp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (resp.ok) {
                                            newCellLock = resp.cellLockState
                                            newPlmnLock = resp.plmnLockState
                                        }
                                    } catch (e: Exception) {
                                        AppLog.e(TAG, "CellLock SIM switch: error", e)
                                    }
                                }
                                if (newCellLock != null) {
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock!!)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock!!)
                                }
                                if (newPlmnLock != null) {
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1PlmnLock = newPlmnLock!!)
                                    else
                                        modemState?.copy(sim2PlmnLock = newPlmnLock!!)
                                }
                            }
                        }
                    },
                    onCellLockApply = cellApply@{ sim, fieldIndex, input ->
                        if (sim2Unavailable(sim)) return@cellApply
                        scope.launch {
                            modemLock.withLock {
                                var success = false
                                var errorMsg: String? = null
                                var newCellLock: CellLockState? = null
                                var newPlmnLock: PlmnLockState? = null
                                val parts = input.trim().split(Regex("\\s+"))

                                withContext(Dispatchers.IO) {
                                    try {
                                        val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (!simResp.ok) {
                                            errorMsg = simResp.error?.message ?: "Failed to select SIM $sim"
                                            return@withContext
                                        }

                                        val resp = when (fieldIndex) {
                                            0 -> {
                                                if (parts.size < 2) { errorMsg = "Need: arfcn scs_khz"; return@withContext }
                                                JsonStateParser.parseResponse(daemonManager.nrCellLockArfcnSet(parts[0].toInt(), parts[1].toInt()))
                                            }
                                            1 -> {
                                                if (parts.size < 4) { errorMsg = "Need: arfcn pci scs_khz band"; return@withContext }
                                                JsonStateParser.parseResponse(daemonManager.nrCellLockPciSet(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt()))
                                            }
                                            2 -> {
                                                if (parts.size < 4) { errorMsg = "Need: arfcn scs_khz band pci..."; return@withContext }
                                                val arfcn = parts[0].toInt()
                                                val scs = parts[1].toInt()
                                                val band = parts[2].toInt()
                                                val pciList = parts.drop(3).map { it.toInt() }
                                                JsonStateParser.parseResponse(daemonManager.nrCellLockMultiPciSet(arfcn, scs, band, pciList))
                                            }
                                            3 -> {
                                                if (parts.size < 2) { errorMsg = "Need: id_bits gnb..."; return@withContext }
                                                val idBits = parts[0].toInt()
                                                val gnbIds = parts.drop(1).map { it.toInt() }
                                                JsonStateParser.parseResponse(daemonManager.nrCellLockGnbSet(idBits, gnbIds))
                                            }
                                            4 -> {
                                                if (parts.size < 2) { errorMsg = "Need: earfcn pci"; return@withContext }
                                                JsonStateParser.parseResponse(daemonManager.lteCellLockSet(parts[0].toInt(), parts[1].toInt()))
                                            }
                                            6 -> {
                                                if (parts.size < 2) { errorMsg = "Need: earfcn pci..."; return@withContext }
                                                val earfcn = parts[0].toInt()
                                                val pciList = parts.drop(1).map { it.toInt() }
                                                if (pciList.size > 64) { errorMsg = "Max 64 PCIs"; return@withContext }
                                                if (pciList.any { it < 0 || it > 503 }) { errorMsg = "PCI must be 0-503"; return@withContext }
                                                JsonStateParser.parseResponse(daemonManager.lteCellLockMultiPciSet(earfcn, pciList))
                                            }
                                            5 -> {
                                                if (parts.size != 2) { errorMsg = "Need: mcc mnc"; return@withContext }
                                                val mcc = parts[0].toIntOrNull()
                                                val mnc = parts[1].toIntOrNull()
                                                if (mcc == null || mcc < 0 || mcc > 999 || mnc == null || mnc < 0 || mnc > 999) {
                                                    errorMsg = "MCC and MNC must be 0-999"; return@withContext
                                                }
                                                JsonStateParser.parseResponse(daemonManager.plmnLockSet(mcc, mnc))
                                            }
                                            else -> { errorMsg = "Unknown field"; return@withContext }
                                        }

                                        success = resp.ok
                                        if (!resp.ok) {
                                            errorMsg = resp.error?.message ?: "Rejected by modem"
                                        }
                                        if (fieldIndex == 5) {
                                            newPlmnLock = resp.plmnLockState
                                        } else {
                                            newCellLock = resp.cellLockState
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = "Apply failed: ${e.message}"
                                        AppLog.e(TAG, "CellLock apply: error", e)
                                    }
                                }

                                val typeName = when (fieldIndex) {
                                    0 -> "NR-ARFCN"; 1 -> "PCI"; 2 -> "MultiPCI"; 3 -> "gNB"; 4 -> "LTE PCI"; 6 -> "LTE MultiPCI"; 5 -> "PLMN"; else -> "Unknown"
                                }
                                cellLockResult = CellLockResult(sim, fieldIndex, typeName, success, errorMsg)

                                if (newCellLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock!!)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock!!)
                                }

                                if (newPlmnLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1PlmnLock = newPlmnLock!!)
                                    else
                                        modemState?.copy(sim2PlmnLock = newPlmnLock!!)
                                }
                            }
                        }
                    },
                    onCellLockClearAll = clearAll@{ sim ->
                        if (sim2Unavailable(sim)) return@clearAll
                        scope.launch {
                            modemLock.withLock {
                                var newCellLock: CellLockState? = null
                                var errorMsg: String? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (!simResp.ok) {
                                            errorMsg = simResp.error?.message ?: "Failed to select SIM $sim"
                                            return@withContext
                                        }
                                        // NR clear: only when the device has NR hardware. The modem
                                        // may reject if no lock active — that's the desired state.
                                        // Wrap in own try-catch so I/O errors don't abort the LTE
                                        // clear.
                                        if (modemState?.hardware?.nr?.isNotEmpty() == true) {
                                            try {
                                                JsonStateParser.parseResponse(daemonManager.nrCellLockClear())
                                            } catch (e: Exception) {
                                                AppLog.w(TAG, "CellLock clear all: NR clear failed (ignored)", e)
                                            }
                                        }
                                        val lteResp = JsonStateParser.parseResponse(daemonManager.lteCellLockClear())
                                        if (!lteResp.ok) errorMsg = lteResp.error?.message
                                        newCellLock = lteResp.cellLockState
                                    } catch (e: Exception) {
                                        errorMsg = "Clear all failed: ${e.message}"
                                        AppLog.e(TAG, "CellLock clear all: error", e)
                                    }
                                }
                                if (newCellLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock!!)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock!!)
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: "Cleared all cell locks for SIM $sim"
                            }
                        }
                    },
                    onCellLockClear5G = clear5g@{ sim ->
                        if (sim2Unavailable(sim)) return@clear5g
                        scope.launch {
                            modemLock.withLock {
                                var newCellLock: CellLockState? = null
                                var ioError = false
                                var errorMsg: String? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (!simResp.ok) {
                                            errorMsg = simResp.error?.message ?: "Failed to select SIM $sim"
                                            return@withContext
                                        }
                                        try {
                                            val resp = JsonStateParser.parseResponse(daemonManager.nrCellLockClear())
                                            newCellLock = resp.cellLockState
                                        } catch (e: Exception) {
                                            // NR clear I/O error — not a modem rejection.
                                            AppLog.e(TAG, "CellLock clear 5G: I/O error", e)
                                            ioError = true
                                        }
                                    } catch (e: Exception) {
                                        AppLog.e(TAG, "CellLock clear 5G: error", e)
                                        ioError = true
                                    }
                                }
                                if (newCellLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock!!)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock!!)
                                }
                                snackbarIsError = errorMsg != null || ioError
                                snackbarMessage = when {
                                    errorMsg != null -> errorMsg
                                    ioError -> "Clear 5G failed (connection error)"
                                    else -> "Cleared 5G cell lock for SIM $sim"
                                }
                            }
                        }
                    },
                    onCellLockClear4G = clear4g@{ sim ->
                        if (sim2Unavailable(sim)) return@clear4g
                        scope.launch {
                            modemLock.withLock {
                                var newCellLock: CellLockState? = null
                                var errorMsg: String? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (!simResp.ok) {
                                            errorMsg = simResp.error?.message ?: "Failed to select SIM $sim"
                                            return@withContext
                                        }
                                        val resp = JsonStateParser.parseResponse(daemonManager.lteCellLockClear())
                                        if (!resp.ok) errorMsg = resp.error?.message
                                        newCellLock = resp.cellLockState
                                    } catch (e: Exception) {
                                        errorMsg = "Clear 4G failed: ${e.message}"
                                        AppLog.e(TAG, "CellLock clear 4G: error", e)
                                    }
                                }
                                if (newCellLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock!!)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock!!)
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: "Cleared 4G cell lock for SIM $sim"
                            }
                        }
                    },
                    onCellLockClearPlmn = clearPlmn@{ sim ->
                        if (sim2Unavailable(sim)) return@clearPlmn
                        scope.launch {
                            modemLock.withLock {
                                var newPlmnLock: PlmnLockState? = null
                                var errorMsg: String? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (!simResp.ok) {
                                            errorMsg = simResp.error?.message ?: "Failed to select SIM $sim"
                                            return@withContext
                                        }
                                        val resp = JsonStateParser.parseResponse(daemonManager.plmnLockClear())
                                        if (!resp.ok) errorMsg = resp.error?.message
                                        delay(200)
                                        val resp2 = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        newPlmnLock = resp2.plmnLockState ?: resp.plmnLockState
                                    } catch (e: Exception) {
                                        errorMsg = "Clear PLMN failed: ${e.message}"
                                        AppLog.e(TAG, "CellLock clear PLMN: error", e)
                                    }
                                }
                                if (newPlmnLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1PlmnLock = newPlmnLock!!)
                                    else
                                        modemState?.copy(sim2PlmnLock = newPlmnLock!!)
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: "Cleared PLMN lock for SIM $sim"
                            }
                        }
                    },
                    cellLockResult = cellLockResult,
                    onCellLockResultConsumed = { cellLockResult = null },
                    cellLockRefreshing = cellLockRefreshing,
                    onCellLockRefresh = cellRefresh@{
                        recheckSim2()
                        if (sim2Unavailable(cellLockSelectedSim + 1)) return@cellRefresh
                        scope.launch {
                            cellLockRefreshing = true
                            modemLock.withLock {
                                var newCellLock: CellLockState? = null
                                var newPlmnLock: PlmnLockState? = null
                                var errorMsg: String? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        // Switch to the selected SIM first to ensure we read
                                        // the correct SIM's cell lock state, not whatever SIM
                                        // the daemon happened to be on.
                                        val sim = cellLockSelectedSim + 1
                                        val resp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (resp.ok) {
                                            newCellLock = resp.cellLockState
                                            newPlmnLock = resp.plmnLockState
                                        } else {
                                            errorMsg = resp.error?.message ?: "Refresh failed"
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = "Refresh failed: ${e.message}"
                                        AppLog.e(TAG, "CellLock refresh: error", e)
                                    }
                                }
                                if (newCellLock != null) {
                                    val slot = cellLockSelectedSim
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock!!)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock!!)
                                    cellLockRefreshKey++
                                }
                                if (newPlmnLock != null) {
                                    val slot = cellLockSelectedSim
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1PlmnLock = newPlmnLock!!)
                                    else
                                        modemState?.copy(sim2PlmnLock = newPlmnLock!!)
                                }
                                if (errorMsg != null) {
                                    snackbarIsError = true
                                    snackbarMessage = errorMsg
                                }
                                cellLockRefreshing = false
                            }
                        }
                    },
                    cellLockRefreshKey = cellLockRefreshKey,
                    nrIndependentSupported = nrIndependentSupported,
                    simLabels = simLabels,
                    bandFilter = bandFilter,
                    onSaveFilter = { state ->
                        scope.launch { BandPreferences.saveBandFilter(bandDataStore, state) }
                    },
                    onClearFilter = {
                        scope.launch { BandPreferences.clearBandFilter(bandDataStore) }
                    },
                    quickSelectHidden = quickSelectHidden,
                    onToggleQuickSelect = {
                        val hidden = !quickSelectHidden
                        quickSelectHidden = hidden
                        scope.launch { BandPreferences.setQuickSelectHidden(bandDataStore, hidden) }
                    },
                    usageSettingHidden = usageSettingHidden,
                    onToggleUsageSetting = {
                        val hidden = !usageSettingHidden
                        usageSettingHidden = hidden
                        scope.launch { BandPreferences.setUsageSettingHidden(bandDataStore, hidden) }
                    },
                    nrModeHidden = nrModeHidden,
                    onToggleNrMode = {
                        val hidden = !nrModeHidden
                        nrModeHidden = hidden
                        scope.launch { BandPreferences.setNrModeHidden(bandDataStore, hidden) }
                    },
                    onApply = apply@{ slot, state ->
                        if (sim2Unavailable(slot + 1)) return@apply
                        // A RAT lock with no band for any of its RATs is rejected by
                        // the modem; say why instead of sending it.
                        val checked = modemState?.hardware?.let { BandValidator.validateSimState(state, it) } ?: state
                        BandValidator.noBandsForRatLock(checked)?.let {
                            snackbarIsError = true
                            snackbarMessage = it
                            return@apply
                        }
                        scope.launch {
                            modemLock.withLock {
                                val sim = slot + 1
                                var firstError: DaemonError? = null
                                var newState: SimState? = null
                                var errorMsg: String? = null

                                withContext(Dispatchers.IO) {
                                    try {
                                        // I3: Validate bands against hardware before sending
                                        val hw = modemState?.hardware
                                        val validatedState = if (hw != null)
                                            BandValidator.validateSimState(state, hw) else state

                                        val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        // C2: If simSet fails, abort early
                                        if (!simResp.ok) {
                                            errorMsg = simResp.error?.message ?: "Failed to select SIM $sim"
                                            return@withContext
                                        }

                                        val hasNrHardware = hw != null && hw.nr.isNotEmpty()
                                        val useIndependentLock = nrIndependentSupported == true
                                        val commands = buildList {
                                            add(JsonRequestBuilder.gsmSet(validatedState.gsmBands))
                                            add(JsonRequestBuilder.wcdmaSet(validatedState.wcdmaBands))
                                            add(JsonRequestBuilder.lteSet(validatedState.lteBands))
                                            if (hasNrHardware) {
                                                if (useIndependentLock) {
                                                    add(JsonRequestBuilder.nrNsaSet(validatedState.nrNsaBands))
                                                    add(JsonRequestBuilder.nrSaSet(validatedState.nrSaBands))
                                                } else {
                                                    add(JsonRequestBuilder.nrSet(validatedState.nrNsaBands))
                                                }
                                                add(JsonRequestBuilder.modeSet(validatedState.nrMode))
                                            }
                                            if (validatedState.usageMode != UsageMode.UNKNOWN) {
                                                add(JsonRequestBuilder.usagePrefSet(validatedState.usageMode))
                                            }
                                            add(JsonRequestBuilder.ratSet(validatedState.ratMask))
                                        }

                                        // C2: Check every response's ok field, collect first error
                                        var lastResp: DaemonResponse = simResp
                                        for (cmd in commands) {
                                            lastResp = JsonStateParser.parseResponse(daemonManager.sendRequest(cmd))
                                            if (!lastResp.ok && firstError == null) {
                                                firstError = lastResp.error
                                            }
                                        }
                                        // Give the modem time to apply changes before re-reading state.
                                        // The GET after each SET may read stale data if the modem hasn't
                                        // fully applied the change yet (especially for RAT mask changes).
                                        delay(200)
                                        val queryResp = JsonStateParser.parseResponse(daemonManager.query())
                                        newState = queryResp.simState ?: lastResp.simState
                                    } catch (e: Exception) {
                                        errorMsg = "Apply failed: ${e.message}"
                                        AppLog.e(TAG, "Apply SIM $sim: error", e)
                                    }
                                }
                                // C4: Back on Main dispatcher - update Compose state
                                // Always increment refreshKey to force LaunchedEffect re-sync,
                                // even when the modem state is unchanged (e.g. command rejected).
                                // Without this, SimState data-class equality suppresses the
                                // re-sync and checkboxes stay in the user's desired state.
                                if (newState != null) {
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1 = newState) ?: ModemState(sim1 = newState)
                                    else
                                        modemState?.copy(sim2 = newState) ?: ModemState(sim2 = newState)
                                }
                                if (slot == 0) refreshKey0++ else refreshKey1++
                                snackbarIsError = errorMsg != null || firstError != null
                                snackbarMessage = if (errorMsg != null) errorMsg
                                    else if (firstError != null) {
                                        val msg = firstError.message
                                        val rejected = firstError.rejectedBands
                                        if (rejected != null && rejected.isNotEmpty())
                                            "$msg (rejected: $rejected)" else msg
                                    } else "Settings applied for SIM $sim"
                                if (errorMsg == null && firstError == null) {
                                    AppLog.i(TAG, "Apply SIM $sim: success")
                                }
                            }
                        }
                    },
                    onReset = reset@{ slot ->
                        if (sim2Unavailable(slot + 1)) return@reset
                        scope.launch {
                            modemLock.withLock {
                                val sim = slot + 1
                                var newState: SimState? = null
                                var errorMsg: String? = null
                                var firstError: DaemonError? = null

                                withContext(Dispatchers.IO) {
                                    try {
                                        val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (!simResp.ok) {
                                            errorMsg = simResp.error?.message ?: "Failed to select SIM $sim"
                                            return@withContext
                                        }

                                        val resp = daemonManager.reset()
                                        val parsed = JsonStateParser.parseResponse(resp)
                                        if (!parsed.ok) {
                                            firstError = parsed.error
                                        }
                                        newState = parsed.simState
                                    } catch (e: Exception) {
                                        errorMsg = "Reset failed: ${e.message}"
                                        AppLog.e(TAG, "Reset SIM $sim: error", e)
                                    }
                                }
                                // C4: Back on Main dispatcher - update Compose state
                                if (newState != null) {
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1 = newState) ?: ModemState(sim1 = newState)
                                    else
                                        modemState?.copy(sim2 = newState) ?: ModemState(sim2 = newState)
                                }
                                if (slot == 0) refreshKey0++ else refreshKey1++
                                snackbarIsError = errorMsg != null || firstError != null
                                snackbarMessage = if (errorMsg != null) errorMsg
                                    else if (firstError != null) firstError.message
                                    else "Reset to hardware defaults for SIM $sim"
                                if (errorMsg == null && firstError == null) {
                                    AppLog.i(TAG, "Reset SIM $sim: success")
                                }
                            }
                        }
                    },
                    onDetach = {
                        scope.launch {
                            modemLock.withLock {
                                var errorMsg: String? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        val resp = JsonStateParser.parseResponse(daemonManager.detach())
                                        if (!resp.ok) {
                                            errorMsg = resp.error?.message ?: "Detach failed"
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = "Detach failed: ${e.message}"
                                        AppLog.e(TAG, "Detach: error", e)
                                    }
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: "Detached from network"
                            }
                        }
                    },
                    onAttach = {
                        scope.launch {
                            modemLock.withLock {
                                var errorMsg: String? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        val resp = JsonStateParser.parseResponse(daemonManager.attach())
                                        if (!resp.ok) {
                                            errorMsg = resp.error?.message ?: "Attach failed"
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = "Attach failed: ${e.message}"
                                        AppLog.e(TAG, "Attach: error", e)
                                    }
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: "Attached to network"
                            }
                        }
                    },
                    onReattach = {
                        scope.launch {
                            modemLock.withLock {
                                var errorMsg: String? = null
                                var ok = false
                                var recovered = false
                                snackbarIsError = false
                                snackbarMessage = "Re-attaching to network…"
                                withContext(Dispatchers.IO) {
                                    try {
                                        val resp = JsonStateParser.parseResponse(daemonManager.reattach())
                                        if (resp.ok) {
                                            ok = true
                                        } else {
                                            val msg = resp.error?.message
                                            if (msg?.contains("network may be left detached") == true) {
                                                val retryResp =
                                                    JsonStateParser.parseResponse(daemonManager.attach())
                                                if (retryResp.ok) {
                                                    recovered = true
                                                } else {
                                                    errorMsg = "Re-attach failed; network may be left detached" +
                                                        (retryResp.error?.message?.let { ": $it" } ?: "")
                                                }
                                            } else {
                                                errorMsg = msg ?: "Re-attach failed"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = "Re-attach failed: ${e.message}"
                                        AppLog.e(TAG, "Re-attach: error", e)
                                    }
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = when {
                                    errorMsg != null -> errorMsg
                                    recovered -> "Re-attach recovered: attach succeeded"
                                    ok -> "Re-attach complete"
                                    else -> "Re-attach complete"
                                }
                            }
                        }
                    },
                    refreshingSlots = refreshingSlots,
                    onRefresh = refresh@{ slot ->
                        recheckSim2()
                        if (sim2Unavailable(slot + 1)) return@refresh
                        scope.launch {
                            refreshingSlots = refreshingSlots + slot
                            val sim = slot + 1
                            try {
                                modemLock.withLock {
                                    var newState: SimState? = null
                                    var errorMsg: String? = null
                                    var success = false

                                    withContext(Dispatchers.IO) {
                                        try {
                                            // I11: Try simSet first, fall back to refresh
                                            var resp = daemonManager.simSet(sim)
                                            var parsed = JsonStateParser.parseResponse(resp)
                                            if (!parsed.ok) {
                                                resp = daemonManager.refresh()
                                                parsed = JsonStateParser.parseResponse(resp)
                                            }
                                            if (!parsed.ok) {
                                                errorMsg = parsed.error?.message ?: "Refresh failed"
                                                return@withContext
                                            }
                                            // The refresh fallback re-reads whichever SIM the
                                            // daemon is bound to; never show that under the
                                            // wrong SIM's tab.
                                            if (parsed.sim != sim) {
                                                errorMsg = "Refresh failed: could not select SIM $sim"
                                                return@withContext
                                            }
                                            newState = parsed.simState
                                            success = true
                                        } catch (e: Exception) {
                                            errorMsg = "Refresh failed: ${e.message}"
                                            AppLog.e(TAG, "Refresh SIM $sim: error", e)
                                        }
                                    }
                                    // C4/I9: Back on Main dispatcher - update Compose state
                                    if (success && newState != null) {
                                        modemState = if (slot == 0)
                                            modemState?.copy(sim1 = newState) ?: ModemState(sim1 = newState)
                                        else
                                            modemState?.copy(sim2 = newState) ?: ModemState(sim2 = newState)
                                        if (slot == 0) refreshKey0++ else refreshKey1++
                                        AppLog.i(TAG, "Refresh SIM $sim: success")
                                    } else {
                                        snackbarIsError = true
                                        snackbarMessage = errorMsg ?: "Refresh failed"
                                        AppLog.i(TAG, "Refresh SIM $sim: failed")
                                    }
                                }
                            } finally {
                                refreshingSlots = refreshingSlots - slot
                            }
                        }
                    },
                    refreshKey0 = refreshKey0,
                    refreshKey1 = refreshKey1,
                    modemState = modemState,
                    isLoading = isLoading,
                    showRootDeniedDialog = showRootDenied,
                    onRootRetry = {
                        showRootDenied = false
                        isLoading = true
                        daemonManager.retry()
                    },
                    onDismissRootDialog = { showRootDenied = false },
                    showErrorDialog = showErrorDialog,
                    errorDialogTitle = errorTitle,
                    errorDialogMessage = errorMessage,
                    onDismissErrorDialog = { showErrorDialog = false },
                    snackbarHostState = snackbarHostState,
                    snackbarMessage = snackbarMessage,
                    snackbarIsError = snackbarIsError,
                    onSnackbarShown = { snackbarMessage = null },
                    navbarHidden = navbarHidden,
                    onNavbarHiddenToggle = { hidden ->
                        navbarHidden = hidden
                        scope.launch { BandPreferences.setNavbarHidden(bandDataStore, hidden) }
                    },
                    debugEnabled = debugEnabled,
                    onDebugToggle = {
                        val newVal = !debugEnabled
                        debugEnabled = newVal
                        AppLog.debugEnabled = newVal
                        scope.launch {
                            BandPreferences.setDebugLogging(bandDataStore, newVal)
                            withContext(Dispatchers.IO) {
                                try { daemonManager.verboseSet(newVal) } catch (_: Exception) {}
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        // Closing the app ends the poller, so replay's background service too.
        if (isFinishing) ReplayService.stop(applicationContext)
        super.onDestroy()
    }
}
