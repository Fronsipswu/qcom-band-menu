package dev.qcom.bandmenu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.network.DataTestReport
import dev.qcom.bandmenu.network.MicDirectionDistribution
import dev.qcom.bandmenu.network.MicFiveNumberSummary
import dev.qcom.bandmenu.network.MicStatistics
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/** Summary export callback uses true for JSON, false for CSV, matching report exports. */
@Composable
fun MicCampaignSummary(reports: List<DataTestReport>, selected: DataTestReport, onExport: (String, Boolean) -> Unit) {
    if (!selected.config.isMic) return
    val summary = remember(reports, selected) {
        val available = reports.filterNot { it.id == selected.id } + selected
        val selectedKey = MicStatistics.cohort(selected)
        MicStatistics.campaigns(available).firstOrNull { it.key == selectedKey }
    } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local MIC campaign: ${summary.key.campaignId}", style = MiuixTheme.textStyles.title4)
        Text("${summary.key.operator} • ${summary.key.technology} • ${summary.key.area.title}", style = MiuixTheme.textStyles.body2)
        Text("${summary.points.size} point reports in this device, endpoint and settings cohort. Each point mean uses three numeric trials.",
            style = MiuixTheme.textStyles.body2)
        Distribution("Download", summary.download)
        Distribution("Upload", summary.upload)
        if (summary.duplicatePointIds.isNotEmpty()) Text(
            "Duplicate point IDs excluded: ${summary.duplicatePointIds.sorted().joinToString()}", style = MiuixTheme.textStyles.body2)
        Text("Q1–Q3 shows the middle half of local point means. Whiskers show all values. Quartiles use Type 7 interpolation. Incomplete or duplicate points are excluded, never replaced with zero.",
            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text("This summary does not establish the guideline's nationwide 1,500-point coverage. Point identities and technology are declared; GPS is omitted.",
            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton("Campaign JSON", onClick = { onExport(summary.toJson().toString(2), true) }, modifier = Modifier.weight(1f))
            TextButton("Campaign CSV", onClick = { onExport(summary.toCsv(), false) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Distribution(title: String, distribution: MicDirectionDistribution) {
    if (distribution.requestedPoints == 0) return
    Text("$title: ${distribution.completePoints} complete, ${distribution.incompletePoints} incomplete, ${distribution.ambiguousReports} duplicate reports",
        style = MiuixTheme.textStyles.body2)
    val s = distribution.values
    if (s == null) {
        Text("No complete point means", style = MiuixTheme.textStyles.body2)
        return
    }
    Text("Q1–Q3 ${speed(s.q1)}–${speed(s.q3)} Mbps • median ${speed(s.median)} Mbps", style = MiuixTheme.textStyles.body2)
    MicBoxPlot(s)
    Text("Minimum ${speed(s.minimum)} • Q1 ${speed(s.q1)} • median ${speed(s.median)} • Q3 ${speed(s.q3)} • maximum ${speed(s.maximum)} Mbps",
        style = MiuixTheme.textStyles.body2)
}

@Composable
private fun MicBoxPlot(s: MicFiveNumberSummary) {
    val ink = MiuixTheme.colorScheme.primary
    val description = "${s.count} point means; minimum ${speed(s.minimum)}, first quartile ${speed(s.q1)}, median ${speed(s.median)}, third quartile ${speed(s.q3)}, maximum ${speed(s.maximum)} megabits per second"
    Canvas(Modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = description }) {
        val padding = 8.dp.toPx()
        val span = s.maximum - s.minimum
        fun x(value: Double): Float = if (span == 0.0) size.width / 2 else
            padding + ((value - s.minimum) / span).toFloat() * (size.width - 2 * padding)
        val mid = size.height / 2
        val half = 14.dp.toPx()
        val stroke = 2.dp.toPx()
        drawLine(ink, Offset(x(s.minimum), mid), Offset(x(s.maximum), mid), stroke)
        listOf(s.minimum, s.maximum).forEach { value ->
            drawLine(ink, Offset(x(value), mid - half / 2), Offset(x(value), mid + half / 2), stroke)
        }
        val left = x(s.q1)
        val width = (x(s.q3) - left).coerceAtLeast(stroke)
        drawRect(ink.copy(alpha = .18f), Offset(left, mid - half), Size(width, 2 * half))
        drawRect(ink, Offset(left, mid - half), Size(width, 2 * half), style = Stroke(stroke))
        drawLine(ink, Offset(x(s.median), mid - half), Offset(x(s.median), mid + half), stroke)
    }
}

private fun speed(value: Double): String = String.format(Locale.US, "%.2f", value)
