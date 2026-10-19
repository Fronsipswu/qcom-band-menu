package dev.qcom.bandmenu.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import dev.qcom.bandmenu.TrafficTestService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID

/** Each bounded session owns its network, attempts, sockets, deadline and browser. */
class TrafficTest private constructor(context: Context) {
    data class Run(val running: Boolean = false, val upload: Boolean = false, val mbps: Double = 0.0,
        val remaining: Int? = null, val error: String? = null, val title: String = "", val attempt: Int = 0,
        val total: Int = 0, val phase: String = "")

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("traffic_test", Context.MODE_PRIVATE)
    private val manager = app.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportDir = File(app.filesDir, "data-tests")
    private val passwords = mutableMapOf<DataTestKind, String>()
    private val lock = Any()
    private val _config = MutableStateFlow(loadSelectedConfig())
    val config: StateFlow<DataTestConfig> = _config
    private val _run = MutableStateFlow(Run())
    val run: StateFlow<Run> = _run
    private val _reports = MutableStateFlow<List<DataTestReport>>(emptyList())
    val reports: StateFlow<List<DataTestReport>> = _reports
    private val _browser = MutableStateFlow<BrowserTestTask?>(null)
    val browser: StateFlow<BrowserTestTask?> = _browser
    private var session: Session? = null
    val activeSessionId: String? get() = synchronized(lock) { session?.initialReport?.id }

    private class Session(val network: Network, val config: DataTestConfig) {
        val initialReport = DataTestReport(UUID.randomUUID().toString(), config.copy(password = ""), System.currentTimeMillis())
        lateinit var job: Job
        @Volatile var control: AttemptControl? = null
        @Volatile var attempt: AttemptState? = null
        @Volatile var reason: String? = null
        @Volatile var networkChanged = false
        @Volatile var storageError: String? = null
    }

    init {
        scope.launch {
            val saved = reportDir.listFiles().orEmpty().filter { it.extension == "json" }
                .sortedByDescending { it.name }.take(20).mapNotNull { file ->
                    runCatching { DataTestReport.fromJson(JSONObject(file.readText())) }.getOrNull()
                }
            synchronized(lock) {
                _reports.value = (_reports.value + saved).distinctBy { it.id }.sortedByDescending { it.startedAtMs }.take(20)
            }
        }
    }

    private fun defaultFor(kind: DataTestKind): DataTestConfig = DataTestConfig(kind = kind, custom = !kind.nbtc,
        timeoutSeconds = when (kind) {
            DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> 600
            DataTestKind.WEB_PAGE -> 60
            DataTestKind.STREAMING -> 300
            DataTestKind.HTTP_DOWNLOAD, DataTestKind.HTTP_UPLOAD -> 60
            else -> 5
        })

    private fun loadSelectedConfig(): DataTestConfig {
        migrateLegacyConfigs()
        val selected = enumValueOr(prefs.getString("selected_kind", null).orEmpty(), DataTestKind.PING)
        val saved = prefs.getString("config_${selected.name}", null)
        if (saved != null) return runCatching { DataTestConfig.fromJson(JSONObject(saved)) }.getOrElse { defaultFor(selected) }
        return defaultFor(selected)
    }

    private fun migrateLegacyConfigs() {
        if (prefs.getBoolean("legacy_http_migrated_v1", false)) return
        val edit = prefs.edit()
        // Preserve both endpoints before selecting a new function can hide the legacy mode.
        for ((key, kind) in listOf("get" to DataTestKind.HTTP_DOWNLOAD, "post" to DataTestKind.HTTP_UPLOAD)) {
            if (!prefs.contains(key) || prefs.contains("config_${kind.name}")) continue
            val migrated = defaultFor(kind).copy(url = prefs.getString(key, "").orEmpty(), transferBytes = 25_000_000,
                repetitions = 1, timeoutSeconds = prefs.getInt("seconds", 60).takeIf { it in 1..3600 } ?: 60)
            edit.putString("config_${kind.name}", migrated.toJson().toString())
        }
        if (!prefs.contains("selected_kind") && (prefs.contains("get") || prefs.contains("post"))) {
            val selected = if (prefs.getBoolean("upload", false)) DataTestKind.HTTP_UPLOAD else DataTestKind.HTTP_DOWNLOAD
            edit.putString("selected_kind", selected.name)
        }
        edit.putBoolean("legacy_http_migrated_v1", true).apply()
    }

