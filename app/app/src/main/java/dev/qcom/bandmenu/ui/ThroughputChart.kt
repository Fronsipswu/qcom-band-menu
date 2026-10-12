package dev.qcom.bandmenu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.network.ThputPoint
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The window shown: [WINDOW_MS] centred on the cursor. */
private const val WINDOW_MS = 60_000L
private const val GRID_MS = 10_000L
/** Longer gaps than this between samples break the line. */
private const val GAP_MS = 2_500L

/**
 * Combined PHY downlink throughput (all LTE and NR carriers) over a minute.
 * The cursor stays in the middle at [centerMs], the sample shown; the window
 * moves under it. [maxMbps] sets the scale, five grid steps.
 */
@Composable
fun ThroughputCard(points: List<ThputPoint>, centerMs: Long, maxMbps: Int) {
    SmallTitle("Throughput", modifier = Modifier.padding(top = 2.dp))
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        val grid = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.12f)
        val label = MiuixTheme.colorScheme.onSurfaceVariantSummary
        val line = signalLevelColor(2)   // the "fair" signal yellow
        val cursor = MiuixTheme.colorScheme.onBackground
        val labelStyle = MiuixTheme.textStyles.footnote2.copy(color = label)
        val valueStyle = MiuixTheme.textStyles.footnote1.copy(color = Color.Black, fontWeight = FontWeight.Bold)
        val boxColor = line
        val measurer = rememberTextMeasurer()
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
            val w = size.width
            val h = size.height
            val start = centerMs - WINDOW_MS / 2
            fun x(t: Long) = (t - start).toFloat() / WINDOW_MS * w
            fun y(v: Double) = h - (v / maxMbps).coerceIn(0.0, 1.0).toFloat() * h

            // Grid: five horizontal steps; vertical lines every 10 s of clock
            // time, so they move with the window.
            val step = maxMbps / 5
            for (i in 0..5) {
                val gy = h - h * i / 5
                drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
            }
            var gt = (start / GRID_MS + 1) * GRID_MS
            while (gt < start + WINDOW_MS) {
                drawLine(grid, Offset(x(gt), 0f), Offset(x(gt), h), strokeWidth = 1.dp.toPx())
                gt += GRID_MS
            }
            for (i in 1..5) {
                val text = measurer.measure("${step * i}", labelStyle)
                drawText(text, topLeft = Offset(4.dp.toPx(), h - h * i / 5 + 2.dp.toPx()))
            }

            // Throughput as steps: each sample holds until the next one.
            val path = Path()
            var prev: ThputPoint? = null
            for (p in points) {
                if (p.t < start - GAP_MS || p.t > start + WINDOW_MS + GAP_MS) { prev = null; continue }
                val last = prev
                if (last == null || p.t - last.t > GAP_MS) {
                    path.moveTo(x(p.t), y(p.mbps))
                } else {
                    path.lineTo(x(p.t), y(last.mbps))
                    path.lineTo(x(p.t), y(p.mbps))
                }
                prev = p
            }
            drawContext.canvas.save()
            drawContext.canvas.clipRect(0f, 0f, w, h)
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
            drawContext.canvas.restore()

            // The cursor: the sample on screen.
            drawLine(cursor, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 1.5.dp.toPx())

            // Its combined throughput, top right.
            points.lastOrNull { it.t <= centerMs && centerMs - it.t <= GAP_MS }?.let { p ->
                val v = if (p.mbps >= 100) "%.0f".format(p.mbps) else "%.1f".format(p.mbps)
                val text = measurer.measure(v, valueStyle)
                val padH = 6.dp.toPx()
                val padV = 2.dp.toPx()
                val bw = text.size.width + 2 * padH
                val bh = text.size.height + 2 * padV
                drawRoundRect(boxColor, topLeft = Offset(w - bw, 0f), size = Size(bw, bh), cornerRadius = CornerRadius(4.dp.toPx()))
                drawText(text, topLeft = Offset(w - bw + padH, padV))
            }
        }
    }
}
