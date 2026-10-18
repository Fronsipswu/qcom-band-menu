package dev.qcom.bandmenu

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.qcom.bandmenu.network.DiagClient
import dev.qcom.bandmenu.network.NetworkMonitor
import dev.qcom.bandmenu.network.ReplayRecorder
import dev.qcom.bandmenu.network.SimSlotStatus
import dev.qcom.bandmenu.ui.CellLockResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex

/**
 * Owns the [DaemonManager] and loaded [ModemState] across activity recreation
 * (configuration changes), so the daemon, its socket, and the queried modem
 * state survive instead of being reset and re-initialized.
 * Cleared only when the activity really finishes.
 */
class DaemonHolder(val daemonManager: DaemonManager, diagClient: DiagClient, replayDir: File) : ViewModel() {
    val scope: CoroutineScope get() = viewModelScope

    /** Serializes multi-request modem sequences (SIM select + commands). Lives
     *  here, not in the composition, so an operation still running in a
     *  destroyed activity and the new activity's init can never interleave. */
    val modemLock = Mutex()

    /** Network page poller; here so a configuration change keeps one poller. */
    val networkMonitor = NetworkMonitor(daemonManager, diagClient, viewModelScope, ReplayRecorder(replayDir))

    val modemState = mutableStateOf<ModemState?>(null)
    val isLoading = mutableStateOf(true)
    val showRootDenied = mutableStateOf(false)
    val showErrorDialog = mutableStateOf(false)
    val errorTitle = mutableStateOf("")
    val errorMessage = mutableStateOf("")
    val snackbarMessage = mutableStateOf<String?>(null)
    val snackbarIsError = mutableStateOf(false)
    val refreshingSlots = mutableStateOf(emptySet<Int>())
    val refreshKey0 = mutableIntStateOf(0)
    val refreshKey1 = mutableIntStateOf(0)
    val cellLockResult = mutableStateOf<CellLockResult?>(null)
    val cellLockRefreshing = mutableStateOf(false)
    val cellLockRefreshKey = mutableIntStateOf(0)
    val cellLockSelectedSim = mutableIntStateOf(0)
    val nrIndependentSupported = mutableStateOf<Boolean?>(null)
    val initGeneration = mutableIntStateOf(0)
    val sim2BindRetries = mutableIntStateOf(0)
    val simLabels = mutableStateOf(listOf("SIM 1", "SIM 2"))
    var loadedSimLabelsKey: Triple<SimSlotStatus, SimSlotStatus, Boolean>? = null
    var initInFlight = false

    override fun onCleared() {
        // Off the main thread: stopBlocking() waits for any in-flight request,
        // sends shutdown and runs pkill. A no-op if Back already stopped it.
        // The DIAG helper is stopped too (it would also exit by itself once
        // it's no longer polled).
        Thread { networkMonitor.stopDiagBlocking(); daemonManager.stopBlocking() }.start()
    }
}
