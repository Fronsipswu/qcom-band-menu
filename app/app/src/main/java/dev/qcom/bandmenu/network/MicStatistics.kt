package dev.qcom.bandmenu.network

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** One cohort holds only comparable, explicitly declared campaign conditions. */
data class MicCohortKey(
    val campaignId: String, val operator: String, val device: String, val technology: String,
    val area: MicArea, val kind: DataTestKind, val downloadUrl: String, val uploadUrl: String,
    val udpHost: String, val udpPort: Int, val connections: Int, val warmupSeconds: Int,
    val warmupBytes: Long, val measureSeconds: Int, val measureBytes: Long,
    val udpCount: Int, val udpIntervalMs: Int, val udpTimeoutMs: Int,
    val attemptTimeoutSeconds: Int, val intervalSeconds: Int
) {
    fun toJson(): JSONObject = JSONObject().put("campaignId", campaignId).put("operator", operator)
        .put("device", device).put("declaredTechnology", technology).put("area", area.name).put("kind", kind.name)
        .put("downloadUrl", downloadUrl).put("uploadUrl", uploadUrl).put("udpHost", udpHost).put("udpPort", udpPort)
        .put("connections", connections).put("warmupSeconds", warmupSeconds).put("warmupBytes", warmupBytes)
        .put("measureSeconds", measureSeconds).put("measureBytes", measureBytes).put("udpCount", udpCount)
        .put("udpIntervalMs", udpIntervalMs).put("udpTimeoutMs", udpTimeoutMs)
        .put("attemptTimeoutSeconds", attemptTimeoutSeconds).put("intervalSeconds", intervalSeconds)
}

data class MicPointDirection(val validTrials: Int, val meanMbps: Double?, val reason: String?) {
    fun toJson(): JSONObject = JSONObject().put("validTrials", validTrials).put("meanMbps", meanMbps ?: JSONObject.NULL)
        .put("reason", reason ?: JSONObject.NULL)
}

data class MicPointSummary(
    val reportId: String, val pointId: String, val cohort: MicCohortKey,
    val attemptedTrials: Int, val cancelledTrials: Int, val finished: Boolean,
    val download: MicPointDirection?, val upload: MicPointDirection?, val limitations: List<String>,
    val startedAtMs: Long, val endedAtMs: Long?, val trials: List<DataTestAttempt>
) {
    fun toJson(): JSONObject = JSONObject().put("reportId", reportId).put("pointId", pointId)
        .put("attemptedTrials", attemptedTrials).put("cancelledTrials", cancelledTrials).put("finished", finished)
        .put("download", download?.toJson() ?: JSONObject.NULL).put("upload", upload?.toJson() ?: JSONObject.NULL)
        .put("limitations", JSONArray(limitations)).put("startedAtMs", startedAtMs).put("endedAtMs", endedAtMs ?: JSONObject.NULL)
        .put("trials", JSONArray(trials.map { it.toJson() }))
}

/** Type 7 interpolation is an explicit app convention; MIC does not specify a quantile algorithm. */
data class MicFiveNumberSummary(val count: Int, val minimum: Double, val q1: Double, val median: Double,
    val q3: Double, val maximum: Double) {
    fun toJson(): JSONObject = JSONObject().put("count", count).put("minimumMbps", minimum).put("q1Mbps", q1)
        .put("medianMbps", median).put("q3Mbps", q3).put("maximumMbps", maximum)
}

data class MicDirectionDistribution(
    val requestedPoints: Int, val completePoints: Int, val incompletePoints: Int, val ambiguousReports: Int,
    val values: MicFiveNumberSummary?
) {
    fun toJson(): JSONObject = JSONObject().put("requestedPoints", requestedPoints).put("completePoints", completePoints)
        .put("incompletePoints", incompletePoints).put("ambiguousReports", ambiguousReports)
        .put("fiveNumberSummary", values?.toJson() ?: JSONObject.NULL)
}

