package dev.qcom.bandmenu.network

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One location trial. Failure of one item does not erase another item or suppress the next one. */
internal fun runMicPoint(c: DataTestConfig, io: TestIo, control: AttemptControl,
    onPhase: (String) -> Unit = {},
    execute: (DataTestConfig, TestIo, AttemptControl) -> AttemptMeasurement = MicDataTransport::execute
): AttemptMeasurement {
    val started = System.nanoTime()
    var combined = MicMeasurement()
    var bytes = 0L
    val errors = mutableListOf<String>()
    fun snapshot() = AttemptMeasurement(bytes = bytes, durationMs = (System.nanoTime() - started) / 1e6,
        mic = combined, rttMs = combined.udp?.averageRttMs, error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        detail = "Sequential download, upload, UDP; failed items retained; each direction assessed independently")
    for (kind in listOf(DataTestKind.PARALLEL_DOWNLOAD, DataTestKind.PARALLEL_UPLOAD, DataTestKind.UDP_LATENCY)) {
        if (control.isCancelled) throw MeasurementException(snapshot().copy(error = "Point sequence interrupted"))
        onPhase(kind.title)
        val phaseConfig = c.copy(kind = kind)
        val phaseControl = control.track(AttemptControl())
        // 0 = active, 1 = finished on time, 2 = deadline won. Cleanup cannot change the winner.
        val terminal = AtomicInteger(0)
        val deadline = System.nanoTime() + phaseConfig.effectiveTimeoutSeconds * 1_000_000_000L
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "MIC-phase-deadline").apply { isDaemon = true } }
        val alarm = timer.schedule({ if (terminal.compareAndSet(0, 2)) phaseControl.cancel() }, phaseConfig.effectiveTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        var result = try { execute(phaseConfig, io, phaseControl) }
        catch (e: Exception) {
            if (e is MeasurementException) e.measurement else AttemptMeasurement(error = e.message ?: e.javaClass.simpleName)
        } finally {
            terminal.compareAndSet(0, if (System.nanoTime() >= deadline) 2 else 1)
            alarm.cancel(false)
            timer.shutdownNow()
            phaseControl.cancel()
        }
        if (terminal.get() == 2) {
            val error = "Phase timed out after ${phaseConfig.effectiveTimeoutSeconds} seconds"
            result = result.copy(error = error, mic = result.mic?.let { mic -> mic.copy(
                download = mic.download?.copy(error = error), upload = mic.upload?.copy(error = error), udp = mic.udp?.copy(error = error)) })
        }
        bytes += result.bytes
        result.error?.let { errors += "${kind.title}: $it" }
        combined = when (kind) {
            DataTestKind.PARALLEL_DOWNLOAD -> combined.copy(download = result.mic?.download ?:
                MicTransferResult(error = result.error ?: "No download measurement", expectedStreams = c.effectiveParallelConnections))
            DataTestKind.PARALLEL_UPLOAD -> combined.copy(upload = result.mic?.upload ?:
                MicTransferResult(error = result.error ?: "No upload measurement", expectedStreams = c.effectiveParallelConnections))
            else -> combined.copy(udp = result.mic?.udp ?: MicUdpResult(0, 0, emptyList(), 0, result.error ?: "No UDP measurement"))
        }
        if (control.isCancelled) throw MeasurementException(snapshot().copy(error = "Point sequence interrupted"))
    }
    return snapshot()
}
