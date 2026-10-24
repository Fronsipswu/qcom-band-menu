package dev.qcom.bandmenu.network

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.topjohnwu.superuser.Shell
import dev.qcom.bandmenu.AppLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

/** One carrier's PDSCH summary over the helper's last window (one round). */
data class DiagCarrier(
    /** ML1 carrier slot: 0 = PCell, n = SCell n (B173/B126/B184). */
    val slot: Int,
    /** From B184; null until the helper has seen one. */
    val earfcn: Long?, val pci: Int?, val band: Int?, val bandwidthMhz: Double?,
    /** B184 DL state: 1 deactivated, 2 activated (the PCell is always 2). */
    val state: Int?,
    /** B193 serving-cell measurements, averaged over the window. */
    val rsrp: Double?, val rsrq: Double?, val sinr: Double?,
    val transportBlocks: Int,
    /** PHY downlink rate from every scheduled transport block (CRC failures and
     *  retransmissions included, as for uplink), Mbps. */
    val mbps: Double,
    /** PHY uplink rate from every PUSCH transport block (B139, retransmissions
     *  included), Mbps; null when the carrier sent none this window. */
    val ulMbps: Double?,
    val bler: Double?, val mcs: Double?, val rbs: Double?,
    /** Majority modulation, e.g. "64QAM". */
    val modulation: String?,
    val rank: Int?, val transmissionMode: Int?, val txAntennas: Int?, val rxAntennas: Int?,
    /** Mean scheduled layers per TB (B173); null when the carrier had no TBs. */
    val layers: Double? = null,
    /** B193 per RX chain (index = chain, null = no value): the antenna card. */
    val rsrpRx: List<Double?>? = null, val rsrqRx: List<Double?>? = null, val sinrRx: List<Double?>? = null,
    /** The uplink card, over the window: mean PUSCH RBs (B139), mean MCS of
     *  PUSCH grants (B16D; reserved retransmission indices left out), the
     *  most used modulation (B139) and the mean actual PUSCH transmit power,
     *  dBm (B16E); the mean PUCCH transmit power, dBm, and the uplink BLER,
     *  % of PUSCH records retransmitted (B16D). Null when the carrier had
     *  none. */
    val ulRbs: Double? = null, val ulMcs: Double? = null, val ulModulation: String? = null, val ulPower: Double? = null,
    val ulPucch: Double? = null, val ulBler: Double? = null
) {
    /** Any uplink value this window. */
    val hasUplink: Boolean get() = ulMbps != null || ulRbs != null || ulMcs != null || ulPower != null || ulPucch != null
}

/** One NR carrier over the helper's last window (B825/B823/B887/B97F/B8D8/B8A7). */
data class DiagNrCarrier(
    /** The modem's carrier ID (cc_id). */
    val cc: Int,
    /** From B823 (RRC serving cell); null when the helper hasn't seen one. */
    val pcell: Boolean?,
    val pci: Int,
    /** SSB NR-ARFCN (what B97F/B887 name the cell by). */
    val arfcn: Long,
    /** From B825; null until the helper has seen one. */
    val dlArfcn: Long?, val band: Int?, val bandwidthMhz: Int?, val scsKhz: Int?, val maxLayers: Int?,
    /** SS-SINR from B8D8 (B8DD on X65): SSB records, best RX chain. */
    val rsrp: Double?, val rsrq: Double?, val sinr: Double?,
    /** B8A7 wideband CQI and rank indicator (as a rank). */
    val cqi: Int?, val ri: Int?,
    val transportBlocks: Int,
    /** PHY downlink rate from every scheduled transport block (CRC failures and
     *  retransmissions included, as for uplink), Mbps. */
    val mbps: Double,
    /** PHY uplink rate from every PUSCH transport block (B883, retransmissions
     *  included), Mbps; null when the carrier sent none this window. */
    val ulMbps: Double?,
    val bler: Double?, val mcs: Double?, val rbs: Double?,
    /** Mean scheduled layers per TB. */
    val layers: Double?,
    val modulation: String?, val rxAntennas: Int?,
    /** SNR of the carrier's strongest SSB beam (B96A, X65): a per-beam
     *  searcher measurement, not the serving SS-SINR. Not shown. */
    val beamSinr: Double? = null,
    /** CSI-SINR: B8D8/B8DD TRS records (a CSI-RS for tracking), best RX chain. */
    val csiSinr: Double? = null,
    /** CSI-RS ports the network configured for CSI acquisition (NZP CSI-RS,
     *  from the RRC reconfiguration); null until the helper reports it. */
    val csiPorts: Int? = null,
    /** Per RX chain (index = chain, null = no value), the antenna card: B97F
     *  RSRP (RX 0-1 from the serving beam, RX 2-3 from the carrier's serving
     *  values), B8D8/B8DD SS-SINR and CSI-SINR. */
    val rsrpRx: List<Double?>? = null, val sinrRx: List<Double?>? = null, val csiSinrRx: List<Double?>? = null,
    /** The uplink card, over the window (B883 PUSCH grants): mean RBs, mean
     *  MCS (reserved retransmission indices left out), the most used
     *  modulation, mean layers (DMRS ports used) and the last TX mode (SISO,
     *  SMSL, SMDL), the uplink BLER (% of PUSCH bytes that were HARQ
     *  retransmissions); B884's mean PUSCH and PUCCH transmit power, each
     *  capped at the maximum, dBm. Null when the carrier had none. */
    val ulRbs: Double? = null, val ulMcs: Double? = null, val ulModulation: String? = null,
    val ulLayers: Double? = null, val ulTxMode: String? = null, val ulPower: Double? = null,
    val ulPucch: Double? = null, val ulBler: Double? = null
) {
    /** Any uplink value this window. */
    val hasUplink: Boolean get() = ulMbps != null || ulRbs != null || ulPower != null || ulPucch != null
}

