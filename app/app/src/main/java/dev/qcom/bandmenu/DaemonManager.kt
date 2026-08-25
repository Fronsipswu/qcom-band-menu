package dev.qcom.bandmenu

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.mutableStateOf
import com.topjohnwu.superuser.Shell
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

class DaemonManager(private val context: Context) {

    companion object {
        private const val TAG = "QcomBand"
        private const val BINARY_NAME = "qcom-bandlockd"
        private const val SOCKET_NAME = "qcom_bandlockd"
        private const val EXPECTED_DAEMON_VERSION = "4.4.0"
    }

    var isReady = mutableStateOf(false)
        private set

    var isRootDenied = mutableStateOf(false)
        private set

    var launchError = mutableStateOf<String?>(null)
        private set

    @Volatile
    private var socket: LocalSocket? = null
    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var reader: BufferedReader? = null
    private var requestId = 0

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var connectedDaemonVersion: String? = null

    @Volatile
    var onConnectionEvent: ((Boolean) -> Unit)? = null

    fun start(onDenied: () -> Unit, onLaunchFailed: (String) -> Unit = {}) {
        AppLog.d(TAG, "start: requesting shell...")
        launchError.value = null
        Shell.getShell { shell ->
            AppLog.d(TAG, "start: shell obtained, isRoot=${shell.isRoot}, status=${shell.status}")
            if (shell.isRoot) {
                Thread { launchAndConnect(onLaunchFailed) }.start()
            } else {
                AppLog.e(TAG, "start: root denied")
                Handler(Looper.getMainLooper()).post {
                    isRootDenied.value = true
                    onDenied()
                }
            }
        }
    }

    private fun launchAndConnect(onLaunchFailed: (String) -> Unit) {
        // Try connecting to an existing daemon first — avoids ETXTBSY when
        // overwriting a binary that's still being executed by a running daemon.
        AppLog.i(TAG, "launchAndConnect: trying existing daemon...")
        if (tryConnect()) {
            if (connectedDaemonVersion == EXPECTED_DAEMON_VERSION) {
                AppLog.i(TAG, "launchAndConnect: connected to existing daemon (v$connectedDaemonVersion)")
                Handler(Looper.getMainLooper()).post { isReady.value = true }
                return
            }
            AppLog.i(TAG, "launchAndConnect: existing daemon v$connectedDaemonVersion != expected v$EXPECTED_DAEMON_VERSION, redeploying")
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            writer = null
            reader = null
        }
        AppLog.i(TAG, "launchAndConnect: no existing daemon, starting fresh")

        // No existing daemon reachable — kill any stale process before copying.
        // Use SIGKILL (-9) since the daemon may not handle SIGTERM.
        Shell.cmd("pkill -9 -f qcom-bandlockd 2>/dev/null; true").exec()
        Thread.sleep(500)

        try {
            val destFile = File(context.filesDir, BINARY_NAME)
            context.assets.open(BINARY_NAME).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.setExecutable(true)
            AppLog.i(TAG, "launchAndConnect: binary copied, size=${destFile.length()}")
        } catch (e: Exception) {
            AppLog.e(TAG, "launchAndConnect: failed to copy binary", e)
            val msg = "Failed to copy daemon binary: ${e.message}"
            launchError.value = msg
            Handler(Looper.getMainLooper()).post {
                isReady.value = false
                onLaunchFailed(msg)
            }
            return
        }

        val uid = Process.myUid()
        val path = File(context.filesDir, BINARY_NAME).absolutePath
        val stderrFile = File(context.cacheDir, "daemon_stderr.log")
        if (stderrFile.exists()) stderrFile.delete()
        AppLog.i(TAG, "launchAndConnect: launching daemon: $path -uid $uid")
        // </dev/null prevents the daemon from inheriting the shell's stdin,
        // which would cause exec() to block waiting for the pipe to close.
        Shell.cmd("setsid '$path' -uid $uid </dev/null >/dev/null 2>'${stderrFile.absolutePath}' &").exec()
        AppLog.i(TAG, "launchAndConnect: daemon launch command returned")

        for (i in 1..12) {
            Thread.sleep(250)
            if (tryConnect()) {
                AppLog.i(TAG, "launchAndConnect: connected after ${(i * 250)}ms")
                Handler(Looper.getMainLooper()).post { isReady.value = true }
                return
            }
        }

        AppLog.e(TAG, "launchAndConnect: failed to connect after 3s, trying SELinux fallback...")

        val selinuxMode = Shell.cmd("getenforce").exec().out.firstOrNull()?.trim() ?: ""
        if (selinuxMode.equals("Enforcing", ignoreCase = true)) {
            AppLog.i(TAG, "launchAndConnect: SELinux is Enforcing, trying permissive...")
            Shell.cmd("setenforce 0").exec()

            if (stderrFile.exists()) stderrFile.delete()
            Shell.cmd("setsid '$path' -uid $uid </dev/null >/dev/null 2>'${stderrFile.absolutePath}' &").exec()

            for (i in 1..20) {
                Thread.sleep(250)
                if (tryConnect()) {
                    AppLog.i(TAG, "launchAndConnect: connected after SELinux permissive (${i * 250}ms)")
                    Shell.cmd("setenforce 1").exec()
                    AppLog.i(TAG, "launchAndConnect: SELinux restored to Enforcing")
                    Handler(Looper.getMainLooper()).post { isReady.value = true }
                    return
                }
            }

            AppLog.e(TAG, "launchAndConnect: still failed after SELinux permissive")
            Shell.cmd("setenforce 1").exec()
            AppLog.i(TAG, "launchAndConnect: SELinux restored to Enforcing")
        }

        AppLog.e(TAG, "launchAndConnect: failed to connect")
        val stderr = if (stderrFile.exists()) stderrFile.readText().trim() else ""
        val msg = if (stderr.isNotEmpty()) {
            "Daemon failed to start. stderr:\n$stderr"
        } else {
            "Daemon failed to start (no stderr output)"
        }
        launchError.value = msg
        if (stderrFile.exists()) stderrFile.delete()
        Handler(Looper.getMainLooper()).post {
            isReady.value = false
            onLaunchFailed(msg)
        }
    }

