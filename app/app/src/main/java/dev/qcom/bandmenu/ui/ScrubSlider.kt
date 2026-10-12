package dev.qcom.bandmenu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A slider over 0..[max] (miuix look) with fine scrubbing: while dragging,
 * moving the finger away from the bar vertically slows the thumb down
 * (1x, 1/2, 1/4, 1/10), so a long recording can still be stepped one sample
 * at a time. A tap jumps to the tapped spot. [onSpeed] reports the slowed
 * speed while it applies, null otherwise.
 */
@Composable
fun ScrubSlider(
    value: Int,
    max: Int,
    onValue: (Int) -> Unit,
    onSpeed: (Float?) -> Unit,
    modifier: Modifier = Modifier
) {
    val fg = MiuixTheme.colorScheme.primary
    val bg = MiuixTheme.colorScheme.sliderBackground
    val thumb = MiuixTheme.colorScheme.onPrimary
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val setValue by rememberUpdatedState(onValue)
    val setSpeed by rememberUpdatedState(onSpeed)

    Box(
        modifier
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .pointerInput(max) {
                val inset = 14.dp.toPx()
                val steps = listOf(40.dp.toPx() to 1f, 100.dp.toPx() to 0.5f, 160.dp.toPx() to 0.25f)
                fun speedFor(dy: Float) = steps.firstOrNull { dy < it.first }?.second ?: 0.1f
                fun valueAt(x: Float): Int {
                    val usable = (size.width - 2 * inset).coerceAtLeast(1f)
                    return (((x - inset) / usable) * max).roundToInt().coerceIn(0, max)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val usable = (size.width - 2 * inset).coerceAtLeast(1f)
                    var acc = current.toFloat()
                    var lastX = down.position.x
                    var dragging = false
                    var speed = 1f
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) {
                            if (!dragging) setValue(valueAt(c.position.x))
                            break
                        }
                        val dx = c.position.x - lastX
                        lastX = c.position.x
                        val dy = abs(c.position.y - down.position.y)
                        if (!dragging && (abs(c.position.x - down.position.x) > viewConfiguration.touchSlop ||
                                dy > viewConfiguration.touchSlop)) dragging = true
                        if (!dragging) continue
                        val s = speedFor(dy)
                        if (s != speed) {
                            speed = s
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            setSpeed(s.takeIf { it < 1f })
                        }
                        acc = (acc + dx / usable * max * speed).coerceIn(0f, max.toFloat())
                        val v = acc.roundToInt()
                        if (v != current) setValue(v)
                        c.consume()
                    }
                    setSpeed(null)
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
}