/** One NR neighbour beam (B97F), as the neighbour list shows it. */
data class DiagNrNeighbour(val arfcn: Long, val pci: Int, val beam: Int, val rsrp: Double, val rsrq: Double)

data class DiagStats(val windowMs: Long, val carriers: List<DiagCarrier>, val nr: List<DiagNrCarrier> = emptyList(),
    /** Raw timing-advance index of the last random access (the RAR): LTE B168,
     *  NR B88A. The helper drops it when the serving cell changes. */
    val lteTimingAdvance: Int? = null, val nrTimingAdvance: Int? = null,
    /** NR neighbour beams, strongest RSRP first (the serving beams left out);
     *  null when the helper sent no list (no NR measurement lately). */
    val nrNeighbours: List<DiagNrNeighbour>? = null) {
    /** Combined PHY downlink throughput: every LTE and NR carrier. */
    val totalMbps: Double get() = carriers.sumOf { it.mbps } + nr.sumOf { it.mbps }

    /** Combined PHY uplink throughput: every LTE and NR carrier. */
    val totalUlMbps: Double get() = carriers.sumOf { it.ulMbps ?: 0.0 } + nr.sumOf { it.ulMbps ?: 0.0 }

    /** The carriers as B184 lists them: the PCell, then configured SCells by
     *  slot. Empty until the helper has seen a B184. */
    val caCarriers: List<DiagCarrier>
        get() = carriers.filter { it.earfcn != null && it.pci != null && (it.slot == 0 || (it.state ?: 0) > 0) }
            .sortedBy { it.slot }.takeIf { l -> l.any { it.slot == 0 } }.orEmpty()

    /** The PCell's entry; checked against B184's PCell when the helper has it,
     *  so another SIM's (or a stale) PCell never borrows these numbers. */
    fun forPcell(earfcn: Long, pci: Int): DiagCarrier? =
        carriers.firstOrNull { it.slot == 0 && (it.earfcn == null || (it.earfcn == earfcn && it.pci == pci)) }

    /** An SCell's entry, matched by EARFCN + PCI (QMI's SCell index isn't
     *  the ML1 slot number). */
    fun forScell(earfcn: Long, pci: Int): DiagCarrier? =
        carriers.firstOrNull { it.slot > 0 && it.earfcn == earfcn && it.pci == pci }

    companion object {
        fun parse(o: JSONObject): DiagStats {
            val arr = o.getJSONArray("carriers")
            fun JSONObject.d(k: String) = if (has(k)) getDouble(k) else null
            fun JSONObject.i(k: String) = if (has(k)) getInt(k) else null
            // One value per RX chain, null where the chain has none.
            fun JSONObject.chains(k: String) = optJSONArray(k)?.let { a ->
                (0 until a.length()).map { if (a.isNull(it)) null else a.getDouble(it) }
            }
            // Scheduled TBs ("mbps" counts CRC-passed ones only).
            fun JSONObject.scheduledMbps() = optDouble("mbps_sched", optDouble("mbps", 0.0))
            val nrArr = o.optJSONArray("nr")
            val nr = if (nrArr == null) emptyList() else (0 until nrArr.length()).map { n ->
                val c = nrArr.getJSONObject(n)
                DiagNrCarrier(
                    cc = c.getInt("cc"), pcell = if (c.isNull("pcell")) null else c.getBoolean("pcell"), pci = c.getInt("pci"), arfcn = c.getLong("arfcn"),
                    dlArfcn = if (c.has("dl_arfcn")) c.getLong("dl_arfcn") else null, band = c.i("band"),
                    bandwidthMhz = c.i("bw"), scsKhz = c.i("scs"), maxLayers = c.i("max_layers"),
                    rsrp = c.d("rsrp"), rsrq = c.d("rsrq"), sinr = c.d("sinr"), cqi = c.i("cqi"), ri = c.i("ri"),
                    transportBlocks = c.optInt("tb"), mbps = c.scheduledMbps(), ulMbps = c.d("ul_mbps"),
                    bler = c.d("bler"), mcs = c.d("mcs"), rbs = c.d("rbs"), layers = c.d("layers"),
                    modulation = c.optString("mod").ifEmpty { null }, rxAntennas = c.i("rx"),
                    beamSinr = c.d("bsinr"), csiSinr = c.d("csinr"), csiPorts = c.i("csi_ports"),
                    rsrpRx = c.chains("rsrp_rx"), sinrRx = c.chains("sinr_rx"), csiSinrRx = c.chains("csinr_rx"),
                    ulRbs = c.d("ul_rbs"), ulMcs = c.d("ul_mcs"), ulModulation = c.optString("ul_mod").ifEmpty { null },
                    ulLayers = c.d("ul_layers"), ulTxMode = c.optString("ul_tx_mode").ifEmpty { null }, ulPower = c.d("ul_pwr"),
                    ulPucch = c.d("ul_pucch"), ulBler = c.d("ul_bler")
                )
            }
            return DiagStats(o.optLong("ms"), (0 until arr.length()).map { n ->
                val c = arr.getJSONObject(n)
                DiagCarrier(
                    slot = c.getInt("slot"),
                    earfcn = if (c.has("earfcn")) c.getLong("earfcn") else null,
                    pci = c.i("pci"), band = c.i("band"), bandwidthMhz = c.d("bw"), state = c.i("state"),
                    rsrp = c.d("rsrp"), rsrq = c.d("rsrq"), sinr = c.d("sinr"),
                    transportBlocks = c.optInt("tb"),
                    mbps = c.scheduledMbps(), ulMbps = c.d("ul_mbps"),
                    bler = c.d("bler"), mcs = c.d("mcs"), rbs = c.d("rbs"),
                    modulation = c.optString("mod").ifEmpty { null },
                    rank = c.i("rank"), transmissionMode = c.i("tm"),
                    txAntennas = c.i("tx"), rxAntennas = c.i("rx"), layers = c.d("layers"),
                    rsrpRx = c.chains("rsrp_rx"), rsrqRx = c.chains("rsrq_rx"), sinrRx = c.chains("sinr_rx"),
                    ulRbs = c.d("ul_rbs"), ulMcs = c.d("ul_mcs"), ulModulation = c.optString("ul_mod").ifEmpty { null },
                    ulPower = c.d("ul_pwr"), ulPucch = c.d("ul_pucch"), ulBler = c.d("ul_bler")
                )
            }, nr, lteTimingAdvance = o.i("lte_ta"), nrTimingAdvance = o.i("nr_ta"),
                nrNeighbours = o.optJSONArray("nr_nb")?.let { a ->
                    (0 until a.length()).map { k ->
                        val e = a.getJSONArray(k)
                        DiagNrNeighbour(e.getLong(0), e.getInt(1), e.getInt(2), e.getDouble(3), e.getDouble(4))
                    }
                })
        }

        /** Combined DL and UL throughput from [o] without decoding full carrier lists. */
        fun parseThroughput(o: JSONObject, elapsedMs: Long): ThputPoint {
            val arr = o.getJSONArray("carriers")
            var dl = 0.0
            var ul = 0.0
            for (n in 0 until arr.length()) {
                val c = arr.getJSONObject(n)
                dl += c.optDouble("mbps_sched", c.optDouble("mbps", 0.0))
                if (c.has("ul_mbps")) ul += c.getDouble("ul_mbps")
            }
            val nrArr = o.optJSONArray("nr")
            if (nrArr != null) {
                for (n in 0 until nrArr.length()) {
                    val c = nrArr.getJSONObject(n)
                    dl += c.optDouble("mbps_sched", c.optDouble("mbps", 0.0))
                    if (c.has("ul_mbps")) ul += c.getDouble("ul_mbps")
                }
            }
            return ThputPoint(elapsedMs, dl, ul)
        }
    }
}

