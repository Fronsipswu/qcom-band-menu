package dev.qcom.bandmenu.network

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

enum class TestRegime(val title: String) { NBTC("NBTC · Thailand"), MIC("MIC · Japan"), CUSTOM("Custom") }
enum class MicArea(val title: String, val startHour: Int, val endHour: Int) {
    BUSINESS("Office / shopping district", 12, 18), RESIDENTIAL("Residential district", 15, 21)
}

data class MicStreamResult(
    val index: Int,
    val warmupBytes: Long,
    val warmupMs: Double,
    val bytes: Long,
    val durationMs: Double,
    val completed: Boolean,
    val httpStatus: Int? = null,
    val error: String? = null,
    val stopReason: String = ""
) {
    val rateMbps: Double? get() = if (completed && error == null && durationMs.isFinite() && durationMs > 0 && bytes >= 0)
        bytes * 8.0 / (durationMs * 1000.0) else null
    fun toJson() = JSONObject().put("index", index).put("warmupBytes", warmupBytes).put("warmupMs", warmupMs)
        .put("bytes", bytes).put("durationMs", durationMs).put("completed", completed)
        .put("httpStatus", httpStatus ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL).put("stopReason", stopReason)
        .put("rateMbps", rateMbps ?: JSONObject.NULL)
    companion object {
        fun fromJson(j: JSONObject) = MicStreamResult(j.getInt("index"), j.getLong("warmupBytes"), j.getDouble("warmupMs"),
            j.getLong("bytes"), j.getDouble("durationMs"), j.getBoolean("completed"),
            if (j.isNull("httpStatus")) null else j.getInt("httpStatus"), if (j.isNull("error")) null else j.getString("error"), j.optString("stopReason"))
    }
}

data class MicTransferResult(val streams: List<MicStreamResult> = emptyList(), val error: String? = null, val expectedStreams: Int = 3) {
    val aggregateMbps: Double? get() = if (error == null && expectedStreams > 0 && streams.size == expectedStreams &&
        streams.map { it.index }.distinct().size == expectedStreams && streams.all { it.rateMbps != null }) streams.sumOf { it.rateMbps!! } else null
    fun toJson() = JSONObject().put("expectedStreams", expectedStreams).put("streams", JSONArray(streams.map { it.toJson() }))
        .put("error", error ?: JSONObject.NULL).put("aggregateMbps", aggregateMbps ?: JSONObject.NULL)
    companion object {
        fun fromJson(j: JSONObject): MicTransferResult {
            val a = j.getJSONArray("streams")
            return MicTransferResult(List(a.length()) { MicStreamResult.fromJson(a.getJSONObject(it)) },
                if (j.isNull("error")) null else j.getString("error"), j.optInt("expectedStreams", 3))
        }
    }
}

data class MicUdpProbe(val index: Int, val sentAtNanos: Long, val rttMs: Double?, val outcome: String) {
    fun toJson() = JSONObject().put("index", index).put("sentAtNanos", sentAtNanos).put("rttMs", rttMs ?: JSONObject.NULL).put("outcome", outcome)
    companion object {
        fun fromJson(j: JSONObject) = MicUdpProbe(j.getInt("index"), j.getLong("sentAtNanos"),
            if (j.isNull("rttMs")) null else j.getDouble("rttMs"), j.getString("outcome"))
    }
}

data class MicUdpResult(val sent: Int, val received: Int, val rttsMs: List<Double>, val lost: Int, val error: String? = null,
    val probes: List<MicUdpProbe> = emptyList()) {
    val averageRttMs: Double? get() = rttsMs.takeIf { it.isNotEmpty() }?.average()
    val lossPercent: Double? get() = if (error == null && sent > 0) lost * 100.0 / sent else null
    fun toJson() = JSONObject().put("sent", sent).put("received", received).put("lost", lost).put("rttsMs", JSONArray(rttsMs))
        .put("error", error ?: JSONObject.NULL).put("averageRttMs", averageRttMs ?: JSONObject.NULL).put("lossPercent", lossPercent ?: JSONObject.NULL)
        .put("probes", JSONArray(probes.map { it.toJson() }))
    companion object {
        fun fromJson(j: JSONObject): MicUdpResult {
            val a = j.getJSONArray("rttsMs")
            return MicUdpResult(j.getInt("sent"), j.getInt("received"), List(a.length()) { a.getDouble(it) }, j.getInt("lost"),
                if (j.isNull("error")) null else j.getString("error"),
                j.optJSONArray("probes")?.let { a -> List(a.length()) { MicUdpProbe.fromJson(a.getJSONObject(it)) } }.orEmpty())
        }
    }
}

