package dev.qcom.bandmenu.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * Double-ended open wrench for the Bands tab, tilted 45° to the right. An
 * outline (stroke, no fill) at the miuix Lock's line weight. Drawn along
 * the x axis and then rotated: round heads on a handle, each with a jaw slot
 * opening outwards.
 */
val WrenchIcon: ImageVector by lazy {
    // Head radius r, handle half-width hw, jaw slot half-width sw. The
    // handle meets each head dx from its centre, the slot opens sx out.
    val r = 4.6f; val hw = 2.2f; val sw = 1.6f
    val dx = sqrt(r * r - hw * hw); val sx = sqrt(r * r - sw * sw)
    val lc = 4f; val rc = 20f
    ImageVector.Builder("Wrench", 24.dp, 24.dp, 24f, 24f).apply {
        group(rotate = -45f, pivotX = 12f, pivotY = 12f) {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(lc + dx, 12f - hw)
                lineTo(rc - dx, 12f - hw)
                // Right head: over the top, into the jaw, back under.
                arcTo(r, r, 0f, false, true, rc + sx, 12f - sw)
                lineTo(rc + 0.3f, 12f - sw)
                arcTo(sw, sw, 0f, false, false, rc + 0.3f, 12f + sw)
                lineTo(rc + sx, 12f + sw)
                arcTo(r, r, 0f, false, true, rc - dx, 12f + hw)
                lineTo(lc + dx, 12f + hw)
                // Left head, mirrored.
                arcTo(r, r, 0f, false, true, lc - sx, 12f + sw)
                lineTo(lc - 0.3f, 12f + sw)
                arcTo(sw, sw, 0f, false, false, lc - 0.3f, 12f - sw)
                lineTo(lc - sx, 12f - sw)
                arcTo(r, r, 0f, false, true, lc + dx, 12f - hw)
                close()
            }
        }
    }.build()
}
