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
    onExport: (DataTestReport, Boolean) -> Unit
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
                Text(if (report.config.isNbtc) "NBTC preset: ${report.config.profile.title}" else "Custom configuration",
                    style = MiuixTheme.textStyles.body2)
                ResultHint("Started: ${reportTime(report.startedAtMs)}")
                ResultHint(report.endedAtMs?.let { "Ended: ${reportTime(it)}" } ?: "Session in progress")
                if (report.config.sessionLabel.isNotBlank()) ResultHint("Label: ${report.config.sessionLabel}")
                if (report.config.operator.isNotBlank()) ResultHint("Operator: ${report.config.operator}")
                if (report.config.locationLabel.isNotBlank()) ResultHint("Location: ${report.config.locationLabel}")
                ResultHint("Network: ${report.network.ifBlank { "Not recorded" }}")
                if (report.environment.isNotBlank()) ResultHint(report.environment)
                SelectionContainer { ResultHint("Endpoint: ${reportEndpoint(report)}") }
                ResultHint("${report.config.repetitions} requested attempts; ${report.config.effectiveTimeoutSeconds} s timeout; ${report.config.intervalSeconds} s idle interval")
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
                                ResultHint(reportTime(attempt.startedAtMs))
                                ResultHint(attemptDetails(attempt))
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
    }.joinToString(" | ")
}

private fun reportTime(ms: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
    .withZone(ZoneId.of("Asia/Bangkok")).format(Instant.ofEpochMilli(ms))

@Composable
private fun ResultHint(text: String) {
    Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
