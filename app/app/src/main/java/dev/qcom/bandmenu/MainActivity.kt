package dev.qcom.bandmenu

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.preferencesDataStore
import dev.qcom.bandmenu.ui.MainScreen
import dev.qcom.bandmenu.ui.CellLockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    }

    private val daemonManager by lazy { DaemonManager(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Intercept back gesture: stop the daemon synchronously before
        // letting the activity finish. This guarantees the daemon is killed
        // even though onDestroy is not reliably called by the system.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Thread {
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
                val modemLock = remember { Mutex() }

                var cellLockResult by remember { mutableStateOf<CellLockResult?>(null) }
                var cellLockRefreshing by remember { mutableStateOf(false) }
                var cellLockRefreshKey by remember { mutableIntStateOf(0) }
                var cellLockSelectedSim by remember { mutableIntStateOf(0) }
                var nrIndependentSupported by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(Unit) {
                    BandPreferences.getDebugLogging(bandDataStore).collectLatest { enabled ->
                        debugEnabled = enabled
                        AppLog.debugEnabled = enabled
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

                LaunchedEffect(daemonManager.isReady.value) {
                    if (daemonManager.isReady.value) {
                        scope.launch {
                            isLoading = true
                            var initModemState: ModemState? = null
                            var initErrorTitle: String? = null
                            var initError: String? = null
                            var initNrIndependentSupported: Boolean? = null
                            withContext(Dispatchers.IO) {
                                try {
                                    // C5: Explicitly select SIM 1 before query
                                    val simSet1Parsed = JsonStateParser.parseResponse(daemonManager.simSet(1))
                                    if (!simSet1Parsed.ok) {
                                        initErrorTitle = "Initialization Failed"
                                        initError = "Failed to select SIM 1: ${simSet1Parsed.error?.message ?: "unknown"}"
                                        return@withContext
                                    }

                                    // I8: Check ok on each response
                                    val sim1Resp = daemonManager.query()
                                    val sim1Parsed = JsonStateParser.parseResponse(sim1Resp)
                                    if (!sim1Parsed.ok) {
                                        initErrorTitle = "Initialization Failed"
                                        initError = "Failed to query SIM 1: ${sim1Parsed.error?.message ?: "unknown"}"
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

                                    val sim2Resp = daemonManager.simSet(2)
                                    val sim2Parsed = JsonStateParser.parseResponse(sim2Resp)
                                    if (!sim2Parsed.ok) {
                                        initErrorTitle = "Initialization Failed"
                                        initError = "Failed to select SIM 2: ${sim2Parsed.error?.message ?: "unknown"}"
                                        return@withContext
                                    }
                                    val sim2State = sim2Parsed.simState ?: SimState()
                                    val sim2CellLock = sim2Parsed.cellLockState ?: CellLockState()
                                    val sim2PlmnLock = sim2Parsed.plmnLockState ?: PlmnLockState()

                                    // Switch back to SIM 1 as default
                                    daemonManager.simSet(1)

                                    initModemState = ModemState(
                                        sim1State, sim2State, hardware, true,
                                        sim1CellLock, sim2CellLock,
                                        sim1PlmnLock, sim2PlmnLock,
                                        initNrIndependentSupported ?: nrIndependentSupported
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

                MainScreen(
                    onCellLockSimSwitch = { slot ->
                        cellLockSelectedSim = slot
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
                    onCellLockApply = { sim, fieldIndex, input ->
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
                    onCellLockClearAll = { sim ->
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
                    onCellLockClear5G = { sim ->
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
                    onCellLockClear4G = { sim ->
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
                    onCellLockClearPlmn = { sim ->
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
                    onCellLockRefresh = {
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
                    onApply = { slot, state ->
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
                    onReset = { slot ->
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
                    refreshingSlots = refreshingSlots,
                    onRefresh = { slot ->
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

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            daemonManager.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        daemonManager.stop()
    }
}
