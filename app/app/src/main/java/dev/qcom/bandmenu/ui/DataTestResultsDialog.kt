package dev.qcom.bandmenu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.network.AttemptOutcome
import dev.qcom.bandmenu.network.DataTestAttempt
import dev.qcom.bandmenu.network.DataTestKind
import dev.qcom.bandmenu.network.DataTestReport
import dev.qcom.bandmenu.network.MicTransferResult
import dev.qcom.bandmenu.network.assess
import dev.qcom.bandmenu.network.displayValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Session evidence, numerical assessments and exports; no operator-wide verdict. */
@Composable
fun DataTestResultsDialog(
    show: Boolean,
    reports: List<DataTestReport>,
    onDismiss: () -> Unit,
    onExport: (DataTestReport, Boolean) -> Unit,
    onExportSummary: (String, Boolean) -> Unit = { _, _ -> }
) {
    val ordered = remember(reports) { reports.sortedByDescending { it.startedAtMs } }
    var selectedId by remember(show) { mutableStateOf(ordered.firstOrNull()?.id) }
    val index = ordered.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val report = ordered.getOrNull(index)
    var attemptPage by remember(show, report?.id) { mutableStateOf(0) }
    val pageSize = 20
    WindowDialog(show = show, title = "Data test results", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (report == null) {
                ResultHint("No recorded sessions yet. Configure a test in Test script, then start it from the network screen.")
            } else {
                if (ordered.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = "Newer", enabled = index > 0,
                            onClick = { selectedId = ordered[index - 1].id }, modifier = Modifier.weight(1f))
                        TextButton(text = "Older", enabled = index < ordered.lastIndex,
                            onClick = { selectedId = ordered[index + 1].id }, modifier = Modifier.weight(1f))
                    }
                    ResultHint("Session ${index + 1} of ${ordered.size}")
                }
                Text(report.config.kind.title, style = MiuixTheme.textStyles.body1)
                Text(when {
                    report.config.isNbtc -> "NBTC preset: ${report.config.profile.title}"
                    report.config.isMic -> "MIC Japan: independent method measurements"
                    else -> "Custom configuration"
                },
                    style = MiuixTheme.textStyles.body2)
                ResultHint("Started: ${reportTime(report.startedAtMs, report.config.isMic)}")
                ResultHint(report.endedAtMs?.let { "Ended: ${reportTime(it, report.config.isMic)}" } ?: "Session in progress")
                if (report.config.sessionLabel.isNotBlank()) ResultHint("Label: ${report.config.sessionLabel}")
                if (report.config.operator.isNotBlank()) ResultHint("Operator: ${report.config.operator}")
                if (report.config.locationLabel.isNotBlank()) ResultHint("Location: ${report.config.locationLabel}")
                if (report.config.campaignId.isNotBlank()) ResultHint("Campaign: ${report.config.campaignId}")
                if (report.config.pointId.isNotBlank()) ResultHint("Point: ${report.config.pointId}")
                if (report.config.kind.mic) ResultHint("Declared technology: ${report.config.declaredTechnology}")
                if (report.config.isMic) ResultHint("${report.config.micArea.title}: ${report.config.micArea.startHour}:00-${report.config.micArea.endHour}:00 JST")
                ResultHint("Network: ${report.network.ifBlank { "Not recorded" }}")
                if (report.environment.isNotBlank()) ResultHint(report.environment)
                SelectionContainer { ResultHint("Endpoint: ${reportEndpoint(report)}") }
                ResultHint("${report.config.effectiveRepetitions} requested trials; ${report.config.effectiveTimeoutSeconds} s trial budget; ${report.config.intervalSeconds} s idle interval")
                if (report.config.kind.mic && report.config.kind != DataTestKind.UDP_LATENCY) {
                    ResultHint("${report.config.effectiveParallelConnections} connections; warm-up ${report.config.effectiveWarmupSeconds} s or more than ${report.config.effectiveWarmupBytes} bytes; " +
                        "measured limit ${report.config.effectiveMeasureSeconds} s or ${report.config.effectiveMeasureBytes} bytes per stream")
                }
                Text("${report.attempts.size} recorded attempts", style = MiuixTheme.textStyles.body2)
                val counts = report.attempts.groupingBy { it.outcome }.eachCount()
                ResultHint("Success ${counts[AttemptOutcome.SUCCESS] ?: 0}  |  Failed ${counts[AttemptOutcome.FAILED] ?: 0}\n" +
                    "Timed out ${counts[AttemptOutcome.TIMED_OUT] ?: 0}  |  Cancelled ${counts[AttemptOutcome.CANCELLED] ?: 0}")
                if (report.config.kind == DataTestKind.STREAMING) {
                    ResultHint("Service accessed ${report.attempts.count { it.measurement.serviceAccessed || it.measurement.playbackStarted }}; " +
                        "playback started ${report.attempts.count { it.measurement.playbackStarted }}; " +
                        "completed ${report.attempts.count { it.measurement.playbackCompleted }}")
                }
                if (report.config.kind != DataTestKind.STREAMING) ResultHint("Measured payload: ${dataVolume(report.attempts.sumOf { it.measurement.bytes })}")
                report.stopReason?.let { ResultHint("Stopped: $it") }

                Text("Session measurements", style = MiuixTheme.textStyles.body1)
                report.assess().forEach { metric ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(metric.name, style = MiuixTheme.textStyles.body2)
                        Text(metric.displayValue(), style = MiuixTheme.textStyles.body1)
                        if (report.config.isNbtc && metric.target.isNotBlank()) ResultHint("Target ${metric.target}")
                        ResultHint(metric.status)
                    }
                }
                if (report.config.isMic && report.config.kind == DataTestKind.MIC_POINT) {
                    MicCampaignSummary(reports = reports, selected = report, onExport = onExportSummary)
                } else if (report.config.isMic) {
                    ResultHint("This is a component session. Campaign point distributions use complete Point sequence records.")
                }
                Text("Measurement context", style = MiuixTheme.textStyles.body1)
                report.limitations().forEach { ResultHint(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = "Export CSV", onClick = { onExport(report, false) }, modifier = Modifier.weight(1f))
                    TextButton(text = "Export JSON", onClick = { onExport(report, true) }, modifier = Modifier.weight(1f))
                }

                Text("Attempt evidence", style = MiuixTheme.textStyles.body1)
                if (report.attempts.isEmpty()) ResultHint("No attempt has finished.")
                else {
                    val lastPage = (report.attempts.size - 1) / pageSize
                    val currentPage = attemptPage.coerceIn(0, lastPage)
                    ResultHint("Attempts ${currentPage * pageSize + 1}-${minOf((currentPage + 1) * pageSize, report.attempts.size)} of ${report.attempts.size}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = "Previous", enabled = currentPage > 0,
                            onClick = { attemptPage = currentPage - 1 }, modifier = Modifier.weight(1f))
                        TextButton(text = "Next", enabled = currentPage < lastPage,
                            onClick = { attemptPage = currentPage + 1 }, modifier = Modifier.weight(1f))
                    }
                    report.attempts.drop(currentPage * pageSize).take(pageSize).forEach { attempt ->
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("#${attempt.number} ${attempt.outcome.name}", style = MiuixTheme.textStyles.body2)
                                ResultHint(reportTime(attempt.startedAtMs, report.config.isMic))
                                ResultHint(attemptDetails(attempt))
                                attempt.radio?.let { radio ->
                                    val age = attempt.elapsedRealtimeMs - radio.observedAtElapsedMs
                                    ResultHint("Radio: ${radio.technology}; ${radio.signal.ifBlank { "signal unavailable" }}; SIM slot ${radio.simSlot}; " +
                                        if (age >= 0) "snapshot age ${age} ms" else "snapshot time unavailable")
                                }
                                if (report.config.kind.mic && attempt.radio == null) ResultHint("Measured radio reference unavailable for this trial.")
                                attempt.measurement.mic?.let { mic ->
                                    mic.download?.let { TransferEvidence("Download", it) }
                                    mic.upload?.let { TransferEvidence("Upload", it) }
                                    mic.udp?.let { udp ->
                                        ResultHint("UDP: ${udp.sent} sent, ${udp.received} received, ${udp.lost} lost; " +
                                            "loss ${valueWithUnit(udp.lossPercent, "%")}; mean RTT ${valueWithUnit(udp.averageRttMs, "ms")}")
                                        if (udp.rttsMs.isNotEmpty()) {
                                            ResultHint("RTT min ${valueWithUnit(udp.rttsMs.minOrNull(), "ms")}; max ${valueWithUnit(udp.rttsMs.maxOrNull(), "ms")}")
                                            ResultHint("Received RTTs (ms): " + udp.rttsMs.joinToString(", ") { String.format(Locale.US, "%.2f", it) })
                                        }
                                        udp.error?.let { ResultHint("UDP error: $it") }
                                    }
                                }
                                attempt.measurement.error?.let { ResultHint(it) }
                                attempt.measurement.detail?.let { ResultHint(it) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(text = "Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

private fun reportEndpoint(report: DataTestReport): String = with(report.config) {
    when (kind) {
        DataTestKind.PING -> host
        DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> "$host:$port$path"
        DataTestKind.STREAMING -> "https://www.youtube.com/watch?v=$videoId"
        DataTestKind.MIC_POINT -> "Download $url\nUpload $uploadUrl\nUDP $host:$port"
        DataTestKind.PARALLEL_UPLOAD -> uploadUrl
        DataTestKind.UDP_LATENCY -> "$host:$port"
        else -> url
    }
}

private fun attemptDetails(attempt: DataTestAttempt): String = with(attempt.measurement) {
    buildList {
        add(String.format(Locale.US, "%.2f ms", durationMs))
        if (bytes > 0) add(dataVolume(bytes))
        rttMs?.let { add(String.format(Locale.US, "RTT %.2f ms", it)) }
        throughputMbps?.let { add(String.format(Locale.US, "%.3f Mbps", it)) }
        httpStatus?.let { add("HTTP $it") }
        if (serviceAccessed && !playbackStarted) add("Service accessed; playback not started")
        startupDelayMs?.let { add(String.format(Locale.US, "Startup %.0f ms", it)) }
        if (playbackStarted) add(if (playbackCompleted) "Playback complete" else "Playback started, incomplete")
        observedQuality?.let { add("Quality $it") }
        if (playbackDurationMs > 0 || freezeDurationMs > 0) add("Playing ${playbackDurationMs} ms; frozen ${freezeDurationMs} ms")
        if (!contentVerified) add("Content / quality unverified")
        if (!measurementAvailable) add("Instrument measurement unavailable")
    }.joinToString(" | ")
}

private fun reportTime(ms: Long, mic: Boolean): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
    .withZone(ZoneId.of(if (mic) "Asia/Tokyo" else "Asia/Bangkok")).format(Instant.ofEpochMilli(ms))

@Composable
private fun TransferEvidence(label: String, transfer: MicTransferResult) {
    ResultHint("$label: ${valueWithUnit(transfer.aggregateMbps, "Mbps")}; ${transfer.streams.size}/${transfer.expectedStreams} streams recorded")
    transfer.streams.forEach { stream ->
        ResultHint("Stream ${stream.index}: ${valueWithUnit(stream.rateMbps, "Mbps")}; " +
            "warm-up ${stream.warmupBytes} bytes / ${valueWithUnit(stream.warmupMs, "ms")}; " +
            "measured ${stream.bytes} bytes / ${valueWithUnit(stream.durationMs, "ms")}")
        if (stream.stopReason.isNotBlank()) ResultHint("Stream ${stream.index} stopped: ${stream.stopReason}")
        stream.httpStatus?.let { ResultHint("Stream ${stream.index}: HTTP $it") }
        stream.error?.let { ResultHint("Stream ${stream.index} error: $it") }
    }
    transfer.error?.let { ResultHint("$label error: $it") }
}

private fun valueWithUnit(value: Double?, unit: String): String = value?.let { String.format(Locale.US, "%.3f %s", it, unit) } ?: "N/A"

@Composable
private fun ResultHint(text: String) {
    Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
