package dev.qcom.bandmenu.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class DataTestKind(val title: String, val nbtc: Boolean = true, val mic: Boolean = false) {
    PING("ICMP latency"), FTP_DOWNLOAD("FTP download"), FTP_UPLOAD("FTP upload"),
    WEB_PAGE("Webpage loading"), STREAMING("YouTube streaming"),
    HTTP_DOWNLOAD("HTTP download", false), HTTP_UPLOAD("HTTP upload", false),
    MIC_POINT("Point sequence: DL + UL + UDP", false, true),
    PARALLEL_DOWNLOAD("Parallel HTTP download", false, true), PARALLEL_UPLOAD("Parallel HTTP upload", false, true),
    UDP_LATENCY("UDP latency / loss", false, true)
}

/** Explicit declared radio profile; selecting this never changes the modem's RAT/bands. */
enum class NbtcProfile(val title: String, val is5g: Boolean = false, val band2600: Boolean = false) {
    UMTS("3G"), LTE("4G"), NR_NSA("5G NSA, other bands", true),
    NR_NSA_2600("5G NSA, 2600 MHz", true, true), NR_SA("5G SA, other bands", true),
    NR_SA_2600("5G SA, 2600 MHz", true, true);

    val rttLimitMs get() = when (this) { UMTS -> 500.0; NR_SA_2600 -> 110.0; else -> 150.0 }
    val webTimeoutSeconds get() = when (this) { UMTS -> 180; NR_SA_2600 -> 25; else -> 60 }
    val videoHeight get() = when (this) { UMTS -> 360; NR_SA_2600 -> 1080; else -> 720 }
    val streamingTarget get() = when (this) { UMTS -> 80.0; NR_SA_2600 -> 90.0; else -> 85.0 }
    fun fileBytes(upload: Boolean): Long = when {
        this == UMTS -> if (upload) 2_000_000L else 15_000_000L
        band2600 -> if (upload) 100_000_000L else 500_000_000L
        else -> if (upload) 4_000_000L else 30_000_000L
    }
    fun bitrateMbps(upload: Boolean): Double = when {
        this == UMTS -> if (upload) 0.3 else 0.75
        band2600 -> if (upload) 1.25 else 5.0
        else -> if (upload) 0.5 else 2.5
    }
}

enum class SamplingArea(val title: String, val samples: Int) {
    ROUTE("Regional / Bangkok route", 120), COMMUNITY("Community district", 60),
    SITE("Surveillance site / rail line", 40)
}

