package dev.qcom.bandmenu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.network.Metric
import dev.qcom.bandmenu.network.MetricKind
import dev.qcom.bandmenu.network.Rat
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/**
 * Signal scale for one metric family on one RAT: the bar's range and the
 * four thresholds between the five quality levels (best first). WCDMA Ec/Io
 * and GSM RSSI get their own scales, never the LTE ones.
 */
data class SignalScale(
    val min: Double,
    val max: Double,
    val thresholds: List<Double>,
    /** TX power: a fuller bar is worse (red at max), so levels count the other way. */
    val higherIsWorse: Boolean = false
) {
    /** 0 = excellent … 4 = bad. */
    fun level(v: Double): Int =
        thresholds.indexOfFirst { if (higherIsWorse) v <= it else v >= it }.let { if (it < 0) 4 else it }
    fun fraction(v: Double): Float = ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
}

fun signalScale(kind: MetricKind, rat: Rat): SignalScale = when (kind) {
    MetricKind.RSRP -> SignalScale(-140.0, -60.0, listOf(-85.0, -95.0, -105.0, -115.0))
    MetricKind.RSRQ -> SignalScale(-25.0, -3.0, listOf(-6.0, -10.0, -15.0, -20.0))
    MetricKind.SINR -> SignalScale(-10.0, 35.0, listOf(22.0, 15.0, 10.0, 3.0))
    MetricKind.RSSI -> if (rat == Rat.GSM) {
        SignalScale(-110.0, -48.0, listOf(-70.0, -80.0, -90.0, -100.0))
    } else {
        SignalScale(-110.0, -40.0, listOf(-65.0, -75.0, -85.0, -95.0))
    }
    MetricKind.RSCP -> SignalScale(-120.0, -40.0, listOf(-75.0, -85.0, -95.0, -105.0))
    MetricKind.ECIO -> SignalScale(-24.0, 0.0, listOf(-6.0, -10.0, -14.0, -18.0))
    // UE transmit power: -27 dBm empty/green … 26 dBm full/red (clamped).
    MetricKind.TX_POWER -> SignalScale(-27.0, 26.0, listOf(0.0, 10.0, 17.0, 23.0), higherIsWorse = true)
}

// Mid-tone level colours, readable on both the light and the dark track.
private val LEVEL_COLORS = listOf(
    Color(0xFF1E9E50), Color(0xFF7CB342), Color(0xFFEAB01F), Color(0xFFF07E26), Color(0xFFE0453A)
)
fun signalLevelColor(level: Int): Color = LEVEL_COLORS[level.coerceIn(0, 4)]

private val LEVEL_WORDS = listOf("excellent", "good", "fair", "poor", "bad")

/** Signal values: always one decimal ("-10.0"), rounded half-up from finer values. */
fun formatMetric(v: Double): String =
    java.math.BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()

/** Plain quantities (bandwidth): no trailing ".0". */
fun formatPlain(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

/**
 * One metric row: label, bar, value. An unavailable value renders an empty
 * track and "—", never a red zero bar.
 */
@Composable
fun SignalBar(
    metric: Metric,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 64.dp,
    valueWidth: Dp = 92.dp,
    barHeight: Dp = 10.dp
) {
    val scale = signalScale(metric.kind, metric.rat)
    val v = metric.value
    val level = v?.let(scale::level)
    val fill by animateFloatAsState(v?.let(scale::fraction) ?: 0f, tween(450), label = "signalFill")
    val color by animateColorAsState(level?.let { LEVEL_COLORS[it] } ?: Color.Transparent, tween(450), label = "signalColor")
    val track = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    val tick = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.22f)
    val valueText = metric.text ?: v?.let { "${formatMetric(it)} ${metric.unit}" } ?: "—"
    val spoken = if (v == null && metric.text == null) "${metric.label} unavailable"
        else "${metric.label} $valueText${level?.let { ", " + LEVEL_WORDS[it] } ?: ""}"

    Row(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            metric.label,
            modifier = Modifier.width(labelWidth),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Canvas(modifier = Modifier.weight(1f).height(barHeight)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            // Threshold ticks: where the colour steps.
            scale.thresholds.forEach { t ->
                val x = size.width * scale.fraction(t)
                drawRect(tick, topLeft = Offset(x - 0.5f, size.height * 0.2f), size = Size(1f, size.height * 0.6f))
            }
            if (fill > 0f) {
                drawRoundRect(color, size = Size(size.width * fill.coerceAtLeast(size.height / size.width), size.height), cornerRadius = r)
            }
        }
        Text(
            valueText,
            modifier = Modifier.width(valueWidth),
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = if (v == null && metric.text == null) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MiuixTheme.colorScheme.onBackground
        )
    }
}
