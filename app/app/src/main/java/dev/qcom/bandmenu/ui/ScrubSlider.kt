package dev.qcom.bandmenu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A slider over 0..[max] (miuix look) with fine scrubbing: while dragging,
 * moving the finger 80 dp or more away from the bar vertically slows the
 * thumb to a tenth, so a long recording can still be stepped one sample
 * at a time. Only a drag moves it (a touch without moving does nothing);
 * [onDragStart] fires as one begins. [value] and [max] may change during a
 * drag (the timeline was extended); the drag carries on from the new value.
 * While the slider is held, [bubble] (the value's label) shows in a bubble
 * above the thumb.
 */
@Composable
fun ScrubSlider(
    value: Int,
    max: Int,
    onValue: (Int) -> Unit,
    bubble: String,
    onDragStart: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val fg = MiuixTheme.colorScheme.primary
    val bg = MiuixTheme.colorScheme.sliderBackground
    val thumb = MiuixTheme.colorScheme.onPrimary
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val top by rememberUpdatedState(max)
    val dragStart by rememberUpdatedState(onDragStart)
    val setValue by rememberUpdatedState(onValue)
    var held by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }

    // The bubble's popup anchors to the layout it's placed in: this box, the
    // slider's own bounds (not the bar around it).
    Box(modifier) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width; heightPx = it.height }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .pointerInput(Unit) {
                val inset = 14.dp.toPx()
                val fineAt = 80.dp.toPx()
                fun speedFor(dy: Float) = if (dy < fineAt) 1f else 0.1f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    held = true
                    try {
                    val usable = (size.width - 2 * inset).coerceAtLeast(1f)
                    var acc = current.toFloat()
                    var lastSet = current
                    var lastX = down.position.x
                    var dragging = false
                    var speed = 1f
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val dx = c.position.x - lastX
                        lastX = c.position.x
                        val dy = abs(c.position.y - down.position.y)
                        if (!dragging && (abs(c.position.x - down.position.x) > viewConfiguration.touchSlop ||
                                dy > viewConfiguration.touchSlop)) {
                            dragging = true
                            dragStart()
                        }
                        if (!dragging) continue
                        // Moved from outside (the timeline was extended): carry on from there.
                        if (current != lastSet) { acc = current.toFloat(); lastSet = current }
                        val s = speedFor(dy)
                        if (s != speed) {
                            speed = s
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        acc = (acc + dx / usable * top * speed).coerceIn(0f, top.toFloat())
                        val v = acc.roundToInt()
                        if (v != current) { setValue(v); lastSet = v }
                        c.consume()
                    }
                    } finally {
                        held = false
                    }
                }
            }
            .drawBehind {
                val r = size.height / 2
                val tr = r - 4.dp.toPx()
                val frac = if (max > 0) value.toFloat() / max else 1f
                val cx = r + frac * (size.width - 2 * r)
                drawRoundRect(fg, size = Size(cx + r, size.height), cornerRadius = CornerRadius(r, r))
                drawCircle(thumb, radius = tr, center = Offset(cx, r))
            }
    )
    if (held && widthPx > 0) {
        val r = heightPx / 2f
        val frac = if (max > 0) value.toFloat() / max else 1f
        val thumbX = (r + frac * (widthPx - 2 * r)).roundToInt()
        Popup(popupPositionProvider = AboveThumb(thumbX, gap), properties = PopupProperties(clippingEnabled = false)) {
            Bubble(bubble)
        }
    }
    }
}

/** Places the bubble [gap] above the slider, centred over the thumb; it stops
 *  at the screen's left edge and at the slider's right end. */
private data class AboveThumb(val thumbX: Int, val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize
    ): IntOffset {
        val x = (anchorBounds.left + thumbX - popupContentSize.width / 2)
            .coerceAtMost(minOf(anchorBounds.right, windowSize.width) - popupContentSize.width)
            .coerceAtLeast(0)
        return IntOffset(x, anchorBounds.top - popupContentSize.height - gap)
    }
}

/** A plain box with the value, above the thumb. */
@Composable
private fun Bubble(text: String) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(MiuixTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, style = MiuixTheme.textStyles.footnote1.copy(fontFeatureSettings = "tnum"),
            color = MiuixTheme.colorScheme.onPrimary, maxLines = 1)
    }
}