data class DataTestConfig(
    val kind: DataTestKind = DataTestKind.PING,
    val profile: NbtcProfile = NbtcProfile.LTE,
    val custom: Boolean = false,
    val repetitions: Int = 120,
    val intervalSeconds: Int = 1,
    val timeoutSeconds: Int = 5,
    val host: String = "",
    val port: Int = 21,
    val username: String = "anonymous",
    /** Memory only: never serialized to preferences or reports. */
    val password: String = "",
    val path: String = "",
    val url: String = "",
    val transferBytes: Long = 30_000_000,
    val pingPayloadBytes: Int = 32,
    val videoId: String = "",
    val videoHeight: Int = 720,
    val videoMinimumSeconds: Int = 180,
    val area: SamplingArea = SamplingArea.ROUTE,
    val operator: String = "",
    val locationLabel: String = "",
    val sessionLabel: String = "",
    /** Operator confirms that this is the supplied NBTC server/content, not a public speedtest. */
    val referenceServerConfirmed: Boolean = false,
    val regime: TestRegime = TestRegime.NBTC,
    val uploadUrl: String = "",
    val parallelConnections: Int = 3,
    val warmupSeconds: Int = 5,
    val warmupBytes: Long = 2_500_000,
    val measureSeconds: Int = 20,
    val measureBytes: Long = 20_000_000,
    val udpCount: Int = 60,
    val udpIntervalMs: Int = 500,
    val udpTimeoutMs: Int = 2000,
    val micArea: MicArea = MicArea.BUSINESS,
    val campaignId: String = "",
    val pointId: String = "",
    val declaredTechnology: String = "LTE",
    val stationaryConfirmed: Boolean = false
) {
    // Preserve old callers/reports that used the custom flag and had no regulator field.
    val effectiveRegime get() = if (custom || (regime == TestRegime.NBTC && !kind.nbtc)) TestRegime.CUSTOM else regime
    val isNbtc get() = effectiveRegime == TestRegime.NBTC && kind.nbtc
    val isMic get() = effectiveRegime == TestRegime.MIC && kind.mic
    val upload get() = kind in listOf(DataTestKind.FTP_UPLOAD, DataTestKind.HTTP_UPLOAD, DataTestKind.PARALLEL_UPLOAD)
    val effectiveRepetitions get() = if (isMic) 3 else repetitions
    val effectiveParallelConnections get() = if (isMic) 3 else parallelConnections
    val effectiveWarmupSeconds get() = if (isMic) 5 else warmupSeconds
    val effectiveWarmupBytes get() = if (isMic) 2_500_000L else warmupBytes
    val effectiveMeasureSeconds get() = if (isMic) 20 else measureSeconds
    val effectiveMeasureBytes get() = if (isMic) 20_000_000L else measureBytes
    val effectiveUdpCount get() = if (isMic) 60 else udpCount
    val effectiveUdpIntervalMs get() = if (isMic) 500 else udpIntervalMs
    val effectiveUdpTimeoutMs get() = if (isMic) 2000 else udpTimeoutMs
    val udpBudgetSeconds get() = ((effectiveUdpCount - 1L) * effectiveUdpIntervalMs + effectiveUdpTimeoutMs + 999) / 1000 + 5
    val effectiveTimeoutSeconds get() = when {
        kind == DataTestKind.MIC_POINT -> (2L * timeoutSeconds + udpBudgetSeconds + 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        kind == DataTestKind.UDP_LATENCY -> udpBudgetSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        !isNbtc -> timeoutSeconds
        else -> when (kind) {
        DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> 600
        DataTestKind.WEB_PAGE -> profile.webTimeoutSeconds
        DataTestKind.STREAMING -> 300
        else -> timeoutSeconds // ICMP timeout is an instrument setting, not the RTT target.
        }
    }
    val effectiveTransferBytes get() = if (!isNbtc) transferBytes else when (kind) {
        DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> profile.fileBytes(upload)
        DataTestKind.WEB_PAGE -> 3_500_000L
        else -> transferBytes
    }
    val effectivePingPayloadBytes get() = if (isNbtc) 32 else pingPayloadBytes
    val effectiveVideoHeight get() = if (isNbtc) profile.videoHeight else videoHeight
    val effectiveVideoMinimumSeconds get() = if (isNbtc) 180 else videoMinimumSeconds
    val minimumSamples get() = if (isMic) 3 else if (kind == DataTestKind.STREAMING) 100 else area.samples
    val expectedTransferBytes get() = when (kind) {
        DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD, DataTestKind.HTTP_UPLOAD,
        DataTestKind.HTTP_DOWNLOAD, DataTestKind.WEB_PAGE -> effectiveTransferBytes * repetitions
        DataTestKind.PARALLEL_DOWNLOAD, DataTestKind.PARALLEL_UPLOAD, DataTestKind.MIC_POINT ->
            (effectiveWarmupBytes + 1 + effectiveMeasureBytes) * effectiveParallelConnections * effectiveRepetitions *
                (if (kind == DataTestKind.MIC_POINT) 2 else 1)
        else -> 0L
    }

    fun validationErrors(): List<String> = buildList {
        if (repetitions !in 1..10000) add("Repetitions must be 1–10,000.")
        if (intervalSeconds !in 0..3600) add("Interval must be 0–3,600 seconds.")
        if (timeoutSeconds !in 1..3600) add("Timeout must be 1–3,600 seconds.")
        if (effectiveRegime == TestRegime.MIC && !kind.mic) add("This test function has no MIC measurement profile.")
        if (listOf(host, username, password, path).any { '\r' in it || '\n' in it || '\u0000' in it })
            add("Host, credentials and FTP path must not contain control characters.")
        when (kind) {
            DataTestKind.PING -> {
                if (!validHost(host)) add("Enter a hostname or IP address, without a scheme or port.")
                if (isNbtc && !isLiteralIp(host)) add("NBTC ICMP requires the reference server's literal IP address.")
                if (effectivePingPayloadBytes !in 1..65500) add("ICMP payload must be 1–65,500 bytes.")
            }
            DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> {
                if (!validHost(host)) add("Enter the FTP server hostname or IP, without ftp://.")
                if (isNbtc && !isLiteralIp(host)) add("NBTC FTP requires the reference server's literal IP address.")
                if (port !in 1..65535) add("FTP port must be 1–65,535.")
                if (username.isBlank()) add("Enter an FTP username.")
                if (!path.startsWith('/')) add("Enter an absolute remote file path (download) or directory (upload).")
            }
            DataTestKind.WEB_PAGE, DataTestKind.HTTP_DOWNLOAD, DataTestKind.HTTP_UPLOAD -> {
                if (!validHttpUrl(url)) add("Enter an HTTP(S) URL without embedded credentials or a fragment.")
                if (kind == DataTestKind.WEB_PAGE && isNbtc && !url.startsWith("http://"))
                    add("The NBTC reference webpage uses HTTP. Use Custom for HTTPS pages.")
            }
            DataTestKind.STREAMING -> {
                if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) add("Enter the 11-character YouTube video ID.")
                if (effectiveVideoHeight !in listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)) add("Select a valid video height.")
                if (effectiveVideoMinimumSeconds !in 1..3600) add("Minimum video length must be 1–3,600 seconds.")
            }
            DataTestKind.PARALLEL_DOWNLOAD, DataTestKind.PARALLEL_UPLOAD, DataTestKind.UDP_LATENCY, DataTestKind.MIC_POINT -> {
                if (kind in listOf(DataTestKind.PARALLEL_DOWNLOAD, DataTestKind.MIC_POINT) && !validHttpUrl(url)) add("Enter a valid download URL without embedded credentials.")
                if (kind in listOf(DataTestKind.PARALLEL_UPLOAD, DataTestKind.MIC_POINT) && !validHttpUrl(uploadUrl)) add("Enter a valid HTTP POST upload URL without embedded credentials.")
                if (kind in listOf(DataTestKind.UDP_LATENCY, DataTestKind.MIC_POINT)) {
                    if (!validHost(host)) add("Enter the UDP echo server hostname or IP address.")
                    if (port !in 1..65535) add("UDP port must be 1–65,535.")
                }
                if (effectiveParallelConnections !in 1..8) add("Parallel connections must be 1–8.")
                if (effectiveWarmupSeconds !in 0..60 || effectiveWarmupBytes !in 0..100_000_000L) add("Warm-up must be 0–60 seconds and 0–100,000,000 bytes.")
                if (effectiveMeasureSeconds !in 1..300 || effectiveMeasureBytes !in 1..1_000_000_000L) add("Measurement window must be 1–300 seconds and 1–1,000,000,000 bytes.")
                if (kind != DataTestKind.UDP_LATENCY && timeoutSeconds <= effectiveWarmupSeconds + effectiveMeasureSeconds)
                    add("Attempt timeout must exceed warm-up plus measurement duration to allow connection setup and acknowledgement.")
                if (effectiveUdpCount !in 1..1000 || effectiveUdpIntervalMs !in 10..10000 || effectiveUdpTimeoutMs !in 1..10000)
                    add("UDP settings require 1–1,000 probes, 10–10,000 ms intervals and 1–10,000 ms reply deadlines.")
                if (isMic && (campaignId.isBlank() || pointId.isBlank() || operator.isBlank() || declaredTechnology.isBlank()))
                    add("MIC measurements need campaign ID, point ID, operator and declared radio technology.")
            }
        }
        if (kind in listOf(DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD, DataTestKind.WEB_PAGE,
                DataTestKind.HTTP_DOWNLOAD, DataTestKind.HTTP_UPLOAD) && effectiveTransferBytes !in 1..2_000_000_000L)
            add("Transfer / expected page size must be 1–2,000,000,000 bytes.")
    }

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("profile", profile.name)
        .put("custom", custom).put("repetitions", repetitions).put("intervalSeconds", intervalSeconds)
        .put("timeoutSeconds", timeoutSeconds).put("host", host).put("port", port).put("username", username)
        .put("path", path).put("url", url).put("transferBytes", transferBytes).put("pingPayloadBytes", pingPayloadBytes)
        .put("videoId", videoId).put("videoHeight", videoHeight).put("videoMinimumSeconds", videoMinimumSeconds)
        .put("area", area.name).put("operator", operator).put("locationLabel", locationLabel)
        .put("sessionLabel", sessionLabel).put("referenceServerConfirmed", referenceServerConfirmed)
        .put("regime", effectiveRegime.name).put("uploadUrl", uploadUrl).put("parallelConnections", parallelConnections)
        .put("warmupSeconds", warmupSeconds).put("warmupBytes", warmupBytes).put("measureSeconds", measureSeconds).put("measureBytes", measureBytes)
        .put("udpCount", udpCount).put("udpIntervalMs", udpIntervalMs).put("udpTimeoutMs", udpTimeoutMs).put("micArea", micArea.name)
        .put("campaignId", campaignId).put("pointId", pointId).put("declaredTechnology", declaredTechnology).put("stationaryConfirmed", stationaryConfirmed)

    companion object {
        fun fromJson(j: JSONObject): DataTestConfig = DataTestConfig(
            kind = enumValueOr(j.optString("kind"), DataTestKind.PING),
            profile = enumValueOr(j.optString("profile"), NbtcProfile.LTE), custom = j.optBoolean("custom"),
            repetitions = j.optInt("repetitions", 120), intervalSeconds = j.optInt("intervalSeconds", 1),
            timeoutSeconds = j.optInt("timeoutSeconds", 5), host = j.optString("host"), port = j.optInt("port", 21),
            username = j.optString("username", "anonymous"), path = j.optString("path"), url = j.optString("url"),
            transferBytes = j.optLong("transferBytes", 30_000_000), pingPayloadBytes = j.optInt("pingPayloadBytes", 32),
            videoId = j.optString("videoId"), videoHeight = j.optInt("videoHeight", 720),
            videoMinimumSeconds = j.optInt("videoMinimumSeconds", 180), area = enumValueOr(j.optString("area"), SamplingArea.ROUTE),
            operator = j.optString("operator"), locationLabel = j.optString("locationLabel"), sessionLabel = j.optString("sessionLabel"),
            referenceServerConfirmed = j.optBoolean("referenceServerConfirmed"),
            regime = if (j.has("regime")) enumValueOr(j.optString("regime"), TestRegime.CUSTOM) else TestRegime.NBTC,
            uploadUrl = j.optString("uploadUrl"), parallelConnections = j.optInt("parallelConnections", 3),
            warmupSeconds = j.optInt("warmupSeconds", 5), warmupBytes = j.optLong("warmupBytes", 2_500_000),
            measureSeconds = j.optInt("measureSeconds", 20), measureBytes = j.optLong("measureBytes", 20_000_000),
            udpCount = j.optInt("udpCount", 60), udpIntervalMs = j.optInt("udpIntervalMs", 500), udpTimeoutMs = j.optInt("udpTimeoutMs", 2000),
            micArea = enumValueOr(j.optString("micArea"), MicArea.BUSINESS), campaignId = j.optString("campaignId"), pointId = j.optString("pointId"),
            declaredTechnology = j.optString("declaredTechnology", "LTE"), stationaryConfirmed = j.optBoolean("stationaryConfirmed"))
    }
}

