package dev.qcom.bandmenu.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import dev.qcom.bandmenu.BuildConfig
import dev.qcom.bandmenu.NetworkArchiveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Durable user-selected documents, independent of the disposable one-hour replay cache. */
class NetworkArchive(context: Context, private val diag: DiagClient) {
    data class Status(val active: Boolean = false, val busy: Boolean = false, val folder: String? = null,
        val sessionId: String? = null, val packetCapture: Boolean = false, val segments: Int = 0,
        val records: Long = 0, val dropped: Long = 0, val error: String? = null, val message: String = "Not recording")
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("network_archive", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(Status(folder = prefs.getString("folder", null)))
    val status: StateFlow<Status> = _status
    @Volatile private var writer: LogArchiveWriter? = null
    private var consumer: Job? = null
    private var captureId: String? = null
    @Volatile private var nativeDropped = 0L
    private var lastPacketNs = 0L
    @Volatile private var presentation = JSONObject()
    private val uiEntities = HashMap<Int, Map<String, String>>()
    private data class PublishedUi(val state: NetworkMonitor.State, val visible: Boolean, val resumed: Boolean)
    @Volatile private var lastUi: PublishedUi? = null
    val active get() = _status.value.active
    val packetCapture get() = active && _status.value.packetCapture

    fun selectFolder(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            _status.update { it.copy(folder = uri.toString(), error = null) }
        } catch (e: Exception) { _status.update { it.copy(error = "Cannot retain folder access: ${e.message}") } }
    }

    fun start(packets: Boolean) { scope.launch { mutex.withLock {
        if (writer != null || _status.value.busy) return@withLock
        val session = UUID.randomUUID().toString()
        _status.update { it.copy(busy = true, error = null, message = "Starting archive…", sessionId = session,
            packetCapture = packets, segments = 0, records = 0, dropped = 0) }
        try {
            val tree = Uri.parse(_status.value.folder ?: throw IOException("Choose a destination folder first"))
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val started = SystemClock.elapsedRealtimeNanos()
            lastPacketNs = started; nativeDropped = 0
            synchronized(uiEntities) { uiEntities.clear() }
            val header = JSONObject().put("type", "header").put("format", "qbm-log").put("version", 1)
                .put("sessionId", session).put("startedUtcMs", System.currentTimeMillis()).put("startedElapsedNs", started.toString())
                .put("clock", "boottime-arrival").put("packetCapture", packets).put("appVersion", BuildConfig.VERSION_NAME)
                .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
            NetworkArchiveService.owner = this@NetworkArchive
            NetworkArchiveService.start(app, session)
            writer = LogArchiveWriter(header, { name ->
                val document = DocumentsContract.createDocument(app.contentResolver, parent, "application/gzip", name)
                    ?: throw IOException("Storage provider could not create $name")
                app.contentResolver.openOutputStream(document, "w") ?: throw IOException("Storage provider cannot write $name")
            }, SystemClock::elapsedRealtimeNanos, onProgress = { p ->
                if (_status.value.sessionId == session) _status.update { it.copy(segments = p.segments, records = p.records, dropped = p.dropped + nativeDropped) }
            }, onFailure = { error ->
                if (_status.value.sessionId == session) {
                    _status.update { it.copy(error = "Archive storage failed: $error. Closed earlier segments remain usable.") }
                    stop("Storage failure")
                }
            })
            if (packets) {
                captureId = diag.capture(true).optString("captureId")
                if (captureId.isNullOrEmpty()) throw IOException("DIAG helper omitted capture identity")
            }
            _status.update { it.copy(active = true, busy = false, message = "Recording compressed segments") }
            recordState("start")
            if (packets) consumer = scope.launch {
                try {
                    while (isActive && active) { if (drainPackets() < 256) delay(75) }
                } catch (e: Exception) {
                    if (isActive) {
                        emitGap("Packet capture stopped: ${e.message}", lastPacketNs + 1, SystemClock.elapsedRealtimeNanos(), 0)
                        _status.update { it.copy(error = "Packet capture failed: ${e.message}") }
                        stop("Packet capture failed")
                    }
                }
            }
        } catch (e: Exception) {
            if (packets) runCatching { diag.capture(false) }
            writer?.finish("Start failed: ${e.message}"); writer?.awaitClosed(); writer = null
            _status.update { it.copy(active = false, busy = false, error = "Cannot start archive: ${e.message}", message = "Not recording") }
            NetworkArchiveService.stop(app, session)
        }
    } } }

    fun stop(reason: String = "Stopped by operator") { scope.launch { stopInternal(reason) } }
    fun stopForSession(id: String?, reason: String) { scope.launch {
        if (id != null && _status.value.sessionId == id) stopInternal(reason, id)
    } }
    fun stopBlocking(reason: String = "App closed") = runBlocking { stopInternal(reason) }

