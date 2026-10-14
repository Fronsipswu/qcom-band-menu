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
    val rank: Int?, val transmissionMode: Int?, val txAntennas: Int?, val rxAntennas: Int?
)

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
    val csiPorts: Int? = null
)

data class DiagStats(val windowMs: Long, val carriers: List<DiagCarrier>, val nr: List<DiagNrCarrier> = emptyList()) {
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
                    beamSinr = c.d("bsinr"), csiSinr = c.d("csinr"), csiPorts = c.i("csi_ports")
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
                    txAntennas = c.i("tx"), rxAntennas = c.i("rx")
                )
            }, nr)
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
        private const val EXPECTED_VERSION = "1.22.5"
    }

    private var socket: LocalSocket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** The helper reply [stats] last parsed, as recorded for the replay. */
    @Volatile
    var lastStatsJson: JSONObject? = null
        private set

    /** One window of stats, starting the helper first if needed. Blocking. */
    @Synchronized
    fun stats(): DiagStats {
        if (socket == null) ensureRunning()
        val resp = try {
            request("""{"cmd":"stats"}""")
        } catch (e: IOException) {
            // The helper exited (watchdog after a pause, or a crash): once more.
            close()
            ensureRunning()
            request("""{"cmd":"stats"}""")
        }
        if (resp.optString("version") != EXPECTED_VERSION) {
            AppLog.i(TAG, "helper v${resp.optString("version")} != v$EXPECTED_VERSION, restarting")
            stopHelper()
            ensureRunning()
            return DiagStats.parse(request("""{"cmd":"stats"}""").also { lastStatsJson = it })
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
        Shell.cmd("setsid '${dest.absolutePath}' -uid ${Process.myUid()} " +
            "</dev/null >/dev/null 2>'${err.absolutePath}' &").exec()
        for (i in 1..12) {
            Thread.sleep(250)
            if (tryConnect()) return
        }
        val stderr = if (err.exists()) err.readText().trim() else ""
        throw IOException(stderr.ifEmpty { "The DIAG helper didn't start" })
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
