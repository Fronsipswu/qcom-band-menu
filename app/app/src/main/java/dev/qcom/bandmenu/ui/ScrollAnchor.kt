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
    /**
     * The visible band (below the top bar, above the navbar) in [content]'s
     * coordinates at scroll 0.
     */
    var viewTop = 0f
    var viewBottom = 0f

    // Each card's top and bottom in content coordinates.
    private val cards = HashMap<String, Pair<Float, Float>>()
    private var key: String? = null
    // The anchor's top in the viewport (top - scroll.value) when chosen, and
    // whether the user was at the very top of the page then.
    private var anchorViewY = 0f
    private var anchoredAtTop = true
    private var choosing = true
    private var lastPlacedTop = Float.NEGATIVE_INFINITY
    private var targetScroll: Float? = null
    private var lastScroll = scroll.value
    private var lastMax = scroll.maxValue

    /** Empty space to put after the page, in px. */
    var slack by mutableFloatStateOf(0f)
        private set

    fun placed(k: String, top: Float, height: Int) {
        val bottom = top + height
        val old = cards[k]
        cards[k] = top to bottom
        if (scroll.isScrollInProgress) {
            // The user scrolls: drop what's left of a correction, and the
            // slack below the view.
            targetScroll = null
            slack = (slack - (scroll.maxValue - scroll.value)).coerceAtLeast(0f)
            choosing = true
            lastPlacedTop = top
            choose()
            return
        }
        if (old != null || top <= lastPlacedTop) choosing = false
        else lastPlacedTop = top

        if (k == key && old != null) {
            val shift = top - old.first
            val clamped = scroll.maxValue < lastMax && scroll.value == scroll.maxValue && scroll.value < lastScroll
            if (!anchoredAtTop && (abs(shift) >= 0.5f || clamped || targetScroll != null)) {
                // Keep the anchor at anchorViewY in the viewport across
                // measurement clamps and multi-pass height updates.
                val target = (top - anchorViewY).coerceAtLeast(0f)
                if (shift > 0f && slack > 0f) slack = (slack - shift).coerceAtLeast(0f)
                val need = target - scroll.value
                if (abs(need) >= 0.5f) scroll.dispatchRawDelta(need)
                val rem = target - scroll.value
                if (abs(rem) < 0.5f) {
                    targetScroll = null
                } else {
                    targetScroll = target
                    if ((shift < 0f || clamped) && target > scroll.maxValue) {
                        slack += target - scroll.maxValue
                    }
                }
                lastScroll = scroll.value
                lastMax = scroll.maxValue
                return
            }
            if (abs(shift) < 0.5f && scroll.value != lastScroll) {
                choose()
                return
            }
            lastScroll = scroll.value
            lastMax = scroll.maxValue
            return
        }
        // A new anchor only while the user scrolls (or during the pass that
        // populates cards when there is none), so a card that grows above the
        // anchor can't take its place.
        if (key == null || key !in cards) {
            choosing = true
            lastPlacedTop = top
        }
        if (choosing) choose()
    }

    /** The scrolled content changed size: finish a correction that didn't fit. */
    fun contentResized() {
        val target = targetScroll ?: return
        val need = target - scroll.value
        if (abs(need) >= 0.5f) scroll.dispatchRawDelta(need)
        if (abs(target - scroll.value) < 0.5f) targetScroll = null
        lastScroll = scroll.value
        lastMax = scroll.maxValue
    }

    fun removed(k: String) {
        cards.remove(k)
        if (key == k) {
            key = null
            targetScroll = null
            if (cards.isNotEmpty()) {
                choose()
            } else {
                slack = 0f
                choosing = true
                lastPlacedTop = Float.NEGATIVE_INFINITY
            }
        }
    }

    /** The card under the middle of the visible area, else the nearest one. */
    private fun choose() {
        val mid = (viewTop + viewBottom) / 2 + scroll.value
        val picked = cards.minByOrNull { (_, c) ->
            when {
                mid < c.first -> c.first - mid
                mid > c.second -> mid - c.second
                else -> 0f
            }
        }
        key = picked?.key
        if (picked != null) {
            anchorViewY = picked.value.first - scroll.value
            anchoredAtTop = scroll.value == 0
        }
        lastScroll = scroll.value
        lastMax = scroll.maxValue
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