/**
 * Talks to qbm-diagd, the DIAG helper (a separate dynamic root binary; the
 * static QMI daemon can't load vendor libdiag). Started on demand through
 * the root shell and polled once per Network round. The helper exits by
 * itself when it isn't polled for a few seconds, so leaving the page or the
 * app never leaves a DIAG subscription behind.
 */
class DiagClient(private val context: Context) {

    companion object {
        private const val TAG = "DiagClient"
        private const val BINARY_NAME = "qbm-diagd"
        private const val SOCKET_NAME = "qbm_diagd"
        private const val EXPECTED_VERSION = "1.22.29"
        /** SELinux domains root processes (the helper) run in; see allowRootSocket. */
        private val ROOT_DOMAINS = listOf("su", "magisk")
    }

    private var socket: LocalSocket? = null
    /** The SELinux connectto rule was tried in this app run (see ensureRunning). */
    private var ruleTried = false
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** The helper reply [stats] last parsed, as recorded for the replay. */
    @Volatile
    var lastStatsJson: JSONObject? = null
        private set

    /**
     * One window of stats for [sim] (1 or 2), starting the helper first if
     * needed. Blocking. The helper keeps each SIM's logs apart when the modem
     * names their subscription ("sim_tags"); otherwise everything is SIM 1.
     */
    @Synchronized
    fun stats(sim: Int = 1): DiagStats {
        val ask = """{"cmd":"stats","sim":$sim}"""
        if (socket == null) ensureRunning()
        val resp = try {
            request(ask)
        } catch (e: IOException) {
            // The helper exited (watchdog after a pause, or a crash): once more.
            close()
            ensureRunning()
            request(ask)
        }
        if (resp.optString("version") != EXPECTED_VERSION) {
            AppLog.i(TAG, "helper v${resp.optString("version")} != v$EXPECTED_VERSION, restarting")
            stopHelper()
            ensureRunning()
            return DiagStats.parse(request(ask).also { lastStatsJson = it })
        }
        lastStatsJson = resp
        return DiagStats.parse(resp)
    }

