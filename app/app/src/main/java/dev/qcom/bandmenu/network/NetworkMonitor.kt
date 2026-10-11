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
import org.json.JSONObject

/**
 * Polls the daemon's read-only monitoring client for the Network page.
 *
 * Runs only while the page is visible (settled on it), the activity is
 * resumed, and monitoring isn't frozen. One round at a time, then a 1 s
 * pause. Owned by DaemonHolder (a ViewModel), so a configuration change
 * keeps the same monitor and never starts a second poller.
 *
 * Monitoring uses the daemon's separate per-SIM NAS clients, so it never
 * changes the SIM that band/cell-lock commands target and needs no modemLock.
 */
class NetworkMonitor(
    private val daemon: DaemonManager,
    private val scope: CoroutineScope
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
        val lastBySim: Map<Int, Reading> = emptyMap()
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

    /** LTE serving RSRP/RSRQ/SINR as last shown, per SIM (see [holdLteServing]). */
    private class HeldLte(val cell: String, val atMs: Long, val metrics: Map<String, Metric>)
    private val heldLte = arrayOfNulls<HeldLte>(2)

    fun setVisible(v: Boolean) { visible = v; reschedule() }
    fun setResumed(v: Boolean) { resumed = v; reschedule() }

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
        val run = visible && resumed && !_state.value.frozen
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
        val result = withContext(Dispatchers.IO) { readRound(sim, fresh) }
        // A SIM switch during the round makes its result stale.
        if (_state.value.sim != sim) return@withLock
        result.fold(
            onSuccess = { (snap, name) ->
                _state.update { it.copy(snapshot = snap, operatorName = name, error = null, lastBySim = it.lastBySim + (sim to Reading(snap, name))) }
            },
            onFailure = { e -> _state.update { it.copy(error = e.message ?: "Read failed") } }
        )
    }

    private fun readRound(sim: Int, fresh: Boolean): Result<Pair<NetworkSnapshot, String?>> = runCatching {
        if (!daemon.isReady.value) throw IllegalStateException("Waiting for the daemon…")
        val t0 = SystemClock.elapsedRealtime()
        val reads = request(sim, NetworkReads.ROUND)
        val snap = holdLteServing(sim, fresh,
            SnapshotBuilder(reads, nrHardwareBands).build(sim, SystemClock.elapsedRealtime(), (SystemClock.elapsedRealtime() - t0).toInt()))
        // Slow tier: operator name only when the PLMN changes.
        val plmn = snap.plmn
        val key = plmn?.let { "$it/${snap.mode}" }
        if (plmn != null && key != namedPlmn[sim - 1]) {
            cachedName[sim - 1] = readOperatorName(sim, plmn, snap.roaming)
            namedPlmn[sim - 1] = key
        }
        snap to cachedName[sim - 1].takeIf { key != null && key == namedPlmn[sim - 1] }
    }

    /**
     * The LTE serving card's RSRP/RSRQ/SINR change at most every
     * [LTE_SERVING_HOLD_MS]; between those, the values last shown are kept.
     * Everything else (CA table included) still updates every round. A new
     * serving cell (EARFCN/PCI), a SIM switch or pull-to-refresh shows the
     * new reading at once.
     */
    private fun holdLteServing(sim: Int, fresh: Boolean, snap: NetworkSnapshot): NetworkSnapshot {
        val card = snap.cells.firstOrNull()?.takeIf { it.rat == Rat.LTE }
        if (card == null) { heldLte[sim - 1] = null; return snap }
        val cell = card.fields.filter { it.label == "EARFCN" || it.label == "PCI" }.joinToString("/") { it.value }
        val now = SystemClock.elapsedRealtime()
        val held = heldLte[sim - 1]
        if (fresh || held == null || held.cell != cell || now - held.atMs >= LTE_SERVING_HOLD_MS) {
            heldLte[sim - 1] = HeldLte(cell, now, card.metrics.filter { it.label in LTE_HELD }.associateBy { it.label })
            return snap
        }
        val shown = card.copy(metrics = card.metrics.map { held.metrics[it.label] ?: it })
        return snap.copy(cells = listOf(shown) + snap.cells.drop(1))
    }

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
    private fun request(sim: Int, keys: List<String>): Map<String, ReadOutcome> {
        val resp = daemon.nasRead(sim, keys)
        if (!resp.optBoolean("ok")) {
            val msg = resp.optJSONObject("error")?.optString("message").orEmpty()
            throw IllegalStateException(if (sim == 2 && msg.contains("bind")) "SIM 2 is not available" else msg.ifEmpty { "nas_read failed" })
        }
        val arr = resp.getJSONObject("nas_read").getJSONArray("reads")
        if (arr.length() != keys.size) throw IllegalStateException("nas_read returned ${arr.length()} of ${keys.size} reads")
        return keys.indices.associate { i -> keys[i] to outcome(arr.getJSONObject(i)) }
    }

    private fun outcome(o: JSONObject): ReadOutcome {
        val status = o.optString("status")
        if (status != "ok") return ReadOutcome.Failed(status)
        return try {
            ReadOutcome.Ok(QmiFrame.parseHex(o.getString("rx")))
        } catch (e: QmiFormatException) {
            ReadOutcome.Malformed(e.message ?: "malformed")
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"
        private const val POLL_INTERVAL_MS = 1_000L
        /** The LTE serving card's signal values update at this pace (rounds stay ~1 s). */
        private const val LTE_SERVING_HOLD_MS = 2_000L
        private val LTE_HELD = setOf("RSRP", "RSRQ", "SINR")
    }
}