    private fun tryConnect(): Boolean {
        val s = LocalSocket()
        return try {
            val addr = LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
            AppLog.i(TAG, "tryConnect: connecting to abstract '$SOCKET_NAME'...")
            // NOTE: connect(addr, timeout) throws UnsupportedOperationException on some
            // Android versions for abstract namespace sockets. Use the no-timeout
            // overload instead. The retry loop in launchAndConnect handles the
            // case where the daemon isn't ready yet.
            s.connect(addr)
            AppLog.i(TAG, "tryConnect: connected, setting up streams")
            s.soTimeout = 5000
            socket = s
            writer = s.outputStream.bufferedWriter()
            reader = s.inputStream.bufferedReader()
            // Verify the connection is truly usable: send a query and read
            // the response. A stale daemon with a different -uid will accept
            // the TCP connection then immediately close it (UID mismatch),
            // so a bare connect() is not sufficient.
            val probe = JsonRequestBuilder.query()
            probe.put("id", ++requestId)
            val reqStr = probe.toString()
            AppLog.i(TAG, "tryConnect: sending probe: $reqStr")
            writer!!.write(reqStr)
            writer!!.write("\n")
            writer!!.flush()
            AppLog.i(TAG, "tryConnect: probe sent, waiting for response...")
            val line = reader!!.readLine()
            if (line == null) {
                AppLog.i(TAG, "tryConnect: probe failed (connection closed by daemon)")
                throw IOException("Daemon closed connection (uid mismatch?)")
            }
            AppLog.i(TAG, "tryConnect: probe response (${line.length} chars)")
            // Parse to verify it's valid JSON
            val resp = JSONObject(line)
            connectedDaemonVersion = resp.optString("version", "")
            // Connection is good — upgrade to full 15s timeout
            s.soTimeout = 15000
            if (consecutiveFailures >= 3) {
                onConnectionEvent?.invoke(true)
            }
            consecutiveFailures = 0
            true
        } catch (e: Exception) {
            AppLog.i(TAG, "tryConnect: failed: ${e.javaClass.name}: ${e.message}")
            try { s.close() } catch (_: Exception) {}
            socket = null
            writer = null
            reader = null
            false
        }
    }

