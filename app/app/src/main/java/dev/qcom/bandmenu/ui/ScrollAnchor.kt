package dev.qcom.bandmenu.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.math.abs

/**
 * Scroll anchoring, as browsers do it: the card in the middle of the visible
 * area stays where it is when cards above it change height or come and go
 * (an NSA page turning SA, the NR SCG card popping in and out), by scrolling
 * the same distance the card moved. Not at the very top of the page, so the
 * summary stays in view there.
 *
 * Near the bottom there may be no room for that: when cards above shrink,
 * [slack] (empty space after the page) makes up the lost height, and it is
 * given back as the user scrolls up. The page's own height follows its cards
 * a frame later, so what couldn't be scrolled at once is finished in
 * [contentResized].
 */
class ScrollAnchor(private val scroll: ScrollState) {
    /**
     * The scrolled content (inside `verticalScroll`). Cards are measured in it,
     * so neither the scroll, a pull-to-refresh nor the overscroll bounce moves
     * them: measured from outside, the bounce after a fling to the bottom
     * looked like a card above the anchor shrinking, the anchor scrolled
     * against it, and the bounce's clipped edge swept over the still cards.
     */
    var content: LayoutCoordinates? = null
    /** The visible band in the scrolling container (below the top bar, above the navbar). */
    var viewTop = 0f
    var viewBottom = 0f

    // Each card's top and bottom in content coordinates.
    private val cards = HashMap<String, Pair<Float, Float>>()
    private var key: String? = null

    /** Empty space to put after the page, in px. */
    var slack by mutableFloatStateOf(0f)
        private set
    // Correction not scrolled yet (the page hadn't grown enough).
    private var pending = 0f

    fun placed(k: String, top: Float, height: Int) {
        val bottom = top + height
        val old = cards[k]
        cards[k] = top to bottom
        if (k == key && old != null && !scroll.isScrollInProgress && scroll.value > 0) {
            // The anchor moved in the content: something above it changed.
            val shift = top - old.first
            if (abs(shift) >= 0.5f) {
                slack = if (shift < 0) slack - shift else (slack - shift).coerceAtLeast(0f)
                pending = shift - scroll.dispatchRawDelta(shift)
            }
            return
        }
        if (scroll.isScrollInProgress) {
            // The user scrolls: drop what's left of a correction, and the
            // slack below the view.
            pending = 0f
            slack = (slack - (scroll.maxValue - scroll.value)).coerceAtLeast(0f)
        }
        // A new anchor only while the user scrolls (or when there is none),
        // so a card that grows above the anchor can't take its place.
        if (key == null || key !in cards || scroll.isScrollInProgress) choose()
    }

    /** The scrolled content changed size: finish a correction that didn't fit. */
    fun contentResized() {
        if (abs(pending) >= 0.5f) pending -= scroll.dispatchRawDelta(pending)
    }

    fun removed(k: String) {
        cards.remove(k)
        if (key == k) key = null
    }

    /** The card under the middle of the visible area, else the nearest one. */
    private fun choose() {
        val mid = (viewTop + viewBottom) / 2 + scroll.value
        key = cards.minByOrNull { (_, c) ->
            when {
                mid < c.first -> c.first - mid
                mid > c.second -> mid - c.second
                else -> 0f
            }
        }?.key
    }
}

/** Reports this card's position to [anchor] under [key]; nothing when [anchor] is null. */
@Composable
fun Modifier.scrollAnchor(anchor: ScrollAnchor?, key: String): Modifier {
    if (anchor == null) return this
    DisposableEffect(anchor, key) { onDispose { anchor.removed(key) } }
    return onGloballyPositioned { c ->
        val content = anchor.content?.takeIf { it.isAttached } ?: return@onGloballyPositioned
        anchor.placed(key, content.localPositionOf(c, Offset.Zero).y, c.size.height)
    }
}
