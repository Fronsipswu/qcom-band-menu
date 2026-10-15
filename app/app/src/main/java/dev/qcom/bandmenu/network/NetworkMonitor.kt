package dev.qcom.bandmenu.network

import android.os.SystemClock
import dev.qcom.bandmenu.AppLog
import dev.qcom.bandmenu.DaemonManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Polls the daemon's read-only monitoring client for the Network page.
 *
 * Runs only while the page is visible (settled on it), the activity is
 * resumed, and monitoring isn't frozen; with replay on, always (frozen, the
 * rounds are recorded but not shown). One round at a time, then a 1 s
 * pause. Owned by DaemonHolder (a ViewModel), so a configuration change
 * keeps the same monitor and never starts a second poller.
 *
 * Monitoring uses the daemon's separate per-SIM NAS clients, so it never
 * changes the SIM that band/cell-lock commands target and needs no modemLock.
 */
class NetworkMonitor(
    private val daemon: DaemonManager,
    private val diag: DiagClient,
    private val scope: CoroutineScope,
    private val recorder: ReplayRecorder? = null
) {
    data class State(
        val sim: Int = 1,
        val snapshot: NetworkSnapshot? = null,
        val operatorName: String? = null,
        val error: String? = null,
        val frozen: Boolean = false,
        val refreshing: Boolean = false,
        /** Last good reading per SIM, so the SIM pager's neighbouring page
         *  shows that SIM's own data while swiping. Only [sim] is polled. */
        val lastBySim: Map<Int, Reading> = emptyMap(),
        /** DIAG on: each round also reads the helper's PDSCH stats (the
         *  setting persists). QMI keeps running either way. */
        val diagEnabled: Boolean = false,
        /** Why DIAG stopped or a round's DIAG read failed, if it did. */
        val diagError: String? = null,
        /** Replay on: polls and records every round, also in the background. */
        val replayEnabled: Boolean = false,
        /** Combined DL throughput of each SIM's recent DIAG rounds, oldest
         *  first (the throughput chart's live data). Kept per SIM so a page
         *  keeps its chart while the pager swipes to the other SIM. */
        val thputBySim: Map<Int, List<ThputPoint>> = emptyMap()
    )

    data class Reading(val snapshot: NetworkSnapshot, val operatorName: String?)

    /** NR bands the modem supports (from the band-lock hardware query). */
    @Volatile
    var nrHardwareBands: Set<Int> = emptySet()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var visible = false
    private var resumed = false
    private var loop: Job? = null
    private val roundLock = Mutex()
    /** Operator name cache per SIM (slow tier): the PLMN + mode it was read
     *  for, and the name. The modem's name depends on the RAT (e.g. 520-04 is
     *  "TRUE-H" on LTE but "TRUE5GX" on 5G), so a mode change re-reads it. */
    private val namedPlmn = arrayOfNulls<String>(2)
    private val cachedName = arrayOfNulls<String>(2)

    fun setVisible(v: Boolean) { visible = v; reschedule() }
    fun setResumed(v: Boolean) { resumed = v; reschedule() }

    /** Turns DIAG on (the next round starts the helper) or off (unsubscribes
     *  and stops it; the CA table goes back to QMI only). */
    fun setDiagEnabled(on: Boolean) {
        _state.update { it.copy(diagEnabled = on, diagError = null) }
        if (on) {
            if (visible && resumed) scope.launch { round() }
        } else {
            scope.launch(Dispatchers.IO) { roundLock.withLock { diag.stop() } }
            // Drop the DIAG rows at once instead of waiting for the next round.
            if (visible && resumed) scope.launch { round() }
        }
    }

    /** Stops the DIAG helper if it runs. Blocking; for app shutdown. */
    fun stopDiagBlocking() = diag.stop()

    /** Replay on starts a new recording; off deletes the recorded files
     *  and polling goes back to following the page. */
    fun setReplayEnabled(on: Boolean) {
        _state.update { it.copy(replayEnabled = on) }
        scope.launch(Dispatchers.IO) { recorder?.clear(keepRecording = on) }
        reschedule()
    }

    fun setFrozen(f: Boolean) {
        _state.update { it.copy(frozen = f) }
        reschedule()
    }

    fun selectSim(sim: Int) {
        if (sim == _state.value.sim) return
        // Drop the other SIM's data at once; never show SIM 1 under SIM 2. The
        // new SIM's own last reading (if any) shows until its round lands.
        _state.update { val last = it.lastBySim[sim]; it.copy(sim = sim, snapshot = last?.snapshot, operatorName = last?.operatorName, error = null) }
        if (visible && resumed) scope.launch { round(fresh = true) }
        reschedule()
    }

    /** One sample, also while frozen (pull-to-refresh); doesn't resume polling. */
    fun refreshOnce() {
        scope.launch {
            _state.update { it.copy(refreshing = true) }
            try { round(fresh = true) } finally { _state.update { it.copy(refreshing = false) } }
        }
    }

    private fun reschedule() {
        val run = (visible && resumed && !_state.value.frozen) || _state.value.replayEnabled
        if (run && loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    round()
                    delay(POLL_INTERVAL_MS)
                }
            }
        } else if (!run) {
            loop?.cancel()
            loop = null
        }
    }

    /** One round. [fresh]: show every value as read (pull-to-refresh, SIM switch). */
    private suspend fun round(fresh: Boolean = false) = roundLock.withLock {
        val sim = _state.value.sim
        val withDiag = _state.value.diagEnabled
        val result = withContext(Dispatchers.IO) { readRound(sim, fresh, withDiag) }
        // A SIM switch during the round makes its result stale. Frozen (replay
        // keeps polling), only pull-to-refresh and SIM switches are shown.
        if (_state.value.sim != sim) return@withLock
        // The chart's history follows the rounds, frozen or not.
        result.getOrNull()?.takeIf { it.mbps != null }?.let { r ->
            val t = r.snapshot.takenAtElapsedMs
            _state.update { s ->
                val point = ThputPoint(t, r.mbps!!, r.ulMbps ?: 0.0)
                val history = (s.thputBySim[sim].orEmpty() + point).dropWhile { t - it.t > THPUT_KEEP_MS }
                s.copy(thputBySim = s.thputBySim + (sim to history))
            }
        }
        if (_state.value.frozen && !fresh) return@withLock
        result.fold(
            onSuccess = { r ->
                _state.update {
                    // DIAG that couldn't start is switched off, with the reason;
                    // DIAG toggled off during the round keeps its new state.
                    val diagOn = it.diagEnabled && !(withDiag && r.diagFailed)
                    it.copy(snapshot = r.snapshot, operatorName = r.name, error = null,
                        lastBySim = it.lastBySim + (sim to Reading(r.snapshot, r.name)),
                        diagEnabled = diagOn, diagError = if (withDiag) r.diagError else it.diagError)
                }
            },
            onFailure = { e -> _state.update { it.copy(error = e.message ?: "Read failed") } }
        )
    }

    private class RoundResult(val snapshot: NetworkSnapshot, val name: String?, val diagError: String?, val diagFailed: Boolean,
        val mbps: Double? = null, val ulMbps: Double? = null)

    private fun readRound(sim: Int, fresh: Boolean, withDiag: Boolean): Result<RoundResult> = runCatching {
        if (!daemon.isReady.value) throw IllegalStateException("Waiting for the daemon…")
        val t0 = SystemClock.elapsedRealtime()
        val rawReads = requestRaw(sim, NetworkReads.ROUND)
        val reads = outcomes(NetworkReads.ROUND, rawReads)
        // DIAG only adds to the QMI round; its failure never fails the round.
        var diagError: String? = null
        val diagStats = if (withDiag) {
            try { diag.stats(sim) } catch (e: Exception) {
                AppLog.w(TAG, "DIAG stats failed: ${e.message}")
                diagError = e.message ?: "DIAG read failed"
                null
            }
        } else null
        val snap = SnapshotBuilder(reads, nrHardwareBands, diagStats, diagMode = withDiag)
            .build(sim, SystemClock.elapsedRealtime(), (SystemClock.elapsedRealtime() - t0).toInt())
        // Slow tier: operator name only when the PLMN changes.
        val plmn = snap.plmn
        val key = plmn?.let { "$it/${snap.mode}" }
        if (plmn != null && key != namedPlmn[sim - 1]) {
            cachedName[sim - 1] = readOperatorName(sim, plmn, snap.roaming)
            namedPlmn[sim - 1] = key
        }
        val name = cachedName[sim - 1].takeIf { key != null && key == namedPlmn[sim - 1] }
        if (_state.value.replayEnabled) recorder?.let {
            val elapsed = SystemClock.elapsedRealtime()
            it.append(elapsed, ReplayFormat.encode(sim, elapsed, snap.roundMs, name, nrHardwareBands,
                NetworkReads.ROUND, rawReads, if (diagStats != null) diag.lastStatsJson else null))
        }
        RoundResult(snap, name, diagError, withDiag && diagStats == null, diagStats?.totalMbps, diagStats?.totalUlMbps)
    }

    /** The recording of [sim] so far, for the replay while frozen. Blocking. */
    fun openReplay(sim: Int): ReplaySession? = recorder?.open(sim)

    /**
     * Operator name for a SIM tab label, from QMI only (no telephony API):
     * the current PLMN's network name, else the SIM's home network
     * description when there's no service. Blocking; call off the main thread.
     * Null when the SIM can't be read (e.g. SIM 2 unavailable).
     */
    fun operatorNameForLabel(sim: Int): String? = try {
        val sys = request(sim, listOf(NetworkReads.SYS))[NetworkReads.SYS]
        val s = (sys as? ReadOutcome.Ok)?.frame?.takeIf { it.ok }?.let { runCatching { decodeSysInfo(it) }.getOrNull() }
        val plmn = s?.primaryPlmn ?: s?.common?.values?.firstNotNullOfOrNull { it.plmn }
        if (plmn != null) {
            readOperatorName(sim, plmn, s?.common?.values?.firstNotNullOfOrNull { it.roaming })
        } else {
            val home = request(sim, listOf(NetworkReads.HOME))[NetworkReads.HOME]
            (home as? ReadOutcome.Ok)?.frame?.takeIf { it.ok }?.let { runCatching { decodeHomeNetwork(it) }.getOrNull() }?.description
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "SIM $sim operator name read failed: ${e.message}")
        null
    }

    /**
     * The PLMN's network name, else the home network name when this PLMN is
     * home or we're not roaming. A cell can broadcast a shared PLMN the modem
     * has no name for (WCDMA here: 520 19 while registered on 520 04); then
     * GET_PLMN_NAME just echoes the digits ("520 19"), which isn't a name.
     */
    private fun readOperatorName(sim: Int, plmn: Plmn, roaming: Boolean?): String? = try {
        val r = request(sim, listOf(NetworkReads.HOME, "0044:" + plmnNamePayload(plmn)))
        fun frame(k: String) = (r[k] as? ReadOutcome.Ok)?.frame?.takeIf { it.ok }
        val plmnName = frame(r.keys.last())?.let { runCatching { decodePlmnName(it) }.getOrNull() }
        val home = frame(NetworkReads.HOME)?.let { runCatching { decodeHomeNetwork(it) }.getOrNull() }
        val isHome = home != null && home.mcc == plmn.mcc.toInt() && home.mnc == plmn.mnc.toInt()
        listOfNotNull(plmnName?.longName, plmnName?.shortName, plmnName?.spn).firstOrNull { !isNumericName(it) }
            ?: home?.takeIf { isHome || roaming == false }?.description?.takeIf { !isNumericName(it) }
    } catch (e: Exception) {
        AppLog.w(TAG, "operator name read failed: ${e.message}")
        null
    }

    private fun isNumericName(name: String) = name.all { it.isDigit() || it == ' ' || it == '-' }

    /**
     * SIM [sim]'s subscription from UIM card status (read-only): active,
     * turned off, no card, or UNKNOWN when it can't be read. Blocking; call
     * off the main thread.
     */
    /** Both SIMs' card status from one read: [SIM 1, SIM 2] (UNKNOWN if unreadable). */
    fun simStatuses(): List<SimSlotStatus> = try {
        val resp = daemon.uimCardStatus()
        val rx = resp.optJSONObject("uim_card_status")?.takeIf { it.optString("status") == "ok" }?.optString("rx")
        val status = rx?.takeIf { it.isNotEmpty() }?.let(QmiFrame::parseHex)?.takeIf { it.ok }?.let(::decodeCardStatus)
        listOf(status?.statusOf(1) ?: SimSlotStatus.UNKNOWN, status?.statusOf(2) ?: SimSlotStatus.UNKNOWN)
    } catch (e: Exception) {
        AppLog.w(TAG, "card status read failed: ${e.message}")
        listOf(SimSlotStatus.UNKNOWN, SimSlotStatus.UNKNOWN)
    }

    fun simStatus(sim: Int): SimSlotStatus = try {
        val resp = daemon.uimCardStatus()
        val rx = resp.optJSONObject("uim_card_status")?.takeIf { it.optString("status") == "ok" }?.optString("rx")
        val frame = rx?.takeIf { it.isNotEmpty() }?.let(QmiFrame::parseHex)?.takeIf { it.ok }
        frame?.let(::decodeCardStatus)?.statusOf(sim) ?: SimSlotStatus.UNKNOWN
    } catch (e: Exception) {
        AppLog.w(TAG, "card status read failed: ${e.message}")
        SimSlotStatus.UNKNOWN
    }

    /** One nas_read call; results are matched to [keys] by position. */
    private fun request(sim: Int, keys: List<String>): Map<String, ReadOutcome> = outcomes(keys, requestRaw(sim, keys))

    private fun outcomes(keys: List<String>, arr: JSONArray): Map<String, ReadOutcome> =
        keys.indices.associate { i -> keys[i] to readOutcome(arr.getJSONObject(i)) }

    private fun requestRaw(sim: Int, keys: List<String>): JSONArray {
        val resp = daemon.nasRead(sim, keys)
        if (!resp.optBoolean("ok")) {
            val msg = resp.optJSONObject("error")?.optString("message").orEmpty()
            throw IllegalStateException(if (sim == 2 && msg.contains("bind")) "SIM 2 is not available" else msg.ifEmpty { "nas_read failed" })
        }
        val arr = resp.getJSONObject("nas_read").getJSONArray("reads")
        if (arr.length() != keys.size) throw IllegalStateException("nas_read returned ${arr.length()} of ${keys.size} reads")
        return arr
    }

    companion object {
        private const val TAG = "NetworkMonitor"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val THPUT_KEEP_MS = 150_000L
    }
}