    /**
     * Attempts to reconnect to the daemon. On success, sends a query to resync
     * state (per spec §3). Returns true if reconnected and resynced.
     */
    @Synchronized
    fun reconnect(): Boolean {
        AppLog.d(TAG, "reconnect")
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
        return if (tryConnect()) {
            isReady.value = true
            // Resync by sending a query
            try {
                sendRequest(JsonRequestBuilder.query())
                true
            } catch (e: Exception) {
                AppLog.w(TAG, "reconnect: query after reconnect failed", e)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                writer = null
                reader = null
                isReady.value = false
                false
            }
        } else {
            isReady.value = false
            false
        }
    }

    @Synchronized
    fun sendRequest(request: JSONObject): JSONObject {
        val id = ++requestId
        request.put("id", id)

        // C1: Attempt reconnect if writer/reader is null
        if (writer == null || reader == null) {
            if (!reconnect()) {
                consecutiveFailures++
                if (consecutiveFailures == 3) {
                    onConnectionEvent?.invoke(false)
                }
                throw IOException("Not connected")
            }
        }

        val w = writer ?: throw IOException("Not connected")
        val r = reader ?: throw IOException("Not connected")

        val reqStr = request.toString()
        AppLog.d(TAG, "sendRequest: $reqStr")
        try {
            w.write(reqStr)
            w.write("\n")
            w.flush()
        } catch (e: IOException) {
            disconnect()
            throw e
        }

        val responseLine = try {
            r.readLine()
        } catch (e: IOException) {
            disconnect()
            throw e
        }

        if (responseLine == null) {
            disconnect()
            throw IOException("Connection closed")
        }

        AppLog.d(TAG, "sendRequest: response: $responseLine")

        // I4: Catch malformed JSON, treat as connection error
        val resp = try {
            JSONObject(responseLine)
        } catch (e: JSONException) {
            AppLog.w(TAG, "sendRequest: malformed JSON: $responseLine", e)
            disconnect()
            throw IOException("Malformed JSON response: $responseLine")
        }

        // M9: Verify response id matches request id
        val respId = resp.optInt("id", -1)
        if (respId != id) {
            AppLog.w(TAG, "sendRequest: id mismatch (expected $id, got $respId)")
        }

        if (consecutiveFailures >= 3) {
            onConnectionEvent?.invoke(true)
        }
        consecutiveFailures = 0
        return resp
    }