data class MicMeasurement(val download: MicTransferResult? = null, val upload: MicTransferResult? = null, val udp: MicUdpResult? = null) {
    fun toJson() = JSONObject().put("download", download?.toJson() ?: JSONObject.NULL)
        .put("upload", upload?.toJson() ?: JSONObject.NULL).put("udp", udp?.toJson() ?: JSONObject.NULL)
    companion object {
        fun fromJson(j: JSONObject) = MicMeasurement(j.optJSONObject("download")?.let(MicTransferResult::fromJson),
            j.optJSONObject("upload")?.let(MicTransferResult::fromJson), j.optJSONObject("udp")?.let(MicUdpResult::fromJson))
    }
}

/** Modem evidence is recorded only when its SIM is known to match Android's data subscription. */
data class RadioReference(val technology: String, val signal: String, val observedAtElapsedMs: Long, val simSlot: Int) {
    fun toJson() = JSONObject().put("technology", technology).put("signal", signal)
        .put("observedAtElapsedMs", observedAtElapsedMs).put("simSlot", simSlot)
    companion object {
        fun fromJson(j: JSONObject) = RadioReference(j.getString("technology"), j.getString("signal"), j.getLong("observedAtElapsedMs"), j.getInt("simSlot"))
    }
}

internal fun withinMicWindow(ms: Long, area: MicArea): Boolean {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Tokyo"))
    return t.hour in area.startHour until area.endHour
}

internal fun micLimitations(report: DataTestReport): List<String> = with(report) { buildList {
    add("MIC methodology measurements from an independent client; not the designated common MIC/FCC software or an official operator publication.")
    add("GPS/location selection is omitted. Point, campaign and radio technology are operator-declared; outdoor stationary placement and nationwide coverage require campaign review.")
    if (!config.stationaryConfirmed) add("Stationary outdoor measurement has not been confirmed.")
    if (!config.referenceServerConfirmed) add("The shared domestic-IX server, sufficient bandwidth and server-side admission control have not been confirmed.")
    add("No official server discovery, admission-control or reporting wire format is supplied in the PDFs. Configure cooperating HTTP and 16-byte UDP echo endpoints; no results are submitted automatically.")
    add("MIC has no minimum-speed pass/fail target. A point mean needs all three numeric trials; incomplete points remain visible but are excluded from distribution summaries.")
    add("MB uses decimal bytes. Upload timing includes final server acknowledgement as a conservative application measurement; byte-exact compatibility with the designated client is not established.")
    if (attempts.any { it.outcome != AttemptOutcome.CANCELLED && !withinMicWindow(it.startedAtMs, config.micArea) })
        add("Samples outside the ${config.micArea.startHour}:00–${config.micArea.endHour}:00 Asia/Tokyo window for ${config.micArea.title}.")
    if (attempts.any { it.outcome != AttemptOutcome.CANCELLED && (it.radio == null || it.radio.signal.isBlank() || it.elapsedRealtimeMs - it.radio.observedAtElapsedMs !in 0..5000) })
        add("Some samples lack current measured RAT/signal references from the active data SIM.")
    if (attempts.any { it.outcome != AttemptOutcome.CANCELLED && !it.measurement.measurementAvailable }) add("Instrument/server errors affected some observations; inspect the raw results.")
    if (endedAtMs == null) add("Session is unfinished.")
} }
