package dev.qcom.bandmenu.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The floating navigation bar is hidden (Info page menu). */
val LocalNavbarHidden = staticCompositionLocalOf { false }

/**
 * Space the pages leave at the bottom for the floating navigation bar: the
 * bar (64 dp), its 16 dp margin and the system navigation inset; with the
 * bar hidden, only the margin and the inset.
 */
@Composable
fun navbarSpace(): Dp {
    val inset = WindowInsets.navigationBars.asPaddingValues(LocalDensity.current).calculateBottomPadding()
    return if (LocalNavbarHidden.current) 16.dp + inset else 64.dp + 16.dp + inset
}

/**
 * Bottom padding of a control floating at the bottom of a page (Apply/Reset,
 * the replay bar): [gap] above the navigation bar, or, with the bar hidden,
 * centred on where the bar was (for a control about 50 dp tall), so it
 * doesn't drop to the screen's edge.
 */
@Composable
fun floatingControlBottom(gap: Dp): Dp {
    val inset = WindowInsets.navigationBars.asPaddingValues(LocalDensity.current).calculateBottomPadding()
    // The bar's centre was 16 + 32 dp above the inset; half a control is 25 dp.
    return if (LocalNavbarHidden.current) 16.dp + 32.dp - 25.dp + inset else navbarSpace() + gap
}
