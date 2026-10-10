package dev.qcom.bandmenu.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Flat 8-tooth gear with a round hole, for the Bands tab. */
val GearIcon: ImageVector by lazy {
    val cx = 12f; val cy = 12f
    val teeth = 8
    val outer = 10.5f; val inner = 8f; val hole = 3.4f
    // Each tooth: flat top spanning ±toothHalf, root spanning the rest.
    val step = (2 * Math.PI / teeth).toFloat()
    val toothHalf = step * 0.22f
    val rootHalf = step * 0.30f
    fun px(r: Float, a: Float) = cx + r * cos(a)
    fun py(r: Float, a: Float) = cy + r * sin(a)
    ImageVector.Builder("Gear", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            for (i in 0 until teeth) {
                val a = i * step - (Math.PI / 2).toFloat()
                val p = listOf(
                    inner to a - rootHalf, outer to a - toothHalf,
                    outer to a + toothHalf, inner to a + rootHalf
                )
                p.forEachIndexed { j, (r, ang) ->
                    if (i == 0 && j == 0) moveTo(px(r, ang), py(r, ang)) else lineTo(px(r, ang), py(r, ang))
                }
            }
            close()
            // Centre hole (even-odd cut-out).
            moveTo(cx + hole, cy)
            arcTo(hole, hole, 0f, true, true, cx - hole, cy)
            arcTo(hole, hole, 0f, true, true, cx + hole, cy)
            close()
        }
    }.build()
}