    /** Unsubscribes and stops the helper. Blocking; safe when not running. */
    @Synchronized
    fun stop() {
        if (socket == null && !tryConnect()) return
        stopHelper()
    }

    private fun stopHelper() {
        try { request("""{"cmd":"stop"}""") } catch (_: IOException) {}
        close()
        // The helper unsubscribes and drains (~0.7 s) before it exits.
        Thread.sleep(800)
    }

    private fun request(line: String): JSONObject {
        val w = writer ?: throw IOException("Not connected")
        val r = reader ?: throw IOException("Not connected")
        try {
            w.write(line); w.write("\n"); w.flush()
            val resp = r.readLine() ?: throw IOException("Helper closed the connection")
            return JSONObject(resp)
        } catch (e: IOException) {
            close(); throw e
        } catch (e: org.json.JSONException) {
            close(); throw IOException("Malformed helper reply")
        }
    }

    private fun ensureRunning() {
        if (tryConnect()) return
        val dest = File(context.filesDir, BINARY_NAME)
        copyBinary(dest)
        dest.setExecutable(true)
        val err = File(context.cacheDir, "diagd_stderr.log").apply { delete() }
        AppLog.i(TAG, "starting helper")
        // No LD_LIBRARY_PATH: libdiag is opened by its full path and its own
        // dependencies (liblog, libcutils, libc++) are system libraries. Putting
        // /vendor/lib64 first loaded a vendor libc++ that the system liblog
        // can't use on newer Android ("cannot locate symbol ... __hash_memory").
        val launch = "setsid '${dest.absolutePath}' -uid ${Process.myUid()} " +
            "</dev/null >/dev/null 2>'${err.absolutePath}' &"
        // libsu drops the shell's stderr unless asked; a launch that fails
        // before the helper runs (no file access) only shows up there.
        val shellErr = mutableListOf<String>()
        Shell.cmd(launch).to(mutableListOf(), shellErr).exec()
        if (waitConnect(12)) return
        // The helper ran and reported its own failure: not a connection problem.
        // (libdiag's "diag: ..." trace lines can land here from a healthy helper.)
        val helperErr = if (err.exists()) err.readLines().filterNot { it.startsWith("diag:") }
            .joinToString("\n").trim() else ""
        if (helperErr.isNotEmpty()) throw IOException(helperErr)
        // KernelSU keeps the capabilities a root process started with: after
        // the app's root profile changes, the app's persistent root shell can
        // be uid 0 with none, unable to reach the app's files (Xperia 1 IV).
        if (!rootHasCapabilities()) throw IOException("Root has no capabilities: restart the app")
        // Some root solutions' SELinux policy doesn't let apps connect to a
        // root process's socket (KernelSU on the Xperia 1 IV: connectto
        // denied while the helper runs). Add only that rule, live until
        // reboot, then retry; the helper accepts only this app's uid. Tried
        // once per app run, so phones where DIAG fails for other reasons
        // aren't patched or slowed down on every round.
        if (!ruleTried && allowRootSocket().also { ruleTried = true }) {
            if (waitConnect(4)) return
            // Relaunch only a helper that has exited: a second one next to a
            // running one fails with "Address already in use", hiding why the
            // connection was refused.
            if (!helperRunning()) {
                Shell.cmd(launch).to(mutableListOf(), shellErr).exec()
                if (waitConnect(12)) return
            }
        }
        if (helperRunning()) throw IOException("The DIAG helper is running, but the app can't connect to it")
        val stderr = if (err.exists()) err.readText().trim() else shellErr.joinToString("\n").trim()
        throw IOException(stderr.ifEmpty { "The DIAG helper didn't start" })
    }