    /**
     * Tears down the connection and resets state. Called from within
     * sendRequest (which holds the lock) when an I/O error occurs.
     */
    private fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
        isReady.value = false
    }

    fun query(): JSONObject = sendRequest(JsonRequestBuilder.query())
    fun refresh(): JSONObject = sendRequest(JsonRequestBuilder.refresh())
    fun simSet(sim: Int): JSONObject = sendRequest(JsonRequestBuilder.simSet(sim))
    fun ratSet(rats: Set<RatType>): JSONObject = sendRequest(JsonRequestBuilder.ratSet(rats))
    fun gsmSet(bands: Set<Int>): JSONObject = sendRequest(JsonRequestBuilder.gsmSet(bands))
    fun wcdmaSet(bands: Set<Int>): JSONObject = sendRequest(JsonRequestBuilder.wcdmaSet(bands))
    fun lteSet(bands: Set<Int>): JSONObject = sendRequest(JsonRequestBuilder.lteSet(bands))
    fun nrSaSet(bands: Set<Int>): JSONObject = sendRequest(JsonRequestBuilder.nrSaSet(bands))
    fun nrNsaSet(bands: Set<Int>): JSONObject = sendRequest(JsonRequestBuilder.nrNsaSet(bands))
    fun nrSet(bands: Set<Int>): JSONObject = sendRequest(JsonRequestBuilder.nrSet(bands))
    fun modeSet(mode: NrMode): JSONObject = sendRequest(JsonRequestBuilder.modeSet(mode))
    fun reset(): JSONObject = sendRequest(JsonRequestBuilder.reset())
    fun verboseSet(verbose: Boolean): JSONObject = sendRequest(JsonRequestBuilder.verboseSet(verbose))

    fun lteCellLockSet(earfcn: Int, pci: Int): JSONObject =
        sendRequest(JsonRequestBuilder.lteCellLockSet(earfcn, pci))
    fun lteCellLockMultiPciSet(earfcn: Int, pciList: List<Int>): JSONObject =
        sendRequest(JsonRequestBuilder.lteCellLockMultiPciSet(earfcn, pciList))
    fun lteCellLockClear(): JSONObject = sendRequest(JsonRequestBuilder.lteCellLockClear())
    fun nrCellLockPciSet(arfcn: Int, pci: Int, scsKhz: Int, band: Int): JSONObject =
        sendRequest(JsonRequestBuilder.nrCellLockPciSet(arfcn, pci, scsKhz, band))
    fun nrCellLockArfcnSet(arfcn: Int, scsKhz: Int): JSONObject =
        sendRequest(JsonRequestBuilder.nrCellLockArfcnSet(arfcn, scsKhz))
    fun nrCellLockMultiPciSet(arfcn: Int, scsKhz: Int, band: Int, pciList: List<Int>): JSONObject =
        sendRequest(JsonRequestBuilder.nrCellLockMultiPciSet(arfcn, scsKhz, band, pciList))
    fun nrCellLockGnbSet(idBits: Int, gnbIds: List<Int>): JSONObject =
        sendRequest(JsonRequestBuilder.nrCellLockGnbSet(idBits, gnbIds))
    fun nrCellLockClear(): JSONObject = sendRequest(JsonRequestBuilder.nrCellLockClear())
    fun queryLteCellLock(): JSONObject = sendRequest(JsonRequestBuilder.queryLteCellLock())
    fun queryNrCellLock(): JSONObject = sendRequest(JsonRequestBuilder.queryNrCellLock())

    fun plmnLockSet(mcc: Int, mnc: Int): JSONObject =
        sendRequest(JsonRequestBuilder.plmnLockSet(mcc, mnc))

    fun plmnLockClear(): JSONObject = sendRequest(JsonRequestBuilder.plmnLockClear())

    @Synchronized
    fun stop() {
        AppLog.i(TAG, "stop: sending shutdown...")
        var sentShutdown = false
        try {
            writer?.let { w ->
                w.write(JsonRequestBuilder.shutdown().toString())
                w.write("\n")
                w.flush()
                sentShutdown = true
            }
            if (sentShutdown) Thread.sleep(300)
        } catch (e: Exception) {
            AppLog.w(TAG, "stop: shutdown send failed", e)
        }
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
        isReady.value = false
        // Fallback: ensure the daemon process is killed even if the
        // shutdown command didn't reach it.
        Thread {
            try {
                Shell.cmd("pkill -9 -f qcom-bandlockd 2>/dev/null; true").exec()
                AppLog.i(TAG, "stop: pkill fallback done")
            } catch (e: Exception) {
                AppLog.w(TAG, "stop: pkill fallback failed", e)
            }
        }.start()
    }

    /**
     * Synchronous version of stop() — blocks the calling thread until the
     * daemon is confirmed dead. Use from onBackPressedDispatcher to ensure
     * the daemon is killed before the activity finishes.
     */
    @Synchronized
    fun stopBlocking() {
        AppLog.i(TAG, "stopBlocking: sending shutdown...")
        var sentShutdown = false
        try {
            writer?.let { w ->
                w.write(JsonRequestBuilder.shutdown().toString())
                w.write("\n")
                w.flush()
                sentShutdown = true
            }
            if (sentShutdown) Thread.sleep(300)
        } catch (e: Exception) {
            AppLog.w(TAG, "stopBlocking: shutdown send failed", e)
        }
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
        isReady.value = false
        // Synchronous pkill — blocks until confirmed dead
        try {
            Shell.cmd("pkill -9 -f qcom-bandlockd 2>/dev/null; true").exec()
            AppLog.i(TAG, "stopBlocking: pkill done, daemon should be dead")
        } catch (e: Exception) {
            AppLog.w(TAG, "stopBlocking: pkill failed", e)
        }
    }

    fun retry() {
        AppLog.d(TAG, "retry")
        isReady.value = false
        isRootDenied.value = false
        consecutiveFailures = 0
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
        start({})
    }
}
