package dev.qcom.bandmenu.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Four rising signal bars, for the Network tab (miuix has no signal icon). */
val SignalBarsIcon: ImageVector by lazy {
    ImageVector.Builder("SignalBars", 24.dp, 24.dp, 24f, 24f).apply {
        // x, top: bars 3 wide with 2 gaps, rounded ends.
        listOf(3f to 16f, 8.5f to 12f, 14f to 7.5f, 19.5f to 3f).forEach { (x, top) ->
            path(fill = SolidColor(Color.Black)) {
                moveTo(x + 0.75f, top)
                lineTo(x + 2.25f, top)
                arcTo(0.75f, 0.75f, 0f, false, true, x + 3f, top + 0.75f)
                lineTo(x + 3f, 20.25f)
                arcTo(0.75f, 0.75f, 0f, false, true, x + 2.25f, 21f)
                lineTo(x + 0.75f, 21f)
                arcTo(0.75f, 0.75f, 0f, false, true, x, 20.25f)
                lineTo(x, top + 0.75f)
                arcTo(0.75f, 0.75f, 0f, false, true, x + 0.75f, top)
                close()
            }
        }
    }.build()
}