    private fun waitConnect(tries: Int): Boolean {
        for (i in 1..tries) {
            Thread.sleep(250)
            if (tryConnect()) return true
        }
        return false
    }

    private fun helperRunning(): Boolean = Shell.cmd("pidof $BINARY_NAME >/dev/null").exec().isSuccess

    /** False when the app's root shell runs without effective capabilities. */
    private fun rootHasCapabilities(): Boolean {
        val cap = Shell.cmd("grep CapEff /proc/self/status").exec().out.firstOrNull()
            ?.substringAfter(':')?.trim() ?: return true
        return cap.trimStart('0').isNotEmpty()
    }

    /** Lets the app connect to the helper's socket, `allow untrusted_app <root
     *  domain> unix_stream_socket connectto`, through the root solution's live
     *  policy tool; true if a rule was accepted. The helper runs in the root
     *  shell's domain: "su" on KernelSU, "magisk" on Magisk (Magisk 26 on the
     *  OnePlus Nord: the "su" rule is accepted but doesn't apply). */
    private fun allowRootSocket(): Boolean {
        val added = ROOT_DOMAINS.filter { domain ->
            val rule = "allow untrusted_app $domain unix_stream_socket connectto"
            // A subshell: libsu's root shell is persistent, so a bare exit would end it.
            Shell.cmd(
                "(for t in ksud /data/adb/ksud /data/adb/ksu/bin/ksud; do " +
                    "command -v \$t >/dev/null 2>&1 && \$t sepolicy patch '$rule' >/dev/null 2>&1 && exit 0; done; " +
                "for t in magiskpolicy /data/adb/magisk/magiskpolicy; do " +
                    "command -v \$t >/dev/null 2>&1 && \$t --live '$rule' >/dev/null 2>&1 && exit 0; done; exit 1)"
            ).exec().isSuccess
        }
        AppLog.i(TAG, "SELinux connectto rule: ${if (added.isEmpty()) "no tool accepted it" else "added for $added"}")
        return added.isNotEmpty()
    }

    /** A helper that's exiting can keep the file busy (ETXTBSY) briefly. */
    private fun copyBinary(dest: File) {
        var attempt = 0
        while (true) {
            try {
                context.assets.open(BINARY_NAME).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                return
            } catch (e: IOException) {
                if (++attempt >= 40) throw e
                Thread.sleep(50)
            }
        }
    }

    private fun tryConnect(): Boolean {
        val s = LocalSocket()
        return try {
            s.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = 5000
            socket = s
            writer = s.outputStream.bufferedWriter()
            reader = s.inputStream.bufferedReader()
            true
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            false
        }
    }

    private fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null; writer = null; reader = null
    }
}