internal inline fun <reified T : Enum<T>> enumValueOr(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

private fun validHost(host: String) = host.isNotBlank() && host.none { it.isWhitespace() || it in "/\\@?#" } &&
    !host.startsWith('-') && (':' !in host || isLiteralIp(host))

internal fun isLiteralIp(host: String): Boolean {
    if (':' in host) return Regex("[0-9a-fA-F:]+(?:%[A-Za-z0-9_.-]+)?").matches(host) &&
        runCatching { java.net.InetAddress.getByName(host) is java.net.Inet6Address }.getOrDefault(false)
    val parts = host.split('.')
    return parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }
}

internal fun validHttpUrl(value: String): Boolean = runCatching {
    val u = URI(value)
    u.scheme in listOf("http", "https") && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.fragment == null &&
        (u.port == -1 || u.port in 1..65535)
}.getOrDefault(false)

enum class AttemptOutcome { SUCCESS, FAILED, TIMED_OUT, CANCELLED }

data class AttemptMeasurement(
    val bytes: Long = 0,
    val durationMs: Double = 0.0,
    val throughputMbps: Double? = null,
    val rttMs: Double? = null,
    val httpStatus: Int? = null,
    val playbackStarted: Boolean = false,
    val playbackCompleted: Boolean = false,
    val mediaDurationSeconds: Double? = null,
    val observedQuality: String? = null,
    val freezeDurationMs: Long = 0,
    val playbackDurationMs: Long = 0,
    val contentVerified: Boolean = true,
    val error: String? = null,
    val detail: String? = null,
    val serviceAccessed: Boolean = false,
    val startupDelayMs: Double? = null,
    /** False for unsupported/broken instrumentation; network failures remain valid observations. */
    val measurementAvailable: Boolean = true,
    val mic: MicMeasurement? = null
) {
    fun toJson(): JSONObject = JSONObject().put("bytes", bytes).put("durationMs", durationMs)
        .put("throughputMbps", throughputMbps ?: JSONObject.NULL).put("rttMs", rttMs ?: JSONObject.NULL)
        .put("httpStatus", httpStatus ?: JSONObject.NULL).put("playbackStarted", playbackStarted)
        .put("playbackCompleted", playbackCompleted).put("mediaDurationSeconds", mediaDurationSeconds ?: JSONObject.NULL)
        .put("observedQuality", observedQuality ?: JSONObject.NULL).put("freezeDurationMs", freezeDurationMs)
        .put("playbackDurationMs", playbackDurationMs).put("contentVerified", contentVerified)
        .put("error", error ?: JSONObject.NULL).put("detail", detail ?: JSONObject.NULL)
        .put("serviceAccessed", serviceAccessed).put("startupDelayMs", startupDelayMs ?: JSONObject.NULL)
        .put("measurementAvailable", measurementAvailable)
        .put("mic", mic?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(j: JSONObject) = AttemptMeasurement(j.optLong("bytes"), j.optDouble("durationMs", 0.0),
            j.numberOrNull("throughputMbps"), j.numberOrNull("rttMs"), if (j.isNull("httpStatus")) null else j.getInt("httpStatus"),
            j.optBoolean("playbackStarted"), j.optBoolean("playbackCompleted"), j.numberOrNull("mediaDurationSeconds"),
            j.stringOrNull("observedQuality"), j.optLong("freezeDurationMs"), j.optLong("playbackDurationMs"),
            j.optBoolean("contentVerified", true), j.stringOrNull("error"), j.stringOrNull("detail"),
            j.optBoolean("serviceAccessed"), j.numberOrNull("startupDelayMs"), j.optBoolean("measurementAvailable", true),
            j.optJSONObject("mic")?.let(MicMeasurement::fromJson))
    }
}