    fun select(kind: DataTestKind) {
        synchronized(lock) {
            if (session != null) return
            val raw = prefs.getString("config_${kind.name}", null)
            _config.value = (raw?.let { runCatching { DataTestConfig.fromJson(JSONObject(it)) }.getOrNull() }
                ?: defaultFor(kind)).copy(password = passwords[kind].orEmpty())
            prefs.edit().putString("selected_kind", kind.name).apply()
        }
    }

    fun save(config: DataTestConfig) {
        require(config.validationErrors().isEmpty()) { config.validationErrors().joinToString(" ") }
        synchronized(lock) {
            if (session != null) return
            passwords[config.kind] = config.password
            _config.value = config
            prefs.edit().putString("selected_kind", config.kind.name)
                .putString("config_${config.kind.name}", config.toJson().toString()).apply()
        }
    }

    fun start(): Boolean = synchronized(lock) {
        if (session != null) return true
        val c = config.value
        val errors = c.validationErrors()
        if (errors.isNotEmpty()) { _run.value = Run(error = errors.first()); return false }
        val network = try { manager.activeNetwork } catch (e: Exception) {
            _run.value = Run(error = "Cannot inspect the mobile connection: ${e.message}")
            return false
        }
        if (network == null || !isCellular(network)) {
            _run.value = Run(error = "Use mobile data as the default connection; disconnect Wi-Fi and VPN first.")
            return false
        }
        val s = Session(network, c)
        session = s
        _run.value = Run(running = true, upload = c.upload, title = c.kind.title, total = c.repetitions, phase = "Preparing")
        s.job = scope.sessionJob(onNeverStarted = { finishSession(s, s.initialReport) }) { execute(s) }
        try { TrafficTestService.start(app, s.initialReport.id) } catch (e: Exception) {
            s.reason = "Could not start test service: ${e.message}"
            s.job.cancel(CancellationException(s.reason))
            _run.value = Run(error = s.reason)
            return false
        }
        s.job.start()
        true
    }

    fun stop(reason: String = "Stopped by user") {
        synchronized(lock) { session?.let { stopSession(it, reason) } }
    }

    /** Late service teardown and old notification actions cannot stop a replacement session. */
    fun stopForSession(id: String?, reason: String = "Stopped by user") {
        synchronized(lock) { session?.takeIf { it.initialReport.id == id }?.let { stopSession(it, reason) } }
    }

    private fun stopSession(s: Session, reason: String) {
        synchronized(lock) {
            if (session !== s) return
            if (s.reason == null) s.reason = reason
            s.attempt?.interrupt(s.reason ?: reason, s.networkChanged)
            s.job.cancel(CancellationException(reason))
            val control = s.control
            scope.launch { control?.cancel() } // Disconnect may block: keep it off the UI thread.
        }
    }