data class MicCampaignSummary(
    val key: MicCohortKey, val points: List<MicPointSummary>, val duplicatePointIds: Set<String>,
    val download: MicDirectionDistribution, val upload: MicDirectionDistribution
) {
    val sourceReportIds: List<String> get() = points.map { it.reportId }
    val limitations: List<String> get() = listOf(
        "Local distribution of the supplied reports; no GPS-selected coverage or nationwide 1,500-point claim.",
        "Each point is the arithmetic mean of exactly three numeric trials, without trimming. Directions are assessed independently.",
        "Incomplete and unfinished points are excluded, never replaced with zero. This missing-data policy is an app convention; raw failures remain in source reports.",
        "Repeated point IDs in this cohort are ambiguous: all their reports are excluded, without selecting the best visit.",
        "Quartiles use Type 7 linear interpolation over point means. Whiskers are full minimum and maximum, including outliers.",
        "Cohorts separate operator, declared technology, device/OS, area, endpoints, function and effective measurement settings."
    )

    fun toJson(): JSONObject = JSONObject().put("schemaVersion", 1).put("regime", "MIC")
        .put("cohort", key.toJson()).put("sourceReportIds", JSONArray(sourceReportIds))
        .put("duplicatePointIds", JSONArray(duplicatePointIds.sorted())).put("download", download.toJson())
        .put("upload", upload.toJson()).put("points", JSONArray(points.map { point -> point.toJson()
            .put("excludedAsDuplicate", point.pointId in duplicatePointIds)
            .put("downloadIncluded", point.pointId.isNotBlank() && point.pointId !in duplicatePointIds && point.download?.meanMbps != null)
            .put("uploadIncluded", point.pointId.isNotBlank() && point.pointId !in duplicatePointIds && point.upload?.meanMbps != null)
        }))
        .put("limitations", JSONArray(limitations))

    /** Every row includes its source report and the distribution counts for independent auditing. */
    fun toCsv(): String {
        val headers = listOf("campaign", "operator", "device", "declared_technology", "area", "function",
            "source_report_id", "point_id", "direction", "attempted_trials", "cancelled_trials", "valid_trials",
            "point_mean_mbps", "included", "exclusion_reason", "cohort_requested_points", "cohort_complete_points",
            "cohort_incomplete_points", "cohort_ambiguous_reports", "minimum_mbps", "q1_mbps", "median_mbps", "q3_mbps", "maximum_mbps",
            "download_url", "upload_url", "udp_host", "udp_port", "connections", "warmup_seconds", "warmup_bytes",
            "measure_seconds", "measure_bytes", "udp_count", "udp_interval_ms", "udp_timeout_ms", "attempt_timeout_seconds",
            "interval_seconds", "point_limitations", "summary_limitations", "trial_outcomes", "trial_download_mbps", "trial_upload_mbps", "trial_radio_references")
        val rows = points.flatMap { point -> listOfNotNull(
            point.download?.let { Triple("DOWNLOAD", it, download) }, point.upload?.let { Triple("UPLOAD", it, upload) }
        ).ifEmpty { listOf(Triple("UDP_REFERENCE", MicPointDirection(0, null, "No throughput direction requested"),
            MicDirectionDistribution(0, 0, 0, 0, null))) }.map { (direction, result, distribution) ->
            val ambiguous = point.pointId in duplicatePointIds
            val included = !ambiguous && point.pointId.isNotBlank() && result.meanMbps != null
            val reason = when {
                ambiguous -> "Duplicate point ID"
                point.pointId.isBlank() -> "Missing point ID"
                else -> result.reason.orEmpty()
            }
            val s = distribution.values
            listOf(key.campaignId, key.operator, key.device, key.technology, key.area.name, key.kind.name,
                point.reportId, point.pointId, direction, point.attemptedTrials, point.cancelledTrials, result.validTrials,
                result.meanMbps, included, reason, distribution.requestedPoints, distribution.completePoints,
                distribution.incompletePoints, distribution.ambiguousReports, s?.minimum, s?.q1, s?.median, s?.q3, s?.maximum,
                key.downloadUrl, key.uploadUrl, key.udpHost, key.udpPort, key.connections, key.warmupSeconds, key.warmupBytes,
                key.measureSeconds, key.measureBytes, key.udpCount, key.udpIntervalMs, key.udpTimeoutMs, key.attemptTimeoutSeconds,
                key.intervalSeconds, point.limitations.joinToString("; "), limitations.joinToString("; "),
                JSONArray(point.trials.map { "${it.number}:${it.outcome.name}" }),
                JSONArray(point.trials.map { it.measurement.mic?.download?.aggregateMbps ?: JSONObject.NULL }),
                JSONArray(point.trials.map { it.measurement.mic?.upload?.aggregateMbps ?: JSONObject.NULL }),
                JSONArray(point.trials.map { it.radio?.toJson() ?: JSONObject.NULL }))
        } }
        return (listOf(headers) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::csvCell) }
    }
}

object MicStatistics {
    fun point(report: DataTestReport): MicPointSummary? {
        val c = report.config
        if (!c.isMic) return null
        val eligible = report.attempts.filter { it.outcome != AttemptOutcome.CANCELLED }
        fun direction(upload: Boolean): MicPointDirection {
            val values = eligible.mapNotNull { attempt ->
                if (!attempt.measurement.measurementAvailable) return@mapNotNull null
                val transfer = if (upload) attempt.measurement.mic?.upload else attempt.measurement.mic?.download
                transfer?.aggregateMbps?.takeIf { it.isFinite() && it >= 0 && transfer.expectedStreams == c.effectiveParallelConnections }
            }
            val reason = when {
                report.endedAtMs == null -> "Session is unfinished"
                eligible.size != 3 -> "Exactly three non-cancelled trials are required; found ${eligible.size}"
                eligible.map { it.number }.distinct().size != 3 -> "Trial numbers are duplicated"
                values.size != 3 -> "Only ${values.size} of three trials have complete numeric transfer measurements"
                else -> null
            }
            // Divide before summing to avoid overflow for finite, nonnegative input values.
            val mean = if (reason == null) values.sumOf { it / 3.0 } else null
            return MicPointDirection(values.size, mean, reason)
        }
        return MicPointSummary(report.id, c.pointId.trim(), cohort(report), eligible.size,
            report.attempts.count { it.outcome == AttemptOutcome.CANCELLED }, report.endedAtMs != null,
            if (c.kind in listOf(DataTestKind.MIC_POINT, DataTestKind.PARALLEL_DOWNLOAD)) direction(false) else null,
            if (c.kind in listOf(DataTestKind.MIC_POINT, DataTestKind.PARALLEL_UPLOAD)) direction(true) else null,
            micLimitations(report), report.startedAtMs, report.endedAtMs, report.attempts)
    }