private fun JSONObject.numberOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

data class DataTestAttempt(val number: Int, val startedAtMs: Long, val elapsedRealtimeMs: Long,
    val outcome: AttemptOutcome, val measurement: AttemptMeasurement, val radio: RadioReference? = null) {
    fun toJson() = JSONObject().put("number", number).put("startedAtMs", startedAtMs)
        .put("elapsedRealtimeMs", elapsedRealtimeMs).put("outcome", outcome.name).put("measurement", measurement.toJson())
        .put("radio", radio?.toJson() ?: JSONObject.NULL)
    companion object {
        fun fromJson(j: JSONObject) = DataTestAttempt(j.getInt("number"), j.getLong("startedAtMs"),
            j.getLong("elapsedRealtimeMs"), AttemptOutcome.valueOf(j.getString("outcome")), AttemptMeasurement.fromJson(j.getJSONObject("measurement")),
            j.optJSONObject("radio")?.let(RadioReference::fromJson))
    }
}

data class DataTestReport(
    val id: String,
    val config: DataTestConfig,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val network: String = "",
    val attempts: List<DataTestAttempt> = emptyList(),
    val stopReason: String? = null,
    val environment: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("schemaVersion", 2).put("id", id).put("config", config.toJson())
        .put("reference", when (config.effectiveRegime) {
            TestRegime.NBTC -> "NBTC standard 2565 + amendment no.2/2567; measurement guidelines 2567, effective 2025-01-01; reviewed 2026-10-20"
            TestRegime.MIC -> "MIC Japan July 2015 mobile effective-speed guideline 001056443.pdf; study report 000371343.pdf appendix 3; independent methodology implementation; reviewed 2026-10-20"
            TestRegime.CUSTOM -> "Custom diagnostic configuration"
        })
        .put("timeZone", if (config.isMic) "Asia/Tokyo" else "Asia/Bangkok").put("byteConvention", "decimal MB; Mbps = 1000000 bits/s")
        .put("effectiveParameters", JSONObject().put("timeoutSeconds", config.effectiveTimeoutSeconds)
            .put("transferBytes", config.effectiveTransferBytes).put("pingPayloadBytes", config.effectivePingPayloadBytes)
            .put("videoHeight", config.effectiveVideoHeight).put("videoMinimumSeconds", config.effectiveVideoMinimumSeconds)
            .put("parallelSessions", if (config.kind.mic) config.effectiveParallelConnections else 1).put("minimumSamplesForArea", config.minimumSamples)
            .put("repetitions", config.effectiveRepetitions).put("warmupSeconds", config.effectiveWarmupSeconds)
            .put("warmupBytes", config.effectiveWarmupBytes).put("measureSeconds", config.effectiveMeasureSeconds).put("measureBytes", config.effectiveMeasureBytes)
            .put("udpCount", config.effectiveUdpCount).put("udpIntervalMs", config.effectiveUdpIntervalMs).put("udpTimeoutMs", config.effectiveUdpTimeoutMs))
        .put("startedAtMs", startedAtMs).put("endedAtMs", endedAtMs ?: JSONObject.NULL).put("network", network)
        .put("environment", environment).put("stopReason", stopReason ?: JSONObject.NULL)
        .put("attempts", JSONArray(attempts.map { it.toJson() }))
        .put("assessment", JSONArray(assess().map { JSONObject().put("name", it.name).put("value", it.value ?: JSONObject.NULL)
            .put("unit", it.unit).put("target", it.target).put("status", it.status) }))
        .put("limitations", JSONArray(limitations()))

    fun toCsv(): String {
        val header = listOf("session_id", "function", "mode", "declared_profile", "network", "operator", "location", "session_label",
            "attempt", "started_utc", "elapsed_realtime_ms", "outcome", "bytes", "duration_ms", "mbps", "rtt_ms", "http_status",
            "playback_started", "playback_completed", "media_duration_seconds", "quality", "freeze_ms", "playback_ms", "content_verified", "error", "detail",
            "timeout_seconds", "expected_bytes", "ping_payload_bytes", "interval_seconds", "requested_repetitions", "endpoint", "limitations",
            "service_accessed", "startup_delay_ms", "measurement_available", "campaign_id", "point_id", "mic_area", "declared_technology",
            "upload_endpoint", "udp_endpoint", "download_mbps", "upload_mbps", "udp_sent", "udp_received", "udp_lost", "udp_mean_rtt_ms", "radio_reference_json", "mic_measurement_json")
        val endpoint = when (config.kind) {
            DataTestKind.PING -> config.host
            DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> "${config.host}:${config.port}${config.path}"
            DataTestKind.STREAMING -> "https://www.youtube.com/watch?v=${config.videoId}"
            DataTestKind.UDP_LATENCY -> "${config.host}:${config.port}"
            DataTestKind.PARALLEL_UPLOAD -> config.uploadUrl
            else -> config.url
        }
        return (listOf(header) + attempts.map { a -> with(a.measurement) {
            listOf(id, config.kind.name, config.effectiveRegime.name, if (config.isMic) config.declaredTechnology else config.profile.name, network,
                config.operator, config.locationLabel, config.sessionLabel, a.number, Instant.ofEpochMilli(a.startedAtMs),
                a.elapsedRealtimeMs, a.outcome.name, bytes, durationMs, throughputMbps, rttMs, httpStatus, playbackStarted,
                playbackCompleted, mediaDurationSeconds, observedQuality, freezeDurationMs, playbackDurationMs,
                contentVerified, error, detail, config.effectiveTimeoutSeconds, config.effectiveTransferBytes,
                config.effectivePingPayloadBytes, config.intervalSeconds, config.effectiveRepetitions, endpoint, limitations().joinToString("; "),
                serviceAccessed, startupDelayMs, measurementAvailable, config.campaignId, config.pointId, config.micArea.name, config.declaredTechnology,
                config.uploadUrl, "${config.host}:${config.port}", mic?.download?.aggregateMbps, mic?.upload?.aggregateMbps,
                mic?.udp?.sent, mic?.udp?.received, mic?.udp?.lost, mic?.udp?.averageRttMs, a.radio?.toJson()?.toString(), mic?.toJson()?.toString())
        }}).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { csvCell(it) } }
    }

    fun limitations(): List<String> = if (config.isMic) micLimitations(this) else buildList {
        add("Device session screening only; not an operator compliance certification. RAT/band profile is operator-declared.")
        add("No GPS, route coverage, operator-wide outage statistics or quarterly aggregation.")
        if (!config.isNbtc) add("Custom configuration: no NBTC verdict.")
        if (config.isNbtc && !config.referenceServerConfirmed) add("NBTC server/content has not been confirmed.")
        if (config.isNbtc && attempts.count { it.outcome != AttemptOutcome.CANCELLED } < config.minimumSamples)
            add("Insufficient samples: ${config.minimumSamples} required for the selected area/profile.")
        if (config.isNbtc && attempts.any { !withinNbtcWindow(it.startedAtMs) }) add("Samples outside Monday–Friday 08:00–20:00 Asia/Bangkok.")
        if (config.kind == DataTestKind.FTP_DOWNLOAD || config.kind == DataTestKind.FTP_UPLOAD)
            add("FTP Method B uses application socket timing; packet-level ACK/FIN timing requires external capture. MB uses decimal bytes; confirm reference file size.")
        if (config.kind == DataTestKind.STREAMING)
            add("YouTube quality is manually verified; automatic resolution control is unavailable. Keep the player visible and avoid other traffic during measurement.")
        if (config.isNbtc && config.profile == NbtcProfile.UMTS)
            add("2024 guideline: 3G measurement is exempt where the operator provides a higher technology in the prescribed areas.")
        if (attempts.any { it.outcome != AttemptOutcome.CANCELLED &&
                (it.outcome == AttemptOutcome.SUCCESS || (config.kind == DataTestKind.STREAMING && it.measurement.playbackStarted)) && !it.measurement.contentVerified })
            add("Some content sizes or video resolutions could not be verified; corresponding results are unverified.")
        if (attempts.any { it.outcome != AttemptOutcome.CANCELLED && !it.measurement.measurementAvailable })
            add("Some attempts could not be measured by the instrument; review their errors before interpreting the numerical results.")
        if (endedAtMs == null) add("Session has not finished.")
    }

    companion object {
        fun fromJson(j: JSONObject): DataTestReport {
            val a = j.getJSONArray("attempts")
            return DataTestReport(j.getString("id"), DataTestConfig.fromJson(j.getJSONObject("config")),
                j.getLong("startedAtMs"), if (j.isNull("endedAtMs")) null else j.getLong("endedAtMs"), j.optString("network"),
                List(a.length()) { DataTestAttempt.fromJson(a.getJSONObject(it)) }, j.stringOrNull("stopReason"), j.optString("environment"))
        }
    }
}

