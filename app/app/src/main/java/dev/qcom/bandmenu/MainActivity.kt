package dev.qcom.bandmenu

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.qcom.bandmenu.ui.MainScreen
import dev.qcom.bandmenu.network.DiagClient
import dev.qcom.bandmenu.network.SimSlotStatus
import dev.qcom.bandmenu.ui.CellLockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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

    // The language chosen on the Info page, applied before any screen is drawn.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
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

        // Read before the first frame: the pager's first page depends on it
        // (one small preferences read).
        val startOnNetwork = kotlinx.coroutines.runBlocking {
            BandPreferences.getNetworkAtStart(bandDataStore).first()
        }
        // The display scale, read before the first frame for the same reason.
        val savedScale = DisplayScale.saved(this)

        setContent {
            MiuixTheme(colors = darkColorScheme()) {
                // One density for the whole app, so the Info page's display
                // scale grows or shrinks every dp and sp together.
                var displayScale by remember { mutableStateOf(savedScale) }
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(baseDensity.density * displayScale.factor, baseDensity.fontScale)
                ) {
                val snackbarHostState = remember { SnackbarHostState() }

                var modemState by daemonHolder.modemState
                var isLoading by daemonHolder.isLoading
                var showRootDenied by daemonHolder.showRootDenied
                var showErrorDialog by daemonHolder.showErrorDialog
                var errorTitle by daemonHolder.errorTitle
                var errorMessage by daemonHolder.errorMessage
                var snackbarMessage by daemonHolder.snackbarMessage
                var snackbarIsError by daemonHolder.snackbarIsError
                var snackbarIsWarning by daemonHolder.snackbarIsWarning
                var refreshingSlots by daemonHolder.refreshingSlots
                var refreshKey0 by daemonHolder.refreshKey0
                var refreshKey1 by daemonHolder.refreshKey1
                var debugEnabled by remember { mutableStateOf(false) }
                val scope = daemonHolder.scope
                val modemLock = daemonHolder.modemLock

                var cellLockResult by daemonHolder.cellLockResult
                var cellLockRefreshing by daemonHolder.cellLockRefreshing
                var cellLockRefreshKey by daemonHolder.cellLockRefreshKey
                var cellLockSelectedSim by daemonHolder.cellLockSelectedSim
                var nrIndependentSupported by daemonHolder.nrIndependentSupported
                var nrModeSettable by daemonHolder.nrModeSettable
                var bandFilter by remember { mutableStateOf(BandFilterState()) }
                var quickSelectHidden by remember { mutableStateOf(false) }
                var nrCombined by remember { mutableStateOf(false) }
                var usageSettingHidden by remember { mutableStateOf(false) }
                var nrModeHidden by remember { mutableStateOf(false) }
                var servingHidden by remember { mutableStateOf(false) }
                var thputHidden by remember { mutableStateOf(false) }
                var uplinkHidden by remember { mutableStateOf(false) }
                var keepScreenOn by remember { mutableStateOf(false) }
                var endcSeparate by remember { mutableStateOf(true) }
                var gnbBitsSim1 by remember { mutableIntStateOf(22) }
                var gnbBitsSim2 by remember { mutableIntStateOf(22) }
                var navbarHidden by remember { mutableStateOf(false) }
                var networkAtStart by remember { mutableStateOf(startOnNetwork) }

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
                    BandPreferences.getNrCombined(bandDataStore).collectLatest { combined ->
                        nrCombined = combined
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
                    BandPreferences.getUplinkHidden(bandDataStore).collectLatest { hidden ->
                        uplinkHidden = hidden
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getKeepScreenOn(bandDataStore).collectLatest { keepScreenOn = it }
                }
                // The window flag only holds while the app is visible.
                LaunchedEffect(keepScreenOn) {
                    if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getEndcSeparate(bandDataStore).collectLatest { separate ->
                        endcSeparate = separate
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getGnbBits(bandDataStore, 1).collectLatest { bits ->
                        gnbBitsSim1 = bits
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getGnbBits(bandDataStore, 2).collectLatest { bits ->
                        gnbBitsSim2 = bits
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getNavbarHidden(bandDataStore).collectLatest { hidden ->
                        navbarHidden = hidden
                    }
                }

                LaunchedEffect(Unit) {
                    BandPreferences.getNetworkAtStart(bandDataStore).collectLatest { enabled ->
                        networkAtStart = enabled
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
                                snackbarMessage = getString(R.string.msg_daemon_restored)
                            } else {
                                snackbarIsError = true
                                snackbarMessage = getString(R.string.msg_daemon_lost)
                            }
                        }
                    }
                    daemonManager.start(
                        onDenied = {
                            showRootDenied = true
                            isLoading = false
                        },
                        onLaunchFailed = { msg ->
                            errorTitle = getString(R.string.msg_daemon_launch_failed)
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
                var initGeneration by daemonHolder.initGeneration
                var sim2BindRetries by daemonHolder.sim2BindRetries
                LaunchedEffect(daemonManager.isReady.value, initGeneration) {
                    if (daemonManager.isReady.value && modemState == null && !daemonHolder.initInFlight) {
                        daemonHolder.initInFlight = true
                        scope.launch {
                            try {
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
                                                initErrorTitle = getString(R.string.msg_init_failed)
                                                initError = getString(R.string.msg_select_sim1_failed, sim1Parsed.error?.message ?: "unknown")
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
                                            initErrorTitle = getString(R.string.msg_init_failed)
                                            initError = getString(R.string.msg_query_failed, e.message)
                                        }
                                    }
                                    // C4: Back on Main dispatcher - update Compose state
                                    if (initModemState != null) {
                                        modemState = initModemState
                                        if (initModemState.sim2Available) sim2BindRetries = 0
                                    }
                                    if (initNrIndependentSupported != null && nrIndependentSupported == null) {
                                        nrIndependentSupported = initNrIndependentSupported
                                    }
                                    if (initError != null) {
                                        errorTitle = initErrorTitle ?: getString(R.string.msg_init_failed)
                                        errorMessage = initError
                                        showErrorDialog = true
                                    }
                                    isLoading = false
                                }
                            } finally {
                                daemonHolder.initInFlight = false
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
                        if (status == SimSlotStatus.UNKNOWN) return@launch
                        if (status != current.sim2Status) {
                            sim2BindRetries = 0
                        }
                        // Unchanged: nothing to do, except bounded retries when SIM 2
                        // is ACTIVE in UIM but a transient startup bind failure left it
                        // unavailable (capped so a single-SIM / DSDS-off modem doesn't
                        // re-init forever).
                        val needsBindRetry = status == SimSlotStatus.ACTIVE && !current.sim2Available && sim2BindRetries < 3
                        if (status == current.sim2Status && !needsBindRetry) return@launch
                        if ((status == SimSlotStatus.ACTIVE) != current.sim2Available) {
                            if (status == current.sim2Status) sim2BindRetries++
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
                var simLabels by daemonHolder.simLabels
                val labelKey = modemState?.let {
                    Triple(it.sim1Status, it.sim2Status, it.sim2Available)
                }
                LaunchedEffect(labelKey, modemState?.hardware?.nr) {
                    val state = modemState ?: return@LaunchedEffect
                    val monitor = daemonHolder.networkMonitor
                    monitor.nrHardwareBands = state.hardware.nr
                    if (labelKey == daemonHolder.loadedSimLabelsKey) return@LaunchedEffect
                    simLabels = withContext(Dispatchers.IO) {
                        simTabLabels(listOf(
                            when (state.sim1Status) {
                                SimSlotStatus.DISABLED -> getString(R.string.sim_off)
                                SimSlotStatus.NO_SIM -> getString(R.string.sim_no_sim)
                                else -> monitor.operatorNameForLabel(1)
                            },
                            if (state.sim2Available) monitor.operatorNameForLabel(2)
                            else when (state.sim2Status) {
                                SimSlotStatus.DISABLED -> getString(R.string.sim_off)
                                SimSlotStatus.NO_SIM -> getString(R.string.sim_no_sim)
                                else -> null
                            }
                        ))
                    }
                    daemonHolder.loadedSimLabelsKey = labelKey
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
                    uplinkHidden = uplinkHidden,
                    onUplinkHiddenToggle = { hidden ->
                        uplinkHidden = hidden
                        scope.launch { BandPreferences.setUplinkHidden(bandDataStore, hidden) }
                    },
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOnToggle = { on ->
                        keepScreenOn = on
                        scope.launch { BandPreferences.setKeepScreenOn(bandDataStore, on) }
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
                    gnbBitsSim1 = gnbBitsSim1,
                    gnbBitsSim2 = gnbBitsSim2,
                    onGnbBitsCycle = { sim ->
                        val cur = if (sim == 2) gnbBitsSim2 else gnbBitsSim1
                        val next = if (cur >= 32) 22 else cur + 2
                        if (sim == 2) gnbBitsSim2 = next else gnbBitsSim1 = next
                        scope.launch { BandPreferences.setGnbBits(bandDataStore, sim, next) }
                    },
                    onDiagToggle = { on ->
                        daemonHolder.networkMonitor.setDiagEnabled(on)
                        scope.launch { BandPreferences.setDiagEnabled(bandDataStore, on) }
                    },
                    onReplayToggle = { on ->
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        scope.launch { BandPreferences.setReplayEnabled(bandDataStore, on) }
                    },
                    onCellLockSimSwitch = simSwitch@{ slot ->
                        cellLockSelectedSim = slot
                        if (slot == 1 && modemState?.sim2Available == false) return@simSwitch
                        if (slot == 0 && modemState?.simOffset == 1) return@simSwitch
                        scope.launch {
                            modemLock.withLock {
                                val sim = slot + 1
                                var newCellLock: CellLockState? = null
                                var newPlmnLock: PlmnLockState? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        val resp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (resp.ok && resp.sim == sim) {
                                            newCellLock = resp.cellLockState
                                            newPlmnLock = resp.plmnLockState
                                        }
                                    } catch (e: Exception) {
                                        AppLog.e(TAG, "CellLock SIM switch: error", e)
                                    }
                                }
                                if (newCellLock != null) {
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock)
                                }
                                if (newPlmnLock != null) {
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1PlmnLock = newPlmnLock)
                                    else
                                        modemState?.copy(sim2PlmnLock = newPlmnLock)
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
                                val parts = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

                                val action: (() -> org.json.JSONObject)? = try {
                                    when (fieldIndex) {
                                        0 -> {
                                            if (parts.size < 2) { errorMsg = getString(R.string.msg_need_arfcn_scs); null }
                                            else {
                                                val arfcn = parts[0].toInt()
                                                val scs = parts[1].toInt()
                                                ({ daemonManager.nrCellLockArfcnSet(arfcn, scs) })
                                            }
                                        }
                                        1 -> {
                                            if (parts.size < 4) { errorMsg = getString(R.string.msg_need_arfcn_pci_scs_band); null }
                                            else {
                                                val arfcn = parts[0].toInt()
                                                val pci = parts[1].toInt()
                                                val scs = parts[2].toInt()
                                                val band = parts[3].toInt()
                                                ({ daemonManager.nrCellLockPciSet(arfcn, pci, scs, band) })
                                            }
                                        }
                                        2 -> {
                                            if (parts.size < 4) { errorMsg = getString(R.string.msg_need_arfcn_scs_band_pci); null }
                                            else {
                                                val arfcn = parts[0].toInt()
                                                val scs = parts[1].toInt()
                                                val band = parts[2].toInt()
                                                val pciList = parts.drop(3).map { it.toInt() }
                                                ({ daemonManager.nrCellLockMultiPciSet(arfcn, scs, band, pciList) })
                                            }
                                        }
                                        3 -> {
                                            if (parts.size < 2) { errorMsg = getString(R.string.msg_need_id_bits_gnb); null }
                                            else {
                                                val idBits = parts[0].toIntOrNull()
                                                val gnbIds = parts.drop(1).map { it.toLongOrNull() }
                                                val err = BandValidator.validateGnbAllowlist(idBits, gnbIds)
                                                if (err != null) { errorMsg = err; null }
                                                else {
                                                    val validIds = gnbIds.filterNotNull()
                                                    ({ daemonManager.nrCellLockGnbSet(idBits!!, validIds) })
                                                }
                                            }
                                        }
                                        4 -> {
                                            if (parts.size < 2) { errorMsg = getString(R.string.msg_need_earfcn_pci); null }
                                            else {
                                                val earfcn = parts[0].toInt()
                                                val pci = parts[1].toInt()
                                                ({ daemonManager.lteCellLockSet(earfcn, pci) })
                                            }
                                        }
                                        6 -> {
                                            if (parts.size < 2) { errorMsg = getString(R.string.msg_need_earfcn_pci_list); null }
                                            else {
                                                val earfcn = parts[0].toInt()
                                                val pciList = parts.drop(1).map { it.toInt() }
                                                if (pciList.size > 64) { errorMsg = getString(R.string.msg_max_64_pci); null }
                                                else if (pciList.any { it < 0 || it > 503 }) { errorMsg = getString(R.string.msg_pci_range); null }
                                                else ({ daemonManager.lteCellLockMultiPciSet(earfcn, pciList) })
                                            }
                                        }
                                        5 -> {
                                            val plmn = BandValidator.plmnParts(input)
                                            if (plmn.size != 2) { errorMsg = getString(R.string.msg_need_mcc_mnc); null }
                                            // A longer MNC with leading zeros would pass as a number.
                                            else if (plmn[1].length > 3) { errorMsg = getString(R.string.msg_mnc_digits); null }
                                            else {
                                                val mcc = plmn[0].toIntOrNull()
                                                val mnc = plmn[1].toIntOrNull()
                                                if (mcc == null || mcc < 0 || mcc > 999 || mnc == null || mnc < 0 || mnc > 999) {
                                                    errorMsg = getString(R.string.msg_mcc_mnc_range); null
                                                } else ({ daemonManager.plmnLockSet(mcc, mnc) })
                                            }
                                        }
                                        else -> { errorMsg = getString(R.string.msg_unknown_field); null }
                                    }
                                } catch (e: Exception) {
                                    errorMsg = getString(R.string.msg_invalid_number, e.message)
                                    null
                                }

                                if (action != null) {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                            if (!simResp.ok) {
                                                errorMsg = simResp.error?.message ?: getString(R.string.msg_select_sim_failed, sim)
                                                return@withContext
                                            }

                                            val resp = JsonStateParser.parseResponse(action())
                                            success = resp.ok
                                            if (!resp.ok) {
                                                errorMsg = resp.error?.message ?: getString(R.string.lock_rejected)
                                            } else if (resp.sim == sim) {
                                                if (fieldIndex == 5) {
                                                    newPlmnLock = resp.plmnLockState
                                                } else {
                                                    newCellLock = resp.cellLockState
                                                }
                                            }
                                        } catch (e: Exception) {
                                            errorMsg = getString(R.string.msg_apply_failed, e.message)
                                            AppLog.e(TAG, "CellLock apply: error", e)
                                        }
                                    }
                                }

                                val typeName = when (fieldIndex) {
                                    0 -> "NR-ARFCN"; 1 -> "PCI"; 2 -> "MultiPCI"; 3 -> "gNB"; 4 -> "LTE PCI"; 6 -> "LTE MultiPCI"; 5 -> "PLMN"; else -> "Unknown"
                                }
                                cellLockResult = CellLockResult(sim, fieldIndex, typeName, success, errorMsg)

                                if (newCellLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock)
                                }

                                if (newPlmnLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1PlmnLock = newPlmnLock)
                                    else
                                        modemState?.copy(sim2PlmnLock = newPlmnLock)
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
                                            errorMsg = simResp.error?.message ?: getString(R.string.msg_select_sim_failed, sim)
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
                                        errorMsg = getString(R.string.msg_clear_all_failed, e.message)
                                        AppLog.e(TAG, "CellLock clear all: error", e)
                                    }
                                }
                                if (newCellLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock)
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: getString(R.string.msg_cleared_all, sim)
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
                                            errorMsg = simResp.error?.message ?: getString(R.string.msg_select_sim_failed, sim)
                                            return@withContext
                                        }
                                        try {
                                            val resp = JsonStateParser.parseResponse(daemonManager.nrCellLockClear())
                                            if (!resp.ok) errorMsg = resp.error?.message ?: getString(R.string.lock_rejected)
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
                                        modemState?.copy(sim1CellLock = newCellLock)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock)
                                }
                                snackbarIsError = errorMsg != null || ioError
                                snackbarMessage = when {
                                    errorMsg != null -> errorMsg
                                    ioError -> getString(R.string.msg_clear_5g_conn_failed)
                                    else -> getString(R.string.msg_cleared_5g, sim)
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
                                            errorMsg = simResp.error?.message ?: getString(R.string.msg_select_sim_failed, sim)
                                            return@withContext
                                        }
                                        val resp = JsonStateParser.parseResponse(daemonManager.lteCellLockClear())
                                        if (!resp.ok) errorMsg = resp.error?.message
                                        newCellLock = resp.cellLockState
                                    } catch (e: Exception) {
                                        errorMsg = getString(R.string.msg_clear_4g_failed, e.message)
                                        AppLog.e(TAG, "CellLock clear 4G: error", e)
                                    }
                                }
                                if (newCellLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock)
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: getString(R.string.msg_cleared_4g, sim)
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
                                            errorMsg = simResp.error?.message ?: getString(R.string.msg_select_sim_failed, sim)
                                            return@withContext
                                        }
                                        val resp = JsonStateParser.parseResponse(daemonManager.plmnLockClear())
                                        if (!resp.ok) errorMsg = resp.error?.message
                                        delay(200)
                                        val resp2 = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        newPlmnLock = resp2.plmnLockState ?: resp.plmnLockState
                                    } catch (e: Exception) {
                                        errorMsg = getString(R.string.msg_clear_plmn_failed, e.message)
                                        AppLog.e(TAG, "CellLock clear PLMN: error", e)
                                    }
                                }
                                if (newPlmnLock != null) {
                                    val slot = sim - 1
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1PlmnLock = newPlmnLock)
                                    else
                                        modemState?.copy(sim2PlmnLock = newPlmnLock)
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: getString(R.string.msg_cleared_plmn, sim)
                            }
                        }
                    },
                    cellLockResult = cellLockResult,
                    onCellLockResultConsumed = { cellLockResult = null },
                    cellLockRefreshing = cellLockRefreshing,
                    onCellLockRefresh = cellRefresh@{
                        val slot = cellLockSelectedSim
                        val sim = slot + 1
                        recheckSim2()
                        if (sim2Unavailable(sim)) return@cellRefresh
                        scope.launch {
                            cellLockRefreshing = true
                            modemLock.withLock {
                                var newCellLock: CellLockState? = null
                                var newPlmnLock: PlmnLockState? = null
                                var errorMsg: String? = null
                                withContext(Dispatchers.IO) {
                                    try {
                                        // Switch to the captured SIM first to ensure we read
                                        // the requested SIM's cell lock state and assign the
                                        // response back to that same SIM even if the user swipes.
                                        val resp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        if (resp.ok && resp.sim == sim) {
                                            newCellLock = resp.cellLockState
                                            newPlmnLock = resp.plmnLockState
                                        } else {
                                            errorMsg = resp.error?.message ?: getString(R.string.msg_refresh_failed)
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = getString(R.string.msg_refresh_failed_error, e.message)
                                        AppLog.e(TAG, "CellLock refresh: error", e)
                                    }
                                }
                                if (newCellLock != null) {
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1CellLock = newCellLock)
                                    else
                                        modemState?.copy(sim2CellLock = newCellLock)
                                    cellLockRefreshKey++
                                }
                                if (newPlmnLock != null) {
                                    modemState = if (slot == 0)
                                        modemState?.copy(sim1PlmnLock = newPlmnLock)
                                    else
                                        modemState?.copy(sim2PlmnLock = newPlmnLock)
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
                    nrModeSettable = nrModeSettable,
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
                    nrCombined = nrCombined,
                    onToggleNrCombined = {
                        val combined = !nrCombined
                        nrCombined = combined
                        scope.launch { BandPreferences.setNrCombined(bandDataStore, combined) }
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
                                var nrModeRejected = false

                                withContext(Dispatchers.IO) {
                                    try {
                                        // I3: Validate bands against hardware before sending
                                        val hw = modemState?.hardware
                                        val validatedState = if (hw != null)
                                            BandValidator.validateSimState(state, hw) else state

                                        val simResp = JsonStateParser.parseResponse(daemonManager.simSet(sim))
                                        // C2: If simSet fails, abort early
                                        if (!simResp.ok) {
                                            errorMsg = simResp.error?.message ?: getString(R.string.msg_select_sim_failed, sim)
                                            return@withContext
                                        }

                                        val hasNrHardware = hw != null && hw.nr.isNotEmpty()
                                        val useIndependentLock = nrIndependentSupported == true
                                        // The NR mode only when it changes, so a modem that
                                        // can't set it (the X55) doesn't fail every Apply.
                                        val modeCmd = if (hasNrHardware && nrModeSettable &&
                                            validatedState.nrMode != NrMode.UNKNOWN &&
                                            validatedState.nrMode != simResp.simState?.nrMode
                                        ) JsonRequestBuilder.modeSet(validatedState.nrMode) else null
                                        // The RAT only when it changes, and before the bands: on some
                                        // phones (Galaxy S25 Edge) a RAT write resets the band
                                        // preferences to all bands, so the band writes must follow it.
                                        val currentRats = simResp.simState?.ratMask?.takeIf { it.isNotEmpty() }
                                        val ratCmd = if (validatedState.ratMask != currentRats)
                                            JsonRequestBuilder.ratSet(validatedState.ratMask) else null
                                        val commands = buildList {
                                            ratCmd?.let { add(it) }
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
                                                modeCmd?.let { add(it) }
                                            }
                                            if (validatedState.usageMode != UsageMode.UNKNOWN) {
                                                add(JsonRequestBuilder.usagePrefSet(validatedState.usageMode))
                                            }
                                        }

                                        // C2: Check every response's ok field, collect first error
                                        var lastResp: DaemonResponse = simResp
                                        for (cmd in commands) {
                                            lastResp = JsonStateParser.parseResponse(daemonManager.sendRequest(cmd))
                                            // A rejected NR mode change greys the NR mode row
                                            // out instead of failing the Apply.
                                            if (!lastResp.ok && cmd === modeCmd) {
                                                nrModeRejected = true
                                            } else if (!lastResp.ok && firstError == null) {
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
                                        errorMsg = getString(R.string.msg_apply_failed, e.message)
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
                                if (nrModeRejected) {
                                    nrModeSettable = false
                                    AppLog.i(TAG, "Apply SIM $sim: NR mode change rejected, NR mode read-only")
                                }
                                snackbarIsError = errorMsg != null || firstError != null
                                // Bands applied, only the NR mode refused: a warning, not an error.
                                snackbarIsWarning = !snackbarIsError && nrModeRejected
                                snackbarMessage = if (errorMsg != null) errorMsg
                                    else if (firstError != null) {
                                        val msg = firstError.message
                                        val rejected = firstError.rejectedBands
                                        if (rejected != null && rejected.isNotEmpty())
                                            "$msg (rejected: $rejected)" else msg
                                    } else if (nrModeRejected) getString(R.string.bands_applied_nr_refused)
                                    else getString(R.string.bands_applied_sim, sim)
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
                                            errorMsg = simResp.error?.message ?: getString(R.string.msg_select_sim_failed, sim)
                                            return@withContext
                                        }

                                        val resp = daemonManager.reset()
                                        val parsed = JsonStateParser.parseResponse(resp)
                                        if (!parsed.ok) {
                                            firstError = parsed.error
                                        }
                                        newState = parsed.simState
                                    } catch (e: Exception) {
                                        errorMsg = getString(R.string.msg_reset_failed, e.message)
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
                                    else getString(R.string.msg_reset_defaults, sim)
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
                                            errorMsg = resp.error?.message ?: getString(R.string.msg_detach_failed)
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = getString(R.string.msg_detach_failed_error, e.message)
                                        AppLog.e(TAG, "Detach: error", e)
                                    }
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: getString(R.string.msg_detached)
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
                                            errorMsg = resp.error?.message ?: getString(R.string.msg_attach_failed)
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = getString(R.string.msg_attach_failed_error, e.message)
                                        AppLog.e(TAG, "Attach: error", e)
                                    }
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = errorMsg ?: getString(R.string.msg_attached)
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
                                snackbarMessage = getString(R.string.msg_reattaching)
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
                                                    errorMsg = getString(R.string.msg_reattach_left_detached) +
                                                        (retryResp.error?.message?.let { ": $it" } ?: "")
                                                }
                                            } else {
                                                errorMsg = msg ?: getString(R.string.msg_reattach_failed)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = getString(R.string.msg_reattach_failed_error, e.message)
                                        AppLog.e(TAG, "Re-attach: error", e)
                                    }
                                }
                                snackbarIsError = errorMsg != null
                                snackbarMessage = when {
                                    errorMsg != null -> errorMsg
                                    recovered -> getString(R.string.msg_reattach_recovered)
                                    ok -> getString(R.string.msg_reattach_complete)
                                    else -> getString(R.string.msg_reattach_complete)
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
                                                errorMsg = parsed.error?.message ?: getString(R.string.msg_refresh_failed)
                                                return@withContext
                                            }
                                            // The refresh fallback re-reads whichever SIM the
                                            // daemon is bound to; never show that under the
                                            // wrong SIM's tab.
                                            if (parsed.sim != sim) {
                                                errorMsg = getString(R.string.msg_refresh_no_sim, sim)
                                                return@withContext
                                            }
                                            newState = parsed.simState
                                            success = true
                                        } catch (e: Exception) {
                                            errorMsg = getString(R.string.msg_refresh_failed_error, e.message)
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
                                        snackbarMessage = errorMsg ?: getString(R.string.msg_refresh_failed)
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
                    snackbarIsWarning = snackbarIsWarning,
                    onSnackbarShown = { snackbarMessage = null; snackbarIsWarning = false },
                    navbarHidden = navbarHidden,
                    onNavbarHiddenToggle = { hidden ->
                        navbarHidden = hidden
                        scope.launch { BandPreferences.setNavbarHidden(bandDataStore, hidden) }
                    },
                    startOnNetwork = startOnNetwork,
                    networkAtStart = networkAtStart,
                    onNetworkAtStartToggle = { enabled ->
                        networkAtStart = enabled
                        scope.launch { BandPreferences.setNetworkAtStart(bandDataStore, enabled) }
                    },
                    displayScale = displayScale,
                    onDisplayScaleCycle = {
                        val next = displayScale.next()
                        displayScale = next
                        DisplayScale.save(this@MainActivity, next)
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
    }

    override fun onDestroy() {
        // Closing the app ends the poller, so replay's background service too.
        if (isFinishing) ReplayService.stop(applicationContext)
        super.onDestroy()
    }
}