    private fun isCellular(network: Network): Boolean {
        val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun execute(s: Session) {
        val c = s.config
        var report = s.initialReport
        var iface = ""
        var wakeLock: PowerManager.WakeLock? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (network != s.network) networkLost() }
            override fun onLost(network: Network) { if (network == s.network) networkLost() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == s.network && !isCellular(network)) networkLost()
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == s.network && iface.isNotEmpty() && properties.interfaceName != iface) networkLost()
            }
            private fun networkLost() {
                s.networkChanged = true
                stopSession(s, "Mobile data connection changed or was lost; session stopped.")
            }
        }
        var registered = false
        try {
            currentCoroutineContext().ensureActive()
            wakeLock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QBM:DataTest").apply {
                setReferenceCounted(false)
                acquire(30_000L)
            }
            iface = manager.getLinkProperties(s.network)?.interfaceName.orEmpty()
            val defaultSubscription = SubscriptionManager.getDefaultDataSubscriptionId()
            val actualSubscriptions = if (Build.VERSION.SDK_INT >= 35) runCatching {
                manager.getNetworkCapabilities(s.network)?.subscriptionIds?.sorted()?.joinToString(", ")
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unavailable" else "unavailable on this Android version"
            report = report.copy(
                network = "Pinned cellular network ${s.network}; interface $iface; configured default-data subscription $defaultSubscription; network subscription IDs $actualSubscriptions; independent of modem SIM tab",
                environment = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            check(manager.activeNetwork == s.network && isCellular(s.network)) { "Mobile data connection changed before start" }
            s.storageError = publish(report)
            repeat(c.repetitions) { index ->
                currentCoroutineContext().ensureActive()
                wakeLock.acquire((c.effectiveTimeoutSeconds.toLong() + c.intervalSeconds + 30L) * 1000L)
                val control = AttemptControl()
                s.control = control
                val io = object : TestIo {
                    override val interfaceName = iface
                    override fun resolve(host: String): InetAddress = s.network.getByName(host)
                    override fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
                        val socket = control.track(s.network.socketFactory.createSocket())
                        socket.connect(InetSocketAddress(resolve(host), port), timeoutMs)
                        socket.soTimeout = timeoutMs
                        return socket
                    }
                    override fun openHttp(url: URL): HttpURLConnection = control.track(s.network.openConnection(url) as HttpURLConnection)
                }
                val started = System.currentTimeMillis()
                val monotonic = SystemClock.elapsedRealtime()
                val state = AttemptState(monotonic + c.effectiveTimeoutSeconds * 1000L)
                synchronized(lock) {
                    s.attempt = state
                    // Stop may have arrived between the loop's cancellation check and resource registration.
                    s.reason?.let { state.interrupt(it, s.networkChanged) }
                }
                var measurement = AttemptMeasurement()
                var outcome = AttemptOutcome.FAILED
                _run.value = Run(true, c.upload, title = c.kind.title, attempt = index + 1, total = c.repetitions, phase = "Measuring")
                val browserTask = if (c.kind in listOf(DataTestKind.WEB_PAGE, DataTestKind.STREAMING)) BrowserTestTask(c, io, control, state) else null
                if (browserTask != null) _browser.value = browserTask
                val watchdog = scope.launch {
                    while (state.active) {
                        val now = SystemClock.elapsedRealtime()
                        if (state.expire(now)) {
                            browserTask?.finish(browserTask.snapshotMeasurement(), AttemptOutcome.TIMED_OUT)
                            control.cancel()
                            break
                        }
                        synchronized(lock) {
                            if (session === s && s.attempt === state && state.active)
                                _run.value = _run.value.copy(remaining = state.remainingSeconds(now))
                        }
                        delay(250)
                    }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    if (browserTask != null) {
                        val result = browserTask.result.await()
                        measurement = result.first
                        outcome = result.second
                    } else {
                        measurement = DataTestTransport.execute(c, io, control)
                        outcome = if (measurement.error == null) AttemptOutcome.SUCCESS else AttemptOutcome.FAILED
                    }
                    state.complete(outcome, SystemClock.elapsedRealtime())
                    currentCoroutineContext().ensureActive()
                } catch (e: CancellationException) {
                    measurement = browserTask?.snapshotMeasurement() ?: measurement
                    state.interrupt(s.reason ?: "Cancelled", s.networkChanged)
                    throw e
                } catch (e: Exception) {
                    measurement = if (e is MeasurementException) e.measurement else
                        (browserTask?.snapshotMeasurement() ?: measurement).copy(error = e.message?.take(300) ?: e.javaClass.simpleName)
                    outcome = AttemptOutcome.FAILED
                } finally {
                    watchdog.cancel()
                    val completed = state.complete(outcome, SystemClock.elapsedRealtime())
                    outcome = completed.outcome
                    measurement = completed.applyTo(measurement)
                    control.cancel()
                    _browser.value = null
                    s.control = null
                    s.attempt = null
                    if (measurement.durationMs == 0.0) measurement = measurement.copy(durationMs = (SystemClock.elapsedRealtime() - monotonic).toDouble())
                    report = report.copy(attempts = report.attempts + DataTestAttempt(index + 1, started, monotonic, outcome, measurement))
                    s.storageError = publish(report)
                }
                if (index + 1 < c.repetitions) {
                    _run.value = _run.value.copy(phase = "Waiting", remaining = c.intervalSeconds,
                        mbps = measurement.throughputMbps ?: 0.0, error = measurement.error)
                    delay(c.intervalSeconds * 1000L)
                }
            }
        } catch (_: CancellationException) {
            // Preserve the interrupted attempt and all completed attempts.
        } catch (e: Exception) {
            s.reason = e.message ?: e.javaClass.simpleName
        } finally {
            try {
                if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
                s.control?.cancel()
                _browser.value = null
                finishSession(s, report)
            } finally {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            }
        }
    }

    private fun finishSession(s: Session, report: DataTestReport) {
        val ended = report.copy(endedAtMs = System.currentTimeMillis(), stopReason = s.reason)
        s.storageError = publish(ended)
        synchronized(lock) {
            if (session !== s) return
            session = null
            _run.value = Run(error = s.storageError ?: s.reason, title = s.config.kind.title,
                attempt = ended.attempts.size, total = s.config.repetitions,
                phase = if (s.reason == null) "Complete — results available" else "Stopped — results available")
            TrafficTestService.stop(app, s.initialReport.id)
        }
    }

    /** Each attempt is durable; a killed process leaves an explicitly unfinished report. */
    private fun publish(report: DataTestReport): String? {
        synchronized(lock) { _reports.value = (listOf(report) + _reports.value.filter { it.id != report.id }).take(20) }
        try {
            reportDir.mkdirs()
            val target = File(reportDir, "${report.startedAtMs}-${report.id}.json")
            val temp = File(reportDir, "${target.name}.tmp")
            temp.writeText(report.toJson().toString(2))
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            reportDir.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.name }.drop(20).forEach { it.delete() }
            return null
        } catch (e: Exception) {
            val error = "Result storage failed; export this session before closing the app: ${e.message}"
            _run.value = _run.value.copy(error = error)
            return error
        }
    }

    companion object {
        @Volatile private var instance: TrafficTest? = null
        fun get(context: Context): TrafficTest = instance ?: synchronized(this) {
            instance ?: TrafficTest(context).also { instance = it }
        }
    }
}

class BrowserTestTask internal constructor(val config: DataTestConfig, val io: TestIo, val control: AttemptControl,
    private val state: AttemptState) {
    val id: String = UUID.randomUUID().toString()
    val result = CompletableDeferred<Pair<AttemptMeasurement, AttemptOutcome>>()
    @Volatile var latest = AttemptMeasurement(contentVerified = false)
    @Volatile var snapshot: (() -> AttemptMeasurement)? = null
    @Volatile private var finishedMeasurement: AttemptMeasurement? = null
    fun snapshotMeasurement(): AttemptMeasurement = finishedMeasurement ?: runCatching { snapshot?.invoke() }.getOrNull() ?: latest
    @Synchronized fun finish(measurement: AttemptMeasurement, outcome: AttemptOutcome) {
        if (result.isCompleted) return
        val completed = state.complete(outcome, SystemClock.elapsedRealtime())
        latest = completed.applyTo(measurement)
        finishedMeasurement = latest
        result.complete(latest to completed.outcome)
    }
}