    fun cohort(report: DataTestReport): MicCohortKey = with(report.config) {
        MicCohortKey(campaignId.trim(), operator.trim(), report.environment, declaredTechnology.trim(), micArea, kind,
            url, uploadUrl, host, port, effectiveParallelConnections, effectiveWarmupSeconds, effectiveWarmupBytes,
            effectiveMeasureSeconds, effectiveMeasureBytes, effectiveUdpCount, effectiveUdpIntervalMs, effectiveUdpTimeoutMs,
            timeoutSeconds, intervalSeconds)
    }

    fun campaigns(reports: List<DataTestReport>): List<MicCampaignSummary> = reports.distinctBy { it.id }
        .mapNotNull(::point).groupBy { it.cohort }.map { (key, points) ->
            val duplicates = points.filter { it.pointId.isNotBlank() }.groupingBy { it.pointId }.eachCount()
                .filterValues { it > 1 }.keys
            fun distribution(upload: Boolean): MicDirectionDistribution {
                val requested = points.mapNotNull { point ->
                    (if (upload) point.upload else point.download)?.let { point to it }
                }
                val ambiguous = requested.count { it.first.pointId in duplicates }
                val values = requested.filter { it.first.pointId.isNotBlank() && it.first.pointId !in duplicates }
                    .mapNotNull { it.second.meanMbps }
                return MicDirectionDistribution(requested.size, values.size, requested.size - values.size - ambiguous,
                    ambiguous, fiveNumber(values))
            }
            MicCampaignSummary(key, points, duplicates, distribution(false), distribution(true))
        }

    /** Type 7: index=(n-1)*p; linearly interpolate adjacent sorted values. No outlier removal. */
    fun fiveNumber(values: List<Double>): MicFiveNumberSummary? {
        if (values.isEmpty()) return null
        require(values.all { it.isFinite() && it >= 0 }) { "Throughput values must be finite and nonnegative" }
        val sorted = values.sorted()
        fun quantile(p: Double): Double {
            val position = (sorted.size - 1) * p
            val lo = floor(position).toInt()
            val hi = (lo + 1).coerceAtMost(sorted.lastIndex)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (position - lo)
        }
        return MicFiveNumberSummary(sorted.size, sorted.first(), quantile(.25), quantile(.5), quantile(.75), sorted.last())
    }
}

/** MIC supplies descriptive measurements, without a minimum-speed pass/fail verdict. */
fun micSessionMetrics(report: DataTestReport): List<DataTestMetric> {
    val point = MicStatistics.point(report) ?: return emptyList()
    return buildList {
        fun direction(name: String, result: MicPointDirection?) {
            if (result == null) return
            add(DataTestMetric("$name point mean (${result.validTrials}/3 numeric trials)", result.meanMbps, "Mbps", "",
                if (result.meanMbps == null) "INCOMPLETE" else "MIC POINT MEAN"))
        }
        direction("Download", point.download)
        direction("Upload", point.upload)
        val udp = report.attempts.filter { it.outcome != AttemptOutcome.CANCELLED && it.measurement.measurementAvailable }
            .mapNotNull { it.measurement.mic?.udp }
        if (report.config.kind in listOf(DataTestKind.MIC_POINT, DataTestKind.UDP_LATENCY)) {
            val valid = udp.filter { it.error == null && it.sent > 0 && it.received >= 0 && it.lost >= 0 &&
                it.received + it.lost == it.sent && it.rttsMs.size == it.received && it.rttsMs.all { rtt -> rtt.isFinite() && rtt >= 0 } }
            val replies = valid.flatMap { it.rttsMs }
            val sent = valid.sumOf { it.sent.toLong() }
            val lost = valid.sumOf { it.lost.toLong() }
            add(DataTestMetric("UDP mean RTT (received replies)", replies.takeIf { it.isNotEmpty() }?.average(), "ms", "", "MEASURED"))
            add(DataTestMetric("UDP packet loss (sent probes)", if (sent > 0) lost * 100.0 / sent else null, "%", "", "MEASURED"))
        }
    }
}
