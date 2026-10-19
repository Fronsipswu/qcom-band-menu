package dev.qcom.bandmenu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.network.ThputPoint
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The window shown (see [ThroughputCard] for where the cursor sits in it). */
private const val WINDOW_MS = 120_000L
private const val GRID_MS = 10_000L
/** The highest value on screen may fill this much of the scale before the next step up. */
private const val HEADROOM = 0.8
/** Longer gaps than this between samples break the line. */
private const val GAP_MS = 2_500L
/** The samples a chart at a cursor can draw: a window ending at the cursor
 *  up to one centred on it, each with the sample held in from its edge. */
internal const val THPUT_BEFORE_MS = WINDOW_MS + GAP_MS
internal const val THPUT_AFTER_MS = WINDOW_MS / 2 + GAP_MS
/** Five grid steps of at least this (Mbps): an idle link shows 0-5 Mbps. */
private const val MIN_STEP = 1

/**
 * The grid step (Mbps) for a [peak]: the smallest 1-2-5 step (1, 2, 5, 10,
 * 20, 50, ...) whose five steps keep [peak] under [HEADROOM] of the top.
 */
internal fun niceStep(peak: Double): Int {
    var decade = MIN_STEP
    while (true) {
        for (m in intArrayOf(1, 2, 5)) {
            val step = decade * m
            if (step >= MIN_STEP && peak <= step * 5 * HEADROOM) return step
        }
        if (decade > Int.MAX_VALUE / 100) return decade * 10
        decade *= 10
    }
}

/**
 * Where the window starts for a cursor at [centerMs] when the newest sample is
 * at [newestMs]: the window ends at the newest sample, kept between the
 * cursor (cursor at the right edge) and half a window after it (centred).
 */
internal fun windowStart(centerMs: Long, newestMs: Long): Long =
    newestMs.coerceIn(centerMs, centerMs + WINDOW_MS / 2) - WINDOW_MS

/** The downlink and uplink lines and their values (also the CA tables' PHY rows). */
internal val DownlinkColor: Color get() = signalLevelColor(2)   // the "fair" signal yellow
internal val UplinkColor = Color(0xFF40E0D0)

/**
 * Combined PHY downlink and uplink throughput (all LTE and NR carriers) over
 * two minutes.
 * The cursor marks [centerMs], the sample shown; the window moves under it.
 * The window follows [windowStart]: on the newest sample (live, or a replay
 * at its end) the cursor is at the right edge and the whole window is
 * history; scrubbing back slides it towards the middle, where it stays once
 * there are newer samples to fill the right half. Five grid steps; the step autoscales ([niceStep]) to
 * keep every DL and UL value on screen under [HEADROOM] of the top, so the
 * scale doesn't flip on single samples near a step and no line is clipped.
 */
@Composable
fun ThroughputCard(points: List<ThputPoint>, centerMs: Long) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        val grid = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.12f)
        val label = MiuixTheme.colorScheme.onSurfaceVariantSummary
        val cursor = MiuixTheme.colorScheme.onBackground
        val labelStyle = MiuixTheme.textStyles.footnote2.copy(color = label)
        val valueStyle = MiuixTheme.textStyles.footnote2.copy(fontWeight = FontWeight.Bold)
        // The card's own colour: the box only hides the lines behind the values.
        val boxColor = MiuixTheme.colorScheme.surfaceContainer
        val measurer = rememberTextMeasurer()
        val (atCursor, scaleStep, start) = remember(points, centerMs) {
            val cur = points.lastOrNull { it.t <= centerMs && centerMs - it.t <= GAP_MS }
            val from = windowStart(centerMs, points.lastOrNull()?.t ?: centerMs)
            // The highest DL or UL sample drawn, the one held in from just
            // before the window included.
            var peak = 0.0
            for (p in points) {
                if (p.t in from - GAP_MS..from + WINDOW_MS) {
                    val m = maxOf(p.mbps, p.ulMbps)
                    if (m > peak) peak = m
                }
            }
            Triple(cur, niceStep(peak), from)
        }
        val scaleLabels = remember(measurer, scaleStep, labelStyle) {
            (1..5).map { i -> measurer.measure("${scaleStep * i}", labelStyle) }
        }
        val ulPath = remember { Path() }
        val dlPath = remember { Path() }
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
            val w = size.width
            val h = size.height
            fun x(t: Long) = (t - start).toFloat() / WINDOW_MS * w
            fun y(v: Double) = h - (v / (scaleStep * 5)).coerceIn(0.0, 1.0).toFloat() * h

            // Grid: five horizontal steps; vertical lines every 10 s of clock
            // time, so they move with the window.
            for (i in 0..5) {
                val gy = h - h * i / 5
                drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
            }
            var gt = (start / GRID_MS + 1) * GRID_MS
            while (gt < start + WINDOW_MS) {
                drawLine(grid, Offset(x(gt), 0f), Offset(x(gt), h), strokeWidth = 1.dp.toPx())
                gt += GRID_MS
            }

            // Throughput as steps: each sample holds until the next one.
            fun line(path: Path, value: (ThputPoint) -> Double): Path {
                path.rewind()
                var prev: ThputPoint? = null
                for (p in points) {
                    if (p.t < start - GAP_MS || p.t > start + WINDOW_MS + GAP_MS) { prev = null; continue }
                    val last = prev
                    if (last == null || p.t - last.t > GAP_MS) {
                        path.moveTo(x(p.t), y(value(p)))
                    } else {
                        path.lineTo(x(p.t), y(value(last)))
                        path.lineTo(x(p.t), y(value(p)))
                    }
                    prev = p
                }
                return path
            }
            drawContext.canvas.save()
            drawContext.canvas.clipRect(0f, 0f, w, h)
            drawPath(line(ulPath) { it.ulMbps }, UplinkColor, style = Stroke(width = 2.dp.toPx()))
            drawPath(line(dlPath) { it.mbps }, DownlinkColor, style = Stroke(width = 2.dp.toPx()))
            drawContext.canvas.restore()

            // Scale labels over the lines, so a line never hides them.
            for (i in 1..5) {
                drawText(scaleLabels[i - 1], topLeft = Offset(4.dp.toPx(), h - h * i / 5 + 2.dp.toPx()))
            }

            // The cursor: the sample on screen.
            val cx = x(centerMs).coerceAtMost(w - 0.75.dp.toPx())
            drawLine(cursor, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5.dp.toPx())

            // Its combined throughput, top right: DL over UL, each in its line's colour.
            atCursor?.let { p ->
                fun value(name: String, mbps: Double, style: TextStyle) = measurer.measure("$name %.1f Mbps".format(mbps), style)
                val dl = value("DL", p.mbps, valueStyle.copy(color = DownlinkColor))
                val ul = value("UL", p.ulMbps, valueStyle.copy(color = UplinkColor))
                val padH = 4.dp.toPx()
                val padV = 1.dp.toPx()
                val bw = maxOf(dl.size.width, ul.size.width) + 2 * padH
                val bh = dl.size.height + ul.size.height + 2 * padV
                drawRoundRect(boxColor, topLeft = Offset(w - bw, 0f), size = Size(bw, bh), cornerRadius = CornerRadius(4.dp.toPx()))
                drawText(dl, topLeft = Offset(w - padH - dl.size.width, padV))
                drawText(ul, topLeft = Offset(w - padH - ul.size.width, padV + dl.size.height))
            }
        }
    }
}