    private suspend fun stopInternal(reason: String, expectedSession: String? = null) = mutex.withLock {
        if (expectedSession != null && _status.value.sessionId != expectedSession) return@withLock
        val out = writer ?: return@withLock
        val session = _status.value.sessionId ?: return@withLock
        _status.update { it.copy(active = false, busy = true, message = "Finishing archive…") }
        consumer?.cancelAndJoin(); consumer = null
        if (_status.value.packetCapture) {
            try {
                diag.capture(false)
                var rounds = 0
                while (drainPackets() > 0 && ++rounds < 512) { /* drain after producer shutdown */ }
                if (rounds >= 512) emitGap("Final drain exceeded safety bound", lastPacketNs + 1, SystemClock.elapsedRealtimeNanos(), 0)
            } catch (e: Exception) {
                emitGap("Final packet drain failed: ${e.message}", lastPacketNs + 1, SystemClock.elapsedRealtimeNanos(), 0)
                _status.update { it.copy(error = it.error ?: "Final packet drain failed: ${e.message}") }
            }
        }
        out.finish(reason)
        val finished = out.awaitClosed(5000)
        writer = null
        _status.update { it.copy(busy = false, message = if (finished) "Archive saved: ${out.progress.segments} compressed segments" else "Storage close is still pending",
            error = it.error ?: out.error ?: if (!finished) "Storage provider did not finish closing; the current segment may be incomplete" else null) }
        NetworkArchiveService.stop(app, session)
    }

    private fun drainPackets(): Int {
        val response = diag.events()
        val id = response.optString("captureId")
        val events = response.getJSONArray("events")
        val first = events.optJSONObject(0)?.optString("tNs")?.toLongOrNull() ?: SystemClock.elapsedRealtimeNanos()
        if (id != captureId) {
            emitGap("Native capture restarted", lastPacketNs + 1, first, 0)
            throw IOException("DIAG capture identity changed; start a new archive")
        }
        val delta = if (response.has("totalDropped")) (response.optLong("totalDropped") - nativeDropped).coerceAtLeast(0)
            else response.optLong("dropped").coerceAtLeast(0)
        if (delta > 0) emitGap("Native packet queue overflow", lastPacketNs + 1, first, delta)
        nativeDropped += delta
        _status.update { it.copy(dropped = nativeDropped + (writer?.progress?.dropped ?: 0)) }
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val time = event.optString("tNs").toLongOrNull() ?: throw IOException("Packet has no valid native arrival timestamp")
            lastPacketNs = maxOf(lastPacketNs, time)
            writer?.append(event)
        }
        return events.length()
    }

    private fun emitGap(reason: String, start: Long, end: Long, dropped: Long) {
        writer?.append(JSONObject().put("type", "gap").put("source", "packet").put("reason", reason)
            .put("tNs", minOf(start, end).toString()).put("endNs", maxOf(start, end).toString()).put("dropped", dropped))
    }

    fun updatePresentation(value: JSONObject) {
        presentation = JSONObject(value.toString())
        recordState("presentation")
        lastUi?.let { recordUi(it.state, it.visible, it.resumed) }
    }
    fun recordState(reason: String, extra: JSONObject = JSONObject()) {
        if (!active) return
        writer?.append(extra.put("type", "state").put("reason", reason).put("tNs", SystemClock.elapsedRealtimeNanos().toString())
            .put("presentation", presentation))
    }

    fun recordPoll(sim: Int, raw: JSONObject, snapshot: NetworkSnapshot) {
        if (!active) return
        writer?.append(JSONObject().put("type", "poll").put("sim", sim).put("tNs", SystemClock.elapsedRealtimeNanos().toString())
            .put("snapshot", LogSnapshot.encode(snapshot)).put("raw", raw))
    }

    fun recordUi(state: NetworkMonitor.State, visible: Boolean, resumed: Boolean, raw: JSONObject? = null) {
        if (!active) return
        lastUi = PublishedUi(state, visible, resumed)
        val snapshot = state.snapshot
        val d = raw?.optJSONObject("d")
        val updates = snapshot?.let(LogSnapshot::updates) ?: JSONArray()
        val current = (0 until updates.length()).associate { index ->
            val update = updates.getJSONObject(index)
            update.getString("entity") to update.optString("identity")
        }
        val resets = synchronized(uiEntities) {
            val previous = uiEntities.put(state.sim, current).orEmpty()
            JSONArray(previous.keys.filter { it !in current || previous[it] != current[it] })
        }
        writer?.append(JSONObject().put("type", "ui").put("tNs", SystemClock.elapsedRealtimeNanos().toString())
            .put("sim", state.sim).put("snapshot", snapshot?.let(LogSnapshot::encode) ?: JSONObject.NULL)
            .put("operatorName", state.operatorName ?: JSONObject.NULL).put("error", state.error ?: JSONObject.NULL)
            .put("diagError", state.diagError ?: JSONObject.NULL).put("frozen", state.frozen).put("visible", visible).put("resumed", resumed)
            .put("diagEnabled", state.diagEnabled).put("presentation", presentation)
            .put("snapshotFresh", raw != null).put("snapshotTakenAtElapsedMs", snapshot?.takenAtElapsedMs ?: JSONObject.NULL)
            .put("updates", updates).put("resets", resets)
            .put("aggregates", d?.optJSONArray("aggregates") ?: JSONArray()).put("windowMs", d?.optLong("ms") ?: JSONObject.NULL)
            .put("raw", raw ?: JSONObject.NULL))
    }
}