internal fun csvCell(value: Any?): String {
    var s = value?.toString().orEmpty()
    // Prevent spreadsheet formula execution when opening user-provided labels/endpoints.
    if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) s = "'$s"
    return "\"${s.replace("\"", "\"\"")}\""
}

internal fun withinNbtcWindow(ms: Long): Boolean {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Bangkok"))
    return t.dayOfWeek.value <= 5 && t.hour in 8..19
}

data class DataTestMetric(val name: String, val value: Double?, val unit: String, val target: String, val status: String)

/** Denominators are intentional: FTP bitrate ratios use successful transfers; failure ratios use attempted tests. */
fun DataTestReport.assess(): List<DataTestMetric> {
    if (config.isMic) return micSessionMetrics(this)
    val eligible = attempts.filter { it.outcome != AttemptOutcome.CANCELLED }
    val successes = eligible.filter { it.outcome == AttemptOutcome.SUCCESS }
    val c = config
    val p = c.profile
    fun metric(name: String, value: Double?, unit: String, target: Double, atLeast: Boolean = true): DataTestMetric {
        val unverified = eligible.any { !it.measurement.measurementAvailable ||
            ((it.outcome == AttemptOutcome.SUCCESS || (c.kind == DataTestKind.STREAMING && it.measurement.playbackStarted)) && !it.measurement.contentVerified) }
        val status = when {
            value == null -> "NO DATA"
            !c.isNbtc -> "CUSTOM"
            unverified -> "UNVERIFIED"
            (if (atLeast) value >= target else value <= target) -> "MEETS SESSION TARGET"
            else -> "BELOW SESSION TARGET"
        }
        return DataTestMetric(name, value, unit, (if (atLeast) ">= " else "<= ") + target + " " + unit, status)
    }
    fun ratio(n: Int, d: Int): Double? = if (d == 0) null else n * 100.0 / d
    if (!c.isNbtc) {
        fun custom(name: String, value: Double?, unit: String) = DataTestMetric(name, value, unit, "", "CUSTOM")
        if (c.kind.mic) {
            val udp = eligible.mapNotNull { it.measurement.mic?.udp }.filter { it.error == null }
            return listOf(custom("Completed rounds", ratio(successes.size, eligible.size), "%"),
                custom("Mean download speed (valid trials)", eligible.mapNotNull { it.measurement.mic?.download?.aggregateMbps }.averageOrNull(), "Mbps"),
                custom("Mean upload speed (valid trials)", eligible.mapNotNull { it.measurement.mic?.upload?.aggregateMbps }.averageOrNull(), "Mbps"),
                custom("UDP mean RTT (replies)", udp.flatMap { it.rttsMs }.averageOrNull(), "ms"),
                custom("UDP loss", ratio(udp.sumOf { it.lost }, udp.sumOf { it.sent }), "%"))
        }
        return when (c.kind) {
            DataTestKind.PING -> listOf(
                custom("Replies", ratio(successes.size, eligible.size), "%"),
                custom("Mean RTT (replies)", successes.mapNotNull { it.measurement.rttMs }.averageOrNull(), "ms"),
                custom("Packet loss", ratio(eligible.size - successes.size, eligible.size), "%"))
            DataTestKind.WEB_PAGE -> listOf(
                custom("Complete webpage loads", ratio(successes.size, eligible.size), "%"),
                custom("Mean page load time (complete pages)", successes.map { it.measurement.durationMs }.averageOrNull(), "ms"))
            DataTestKind.STREAMING -> {
                val started = eligible.filter { it.measurement.playbackStarted }
                val frozen = started.sumOf { it.measurement.freezeDurationMs }.toDouble()
                val playing = started.sumOf { it.measurement.playbackDurationMs }.toDouble()
                listOf(custom("Streaming accessibility", ratio(eligible.count { it.measurement.serviceAccessed || it.measurement.playbackStarted }, eligible.size), "%"),
                    custom("Complete playback (started sessions)", ratio(started.count { it.measurement.playbackCompleted }, started.size), "%"),
                    custom("Video freezing time", if (frozen + playing > 0) frozen * 100 / (frozen + playing) else null, "%"))
            }
            else -> listOf(custom("Completed transfers", ratio(successes.size, eligible.size), "%"),
                custom("Mean transfer rate (successful transfers)", successes.mapNotNull { it.measurement.throughputMbps }.averageOrNull(), "Mbps"))
        }
    }
    return when (c.kind) {
        DataTestKind.PING -> listOf(
            metric("2.3.1 RTT < ${p.rttLimitMs.toInt()} ms", ratio(successes.count { (it.measurement.rttMs ?: Double.POSITIVE_INFINITY) < p.rttLimitMs }, eligible.size), "%", 80.0),
            DataTestMetric("Mean RTT (replies)", successes.mapNotNull { it.measurement.rttMs }.averageOrNull(), "ms", "", "MEASURED"),
            DataTestMetric("Packet loss", ratio(eligible.size - successes.size, eligible.size), "%", "", "MEASURED"))
        DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> buildList {
            add(metric("2.3.2 FTP success", ratio(successes.size, eligible.size), "%", if (c.upload) 70.0 else 80.0))
            add(metric("2.3.3 FTP >= ${p.bitrateMbps(c.upload)} Mbps (successful transfers)",
                ratio(successes.count { (it.measurement.throughputMbps ?: -1.0) >= p.bitrateMbps(c.upload) }, successes.size), "%", 75.0))
            val avg = successes.mapNotNull { it.measurement.throughputMbps }.averageOrNull()
            if (p.band2600) add(metric("2.3.11 Mean FTP bitrate", avg, "Mbps", if (c.upload) 5.0 else 20.0))
            else add(DataTestMetric("Mean FTP bitrate (successful transfers)", avg, "Mbps", "", "MEASURED"))
        }
        DataTestKind.WEB_PAGE -> listOf(
            metric("2.3.4 Complete webpage within ${c.effectiveTimeoutSeconds} s", ratio(successes.size, eligible.size), "%", 90.0),
            DataTestMetric("Mean page load time (complete pages)", successes.map { it.measurement.durationMs }.averageOrNull(), "ms", "", "MEASURED"))
        DataTestKind.STREAMING -> {
            val accessed = eligible.filter { it.measurement.playbackStarted }
            val serviceAccessed = eligible.count { it.measurement.serviceAccessed || it.measurement.playbackStarted }
            val freeze = accessed.sumOf { it.measurement.freezeDurationMs }.toDouble()
            val playing = accessed.sumOf { it.measurement.playbackDurationMs }.toDouble()
            listOf(metric("2.3.5 Streaming accessibility", ratio(serviceAccessed, eligible.size), "%", p.streamingTarget),
                metric("2.3.6 Complete playback (started sessions)", ratio(accessed.count { it.measurement.playbackCompleted }, accessed.size), "%", p.streamingTarget),
                metric("2.3.6/1 Video freezing time", if (freeze + playing > 0) freeze * 100 / (freeze + playing) else null, "%", 5.0, false))
        }
        else -> listOf(DataTestMetric("Completed transfers", ratio(successes.size, eligible.size), "%", "", "CUSTOM"),
            DataTestMetric("Mean transfer rate (successful transfers)", successes.mapNotNull { it.measurement.throughputMbps }.averageOrNull(), "Mbps", "", "CUSTOM"))
    }
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

fun DataTestMetric.displayValue(): String = value?.let { String.format(Locale.US, "%.2f %s", it, unit) } ?: "No data"
