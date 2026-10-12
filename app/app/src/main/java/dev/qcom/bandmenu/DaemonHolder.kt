package dev.qcom.bandmenu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.qcom.bandmenu.network.DiagClient
import dev.qcom.bandmenu.network.NetworkMonitor
import dev.qcom.bandmenu.network.ReplayRecorder
import java.io.File
import kotlinx.coroutines.sync.Mutex

/**
 * Owns the [DaemonManager] across activity recreation (configuration changes),
 * so the daemon and its socket survive instead of being killed and relaunched.
 * Cleared only when the activity really finishes.
 */
class DaemonHolder(val daemonManager: DaemonManager, diagClient: DiagClient, replayDir: File) : ViewModel() {
    /** Serializes multi-request modem sequences (SIM select + commands). Lives
     *  here, not in the composition, so an operation still running in a
     *  destroyed activity and the new activity's init can never interleave. */
    val modemLock = Mutex()

    /** Network page poller; here so a configuration change keeps one poller. */
    val networkMonitor = NetworkMonitor(daemonManager, diagClient, viewModelScope, ReplayRecorder(replayDir))

    override fun onCleared() {
        // Off the main thread: stopBlocking() waits for any in-flight request,
        // sends shutdown and runs pkill. A no-op if Back already stopped it.
        // The DIAG helper is stopped too (it would also exit by itself once
        // it's no longer polled).
        Thread { networkMonitor.stopDiagBlocking(); daemonManager.stopBlocking() }.start()
    }
}
